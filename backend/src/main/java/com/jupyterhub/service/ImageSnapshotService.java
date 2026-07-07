/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ImageSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(ImageSnapshotService.class);

    @Autowired
    private SshService sshService;

    private static final String BACKUP_DIR = "/opt/jupyterhub_prod/backups/";
    private static final String LOG_FILE = "/tmp/image_snapshot.log";

    private static final long DOCKER_RECOVERY_COOLDOWN_MS = 30000L;

    private static final long DOCKER_HEALTH_CHECK_INTERVAL_MS = 10000L;

    private static final int MAX_AUTO_RESTORE_FAILURES = 3;

    private static final long RESTORING_STATE_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final long STATUS_CACHE_TTL_MS = 3000L;

    private volatile long dockerLastRestartAt = 0L;     
    private volatile long dockerLastHealthCheckAt = 0L;  
    private volatile String dockerLastHealthState = "unknown"; 

    private volatile List<Map<String, Object>> statusCache = null;
    private volatile long statusCacheAt = 0L;

    private void invalidateAllCaches() {
        dockerLastHealthCheckAt = 0L;
        statusCache = null;
        statusCacheAt = 0L;
    }

    private static final Map<String, String> CRITICAL_IMAGES = new LinkedHashMap<>();
    static {
        CRITICAL_IMAGES.put("jupyter/base-notebook:latest", "jupyter-base-notebook.tar");
        CRITICAL_IMAGES.put("jupyter-cpu-zh:v18", "jupyter-cpu-zh-v18.tar");
        CRITICAL_IMAGES.put("jupyter-gpu-zh:v18", "jupyter-gpu-zh-v18.tar");
    }

    private final ConcurrentHashMap<String, String> imageStatusCache = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> restoringStartAt = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, String[]> backupFileMapping = new ConcurrentHashMap<>();
    private long lastBackupScanTime = 0;

    private final Object globalRestoreLock = new Object();
    private volatile boolean globalRestoreInProgress = false;

    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor();

    private final ConcurrentHashMap<String, Long> restoringImages = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Integer> failedRestoreCount = new ConcurrentHashMap<>();

    private void scanBackupFiles() {
        long now = System.currentTimeMillis();

        if (now - lastBackupScanTime < 5000 && !backupFileMapping.isEmpty()) {
            return;
        }
        lastBackupScanTime = now;

        String command = "ls " + BACKUP_DIR + "*.tar 2>/dev/null";
        String output = sshService.executeShortCommand(command, true);
        if (output == null || output.isEmpty()) {
            return;
        }

        Map<String, String[]> newMapping = new ConcurrentHashMap<>();
        String[] files = output.split("\n");
        for (String file : files) {
            file = file.trim();
            if (!file.endsWith(".tar")) continue;

            String fileName = file.substring(file.lastIndexOf("/") + 1);

            String[] info = getImageInfoFromTar(file);
            newMapping.put(fileName, info);
        }
        backupFileMapping.clear();
        backupFileMapping.putAll(newMapping);
    }

    private String[] getImageInfoFromTar(String tarPath) {

        String command = "tar -xOf " + tarPath + " manifest.json 2>/dev/null | head -c 2000";
        String output = sshService.executeShortCommand(command, true);

        if (output != null && !output.isEmpty()) {
            int repoTagsStart = output.indexOf("\"RepoTags\"");
            if (repoTagsStart > 0) {
                int arrayStart = output.indexOf("[", repoTagsStart);
                int arrayEnd = output.indexOf("]", arrayStart);
                if (arrayStart > 0 && arrayEnd > arrayStart) {
                    String tagInfo = output.substring(arrayStart + 1, arrayEnd);
                    int quoteStart = tagInfo.indexOf("\"");
                    int quoteEnd = tagInfo.indexOf("\"", quoteStart + 1);
                    if (quoteStart >= 0 && quoteEnd > quoteStart) {
                        String fullName = tagInfo.substring(quoteStart + 1, quoteEnd);
                        String[] parts = fullName.split(":", 2);
                        String repo = parts[0];
                        String tag = parts.length > 1 ? parts[1] : "latest";
                        return new String[]{fullName, repo, tag};
                    }
                }
            }
        }

        String fileName = tarPath.substring(tarPath.lastIndexOf("/") + 1);

        if (fileName.endsWith(".tar")) {
            String baseName = fileName.substring(0, fileName.length() - 4);
            int lastDash = baseName.lastIndexOf("-");
            if (lastDash > 0) {
                String repo = baseName.substring(0, lastDash);
                String tag = baseName.substring(lastDash + 1);
                String fullName = repo + ":" + tag;
                return new String[]{fullName, repo, tag};
            }
        }
        return null;
    }

    private String[] findBackupByImageName(String repo, String tag) {
        scanBackupFiles();
        String fullName = repo + ":" + tag;
        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String[] info = entry.getValue();
            if (info != null && info[0].equals(fullName)) {
                return new String[]{entry.getKey(), info[1], info[2]};
            }
        }
        return null;
    }

    private String findBackupFileByImageName(String fullName) {
        scanBackupFiles();
        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String[] info = entry.getValue();
            if (info != null && info[0].equals(fullName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<Map<String, Object>> getImageStatus() {

        cleanupStuckRestoringStates();

        long now = System.currentTimeMillis();
        if (statusCache != null && now - statusCacheAt < STATUS_CACHE_TTL_MS) {
            return statusCache;
        }

        scanBackupFiles();
        List<Map<String, Object>> images = new ArrayList<>();

        boolean dockerOK = isDockerRunning();
        boolean inCooldown = isInDockerCooldown();

        Map<String, String[]> backupInfoMap = getAllBackupFileInfo();

        Set<String> existingImages = new HashSet<>();
        if (dockerOK) {
            String output = sshService.executeShortCommand("docker images --format '{{.Repository}}|{{.Tag}}|{{.ID}}|{{.Size}}'", true);
            if (output != null && !output.isEmpty()) {
                String[] lines = output.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length >= 4) {
                        String repo = parts[0].trim();
                        String tag = parts[1].trim();
                        String id = parts[2].trim();
                        String size = parts[3].trim();

                        String fullName = repo + ":" + tag;
                        existingImages.add(fullName);

                        Map<String, Object> image = new HashMap<>();
                        image.put("repository", repo);
                        image.put("tag", tag);
                        image.put("fullName", fullName);
                        image.put("id", id);
                        image.put("size", size);

                        boolean isCritical = CRITICAL_IMAGES.containsKey(fullName);
                        image.put("isCritical", isCritical);

                        String backupFile = findBackupFileByImageName(fullName);
                        if (backupFile == null && isCritical) {
                            backupFile = CRITICAL_IMAGES.get(fullName);
                        }

                        boolean hasBackup = backupFile != null;
                        if (hasBackup) {

                            if (!backupInfoMap.containsKey(backupFile)) {
                                hasBackup = checkBackupExists(backupFile);
                            }
                        }
                        image.put("hasBackup", hasBackup);
                        image.put("backupPath", hasBackup ? BACKUP_DIR + backupFile : "");
                        String[] bin = backupInfoMap.get(backupFile);
                        image.put("snapshotTime", hasBackup && bin != null ? bin[1] : (hasBackup ? getSnapshotTime(backupFile) : ""));

                        String cachedStatus = imageStatusCache.get(fullName);
                        if ("restoring".equals(cachedStatus)) {
                            imageStatusCache.put(fullName, "normal");
                            restoringStartAt.remove(fullName);
                            restoringImages.remove(fullName);
                            failedRestoreCount.remove(fullName);
                            image.put("status", "normal");
                        } else if ("processing".equals(cachedStatus)) {
                            image.put("status", "processing");
                        } else {
                            image.put("status", "normal");
                        }
                        images.add(image);
                    }
                }
            }
        }

        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String fileName = entry.getKey();
            String[] info = entry.getValue();
            if (info == null) continue;

            String fullName = info[0];
            if (dockerOK && existingImages.contains(fullName)) continue;

            String repo = info[1];
            String tag = info[2];

            Map<String, Object> image = new HashMap<>();
            image.put("repository", repo);
            image.put("tag", tag);
            image.put("fullName", fullName);
            image.put("id", "");
            image.put("size", "N/A");
            image.put("isCritical", CRITICAL_IMAGES.containsKey(fullName));
            image.put("hasBackup", true);
            image.put("backupPath", BACKUP_DIR + fileName);

            String[] bin = backupInfoMap.get(fileName);
            image.put("snapshotTime", bin != null ? bin[1] : "");

            String cachedStatus = imageStatusCache.get(fullName);
            if ("restoring".equals(cachedStatus)) {
                image.put("status", "restoring");
            } else if ("restore_failed".equals(cachedStatus)) {
                image.put("status", "restore_failed");
            } else if (!dockerOK || inCooldown) {
                image.put("status", "waiting_docker");
                imageStatusCache.put(fullName, "waiting_docker");
            } else {
                imageStatusCache.put(fullName, "missing");
                image.put("status", "missing");
            }

            images.add(image);
        }

        images.sort((a, b) -> {
            boolean aHasBackup = (Boolean) a.get("hasBackup");
            boolean bHasBackup = (Boolean) b.get("hasBackup");
            if (aHasBackup && !bHasBackup) return -1;
            if (!aHasBackup && bHasBackup) return 1;

            boolean aCritical = (Boolean) a.get("isCritical");
            boolean bCritical = (Boolean) b.get("isCritical");
            if (aCritical && !bCritical) return -1;
            if (!aCritical && bCritical) return 1;

            String aName = (String) a.get("fullName");
            String bName = (String) b.get("fullName");
            return aName.compareTo(bName);
        });

        statusCache = images;
        statusCacheAt = System.currentTimeMillis();

        return images;
    }

    private boolean checkBackupExists(String backupFile) {
        String command = "ls -la " + BACKUP_DIR + backupFile + " 2>/dev/null";
        String output = sshService.executeShortCommand(command, true);
        return output != null && !output.isEmpty() && !output.startsWith("ERROR");
    }

    private String getSnapshotTime(String backupFile) {
        String command = "stat -c %y " + BACKUP_DIR + backupFile + " 2>/dev/null | cut -d' ' -f1,2";
        String output = sshService.executeShortCommand(command, true);
        if (output != null && !output.isEmpty() && !output.startsWith("ERROR")) {
            String date = output.trim();

            int dotIndex = date.indexOf('.');
            if (dotIndex > 0) {
                date = date.substring(0, dotIndex);
            }
            return date;
        }
        return "";
    }

    private Map<String, String[]> getAllBackupFileInfo() {
        Map<String, String[]> result = new HashMap<>();

        String command = "stat -c \"%n|%s|%y\" " + BACKUP_DIR + "*.tar 2>/dev/null | head -100";
        String output = sshService.executeShortCommand(command, true);
        if (output == null || output.isEmpty()) {
            return result;
        }
        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 3) {

                String fullPath = parts[0].trim();
                String fileName = fullPath.substring(fullPath.lastIndexOf('/') + 1);
                long bytes;
                try {
                    bytes = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                String date = parts[2].trim();
                int dotIndex = date.indexOf('.');
                if (dotIndex > 0) {
                    date = date.substring(0, dotIndex);
                }
                String sizeReadable = formatSize(bytes);
                result.put(fileName, new String[]{sizeReadable, date});
            }
        }
        return result;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fkB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
        return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
    }

    private boolean tryAcquireRestoreLock(String fullName) {
        Long existing = restoringImages.putIfAbsent(fullName, System.currentTimeMillis());
        if (existing != null) {

            if (System.currentTimeMillis() - existing > 30 * 60 * 1000) {
                restoringImages.remove(fullName, existing);
                Long recheck = restoringImages.putIfAbsent(fullName, System.currentTimeMillis());
                return recheck == null;
            }
            return false;
        }
        return true;
    }

    private void releaseRestoreLock(String fullName) {
        restoringImages.remove(fullName);
    }

    private boolean isDockerLoadAlreadyRunning(String tarFileName) {
        try {

            String checkCmd = "ps aux | grep 'docker load' | grep '" + tarFileName + "' | grep -v grep | wc -l";
            String output = sshService.executeShortCommand(checkCmd, true);
            if (output != null) {
                String trimmed = output.trim();
                try {
                    int count = Integer.parseInt(trimmed);
                    return count > 0;
                } catch (NumberFormatException e) {

                    return !trimmed.isEmpty() && !trimmed.equals("0");
                }
            }
        } catch (Exception e) {

            logger.warn("检测 docker load 进程失败: " + e.getMessage());
        }
        return false;
    }

    private boolean isDockerRunning() {
        long now = System.currentTimeMillis();
        if (now - dockerLastHealthCheckAt < DOCKER_HEALTH_CHECK_INTERVAL_MS) {

            return "running".equals(dockerLastHealthState);
        }

        try {
            String output = sshService.executeShortCommand("docker info 2>&1", true);
            dockerLastHealthCheckAt = now;
            boolean running = output != null
                    && output.contains("Server Version")
                    && !output.contains("Cannot connect");
            dockerLastHealthState = running ? "running" : "down";
            return running;
        } catch (Exception e) {
            dockerLastHealthState = "down";
            return false;
        }
    }

    private boolean isInDockerCooldown() {
        if (dockerLastRestartAt == 0L) return false;
        return (System.currentTimeMillis() - dockerLastRestartAt) < DOCKER_RECOVERY_COOLDOWN_MS;
    }

    public Map<String, Object> getDockerHealth() {
        Map<String, Object> result = new HashMap<>();

        dockerLastHealthCheckAt = 0L;
        boolean running = isDockerRunning();
        boolean cooldown = isInDockerCooldown();
        long cooldownRemainingSec = 0;
        if (cooldown) {
            cooldownRemainingSec = (DOCKER_RECOVERY_COOLDOWN_MS - (System.currentTimeMillis() - dockerLastRestartAt)) / 1000;
        }

        result.put("running", running);
        result.put("cooldown", cooldown);
        result.put("cooldownRemainingSec", cooldownRemainingSec);
        if (running) {
            result.put("status", "running");
            result.put("message", "Docker 正常运行");
        } else if (cooldown) {
            result.put("status", "recovering");
            result.put("message", "Docker 刚刚重启，等待 " + cooldownRemainingSec + " 秒后开始恢复镜像");
        } else {
            result.put("status", "down");
            result.put("message", "Docker 未运行，后台正在尝试启动...");
        }
        return result;
    }

    private boolean ensureDockerRunning() {
        if (isDockerRunning()) {
            return true;
        }

        try {
            appendLog("检测到 Docker 未运行，尝试重启 Docker 服务...");
            sshService.executeShortCommand("systemctl start docker.service 2>&1", true);
            Thread.sleep(5000);

            invalidateAllCaches(); 
            if (isDockerRunning()) {
                dockerLastRestartAt = System.currentTimeMillis();
                appendLog("Docker 重启成功，进入 " + (DOCKER_RECOVERY_COOLDOWN_MS / 1000) + " 秒冷却期");
                return true;
            }

            sshService.executeShortCommand("systemctl start docker 2>&1", true);
            Thread.sleep(5000);

            invalidateAllCaches();
            if (isDockerRunning()) {
                dockerLastRestartAt = System.currentTimeMillis();
                appendLog("Docker 重启成功 (systemctl start docker)，进入冷却期");
                return true;
            }

            appendLog("Docker 重启失败");
            return false;
        } catch (Exception e) {
            appendLog("Docker 重启异常: " + e.getMessage());
            return false;
        }
    }

    private boolean isRestoring(String fullName) {
        Long time = restoringImages.get(fullName);
        if (time == null) return false;

        if (System.currentTimeMillis() - time > RESTORING_STATE_TIMEOUT_MS) {
            restoringImages.remove(fullName, time);
            imageStatusCache.remove(fullName, "restoring");
            restoringStartAt.remove(fullName);
            return false;
        }
        return true;
    }

    private void cleanupStuckRestoringStates() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : restoringStartAt.entrySet()) {
            if (now - entry.getValue() > RESTORING_STATE_TIMEOUT_MS) {
                String fullName = entry.getKey();
                imageStatusCache.put(fullName, "missing");
                restoringImages.remove(fullName);
                restoringStartAt.remove(fullName);
                appendLog("镜像 " + fullName + " 恢复超时，已重置为缺失状态");
            }
        }
    }

    public Map<String, Object> restoreImage(String fullName) {
        Map<String, Object> result = new HashMap<>();

        dockerLastHealthCheckAt = 0L;

        if (isRestoring(fullName)) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "镜像 " + fullName + " 正在恢复中，请稍候");
            return result;
        }

        if (!tryAcquireGlobalRestoreLock()) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "系统正在恢复其他镜像，" + fullName + " 稍后自动开始");
            return result;
        }

        try {

            String backupFile = findBackupFileByImageName(fullName);
            if (backupFile == null) {
                backupFile = CRITICAL_IMAGES.get(fullName);
            }
            if (backupFile == null) {
                result.put("success", false);
                result.put("message", "未找到镜像 " + fullName + " 的备份文件");
                return result;
            }

            String backupPath = BACKUP_DIR + backupFile;

            if (!checkBackupExists(backupFile)) {
                result.put("success", false);
                result.put("message", "备份文件不存在: " + backupPath);
                return result;
            }

            if (isInDockerCooldown()) {
                long remaining = (DOCKER_RECOVERY_COOLDOWN_MS - (System.currentTimeMillis() - dockerLastRestartAt)) / 1000;
                result.put("success", false);
                result.put("message", "Docker 刚刚重启，等待 " + remaining + " 秒后再试，或稍后手动点击恢复");
                return result;
            }

            if (!ensureDockerRunning()) {
                result.put("success", false);
                result.put("message", "Docker 服务未运行，已尝试重启但失败，请手动启动 Docker");
                return result;
            }

            if (!tryAcquireRestoreLock(fullName)) {
                result.put("success", true);
                result.put("restoring", true);
                result.put("message", "镜像 " + fullName + " 正在恢复中，请稍候");
                return result;
            }

            try {
                imageStatusCache.put(fullName, "restoring");
                restoringStartAt.put(fullName, System.currentTimeMillis());
                appendLog("开始恢复镜像: " + fullName);

                String tarFile = backupPath.substring(backupPath.lastIndexOf('/') + 1);
                if (isDockerLoadAlreadyRunning(tarFile)) {
                    appendLog("检测到服务器上已有 docker load 进程处理 " + tarFile + "，等待其完成");

                    long waitStart = System.currentTimeMillis();
                    boolean stillRunning = true;
                    while (stillRunning && System.currentTimeMillis() - waitStart < RESTORING_STATE_TIMEOUT_MS) {
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        stillRunning = isDockerLoadAlreadyRunning(tarFile);
                    }

                    String checkOutput = sshService.executeShortCommand(
                            "docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v '^<none>'", true);
                    boolean loaded = false;
                    if (checkOutput != null && !checkOutput.isEmpty()) {
                        for (String line : checkOutput.split("\n")) {
                            if (fullName.equals(line.trim())) {
                                loaded = true;
                                break;
                            }
                        }
                    }
                    if (loaded) {
                        imageStatusCache.put(fullName, "normal");
                        restoringStartAt.remove(fullName);
                        failedRestoreCount.remove(fullName);
                        appendLog("镜像恢复成功（等待后台进程完成）: " + fullName);
                        result.put("success", true);
                        result.put("message", "镜像恢复成功");
                        return result;
                    } else {
                        appendLog("后台进程结束但镜像仍未加载: " + fullName + "，重新执行 docker load");

                    }
                }

                String command = "docker load -i " + backupPath;
                String output = sshService.executeLongRunningCommand(command, true);

                if (output != null && (output.contains("Loaded image") || output.contains("Loaded:"))) {
                    imageStatusCache.put(fullName, "normal");
                    restoringStartAt.remove(fullName);
                    failedRestoreCount.remove(fullName); 
                    appendLog("镜像恢复成功: " + fullName);
                    result.put("success", true);
                    result.put("message", "镜像恢复成功");
                } else {
                    imageStatusCache.put(fullName, "missing");
                    restoringStartAt.remove(fullName);

                    failedRestoreCount.merge(fullName, 1, Integer::sum);
                    int fc = failedRestoreCount.getOrDefault(fullName, 0);
                    if (fc >= MAX_AUTO_RESTORE_FAILURES) {
                        imageStatusCache.put(fullName, "restore_failed");
                        appendLog("镜像恢复失败(" + fc + "次): " + fullName + "，不再自动恢复，请手动操作。错误: " + (output != null ? output : "未知"));
                    } else {
                        appendLog("镜像恢复失败: " + fullName + ", 错误: " + (output != null ? output : "未知"));
                    }
                    result.put("success", false);
                    result.put("message", "镜像恢复失败: " + (output != null ? output : "未知错误"));
                }
            } finally {
                releaseRestoreLock(fullName);
            }

            return result;
        } finally {
            releaseGlobalRestoreLock();
        }
    }

    private boolean tryAcquireGlobalRestoreLock() {
        synchronized (globalRestoreLock) {
            if (globalRestoreInProgress) {
                return false;
            }
            globalRestoreInProgress = true;
            return true;
        }
    }

    private void releaseGlobalRestoreLock() {
        synchronized (globalRestoreLock) {
            globalRestoreInProgress = false;
        }
    }

    public Map<String, Object> deleteBackup(String fullName) {
        Map<String, Object> result = new HashMap<>();

        scanBackupFiles();
        String backupFile = null;
        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String[] info = entry.getValue();
            if (info != null && info[0].equals(fullName)) {
                backupFile = entry.getKey();
                break;
            }
        }

        if (backupFile == null) {
            result.put("success", false);
            result.put("message", "未找到该镜像的备份文件");
            return result;
        }

        String backupPath = BACKUP_DIR + backupFile;
        String command = "rm -rf " + backupPath;
        String output = sshService.executeShortCommand(command, true);

        if (output == null || output.isEmpty()) {
            appendLog("删除备份成功: " + fullName + ", 路径: " + backupPath);
            backupFileMapping.remove(backupFile);
            imageStatusCache.remove(fullName);
            result.put("success", true);
            result.put("message", "删除成功");
            result.put("backupFile", backupFile);
        } else {
            appendLog("删除备份失败: " + fullName + ", 错误: " + output);
            result.put("success", false);
            result.put("message", "删除失败: " + output);
        }

        return result;
    }

    public Map<String, Object> autoRestoreMissingImages() {
        Map<String, Object> result = new HashMap<>();
        List<String> restoredImages = new ArrayList<>();
        List<String> failedImages = new ArrayList<>();
        List<String> skippedImages = new ArrayList<>();

        if (isInDockerCooldown()) {
            long remaining = (DOCKER_RECOVERY_COOLDOWN_MS - (System.currentTimeMillis() - dockerLastRestartAt)) / 1000;
            result.put("success", false);
            result.put("message", "Docker 刚刚重启，等待 " + remaining + " 秒后再试。请耐心等待 Docker 完全就绪。");
            return result;
        }

        if (!isDockerRunning()) {
            if (!ensureDockerRunning()) {
                result.put("success", false);
                result.put("message", "Docker 服务未运行，已尝试重启但失败。请手动启动 Docker 后再试。");
                return result;
            }

            long remaining = DOCKER_RECOVERY_COOLDOWN_MS / 1000;
            result.put("success", true);
            result.put("message", "Docker 已成功启动。为保证稳定，等待 " + remaining + " 秒冷却期后再恢复镜像。");
            return result;
        }

        if (!tryAcquireGlobalRestoreLock()) {
            result.put("success", true);
            result.put("message", "已有恢复任务正在进行中，请稍候...");
            return result;
        }

        try {

            scanBackupFiles();
            Set<String> existingImages = new HashSet<>();
            String dockerOutput = sshService.executeShortCommand(
                    "docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v '^<none>'", true);
            if (dockerOutput != null && !dockerOutput.isEmpty()) {
                for (String line : dockerOutput.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.contains(":")) {
                        existingImages.add(trimmed);
                    }
                }
            }

            for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
                String[] info = entry.getValue();
                if (info == null) continue;

                String fullName = info[0];

                if (existingImages.contains(fullName)) {
                    String cachedStatus = imageStatusCache.get(fullName);
                    if ("restoring".equals(cachedStatus)) {
                        imageStatusCache.put(fullName, "normal");
                        restoringStartAt.remove(fullName);
                        restoringImages.remove(fullName);
                        failedRestoreCount.remove(fullName);
                    }
                    continue;
                }

                if (isRestoring(fullName)) {
                    String fileName2 = entry.getKey();
                    String tarPath2 = fileName2.startsWith("/") ? fileName2 : (BACKUP_DIR + fileName2);
                    String tarFile2 = tarPath2.substring(tarPath2.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile2)) {
                        skippedImages.add(fullName);
                        continue;
                    }

                    imageStatusCache.remove(fullName, "restoring");
                    restoringStartAt.remove(fullName);
                    restoringImages.remove(fullName);
                }

                Integer fc = failedRestoreCount.get(fullName);
                if (fc != null && fc >= MAX_AUTO_RESTORE_FAILURES) {
                    imageStatusCache.put(fullName, "restore_failed");
                    skippedImages.add(fullName);
                    continue;
                }

                if (!tryAcquireRestoreLock(fullName)) {
                    skippedImages.add(fullName);
                    continue;
                }

                try {
                    imageStatusCache.put(fullName, "restoring");
                    restoringStartAt.put(fullName, System.currentTimeMillis());
                    appendLog("【手动】自动恢复镜像: " + fullName);

                    String fileName = entry.getKey();
                    String backupPath = fileName.startsWith("/") ? fileName : (BACKUP_DIR + fileName);

                    String tarFile = backupPath.substring(backupPath.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile)) {
                        appendLog("【手动】检测到服务器上已有 docker load 进程处理 " + tarFile + "，等待其完成");

                        long waitStart = System.currentTimeMillis();
                        boolean stillRunning = true;
                        while (stillRunning && System.currentTimeMillis() - waitStart < RESTORING_STATE_TIMEOUT_MS) {
                            try {
                                Thread.sleep(10000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            stillRunning = isDockerLoadAlreadyRunning(tarFile);
                        }

                        String verifyOutput = sshService.executeShortCommand(
                                "docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v '^<none>'", true);
                        boolean loaded = false;
                        if (verifyOutput != null && !verifyOutput.isEmpty()) {
                            for (String line : verifyOutput.split("\n")) {
                                if (fullName.equals(line.trim())) {
                                    loaded = true;
                                    existingImages.add(fullName);
                                    break;
                                }
                            }
                        }
                        if (loaded) {
                            imageStatusCache.put(fullName, "normal");
                            restoringStartAt.remove(fullName);
                            failedRestoreCount.remove(fullName);
                            appendLog("【手动】自动恢复成功（等待后台进程）: " + fullName);
                            restoredImages.add(fullName);
                            continue;
                        }

                    }

                    String restoreOutput = sshService.executeLongRunningCommand("docker load -i " + backupPath + " 2>&1", true);

                    if (restoreOutput != null && (restoreOutput.contains("Loaded image") || restoreOutput.contains("Loaded:"))) {
                        imageStatusCache.put(fullName, "normal");
                        restoringStartAt.remove(fullName);
                        failedRestoreCount.remove(fullName);
                        appendLog("【手动】自动恢复成功: " + fullName);
                        restoredImages.add(fullName);
                        existingImages.add(fullName);
                    } else {
                        imageStatusCache.put(fullName, "missing");
                        restoringStartAt.remove(fullName);
                        failedRestoreCount.merge(fullName, 1, Integer::sum);
                        int failCount = failedRestoreCount.getOrDefault(fullName, 0);
                        if (failCount >= MAX_AUTO_RESTORE_FAILURES) {
                            imageStatusCache.put(fullName, "restore_failed");
                            appendLog("【手动】镜像恢复失败(" + failCount + "次): " + fullName + "，后续不再自动恢复，请手动操作");
                        } else {
                            appendLog("【手动】自动恢复失败: " + fullName + ", 输出: " + (restoreOutput != null ? restoreOutput : "未知"));
                        }
                        failedImages.add(fullName);
                    }
                } finally {
                    releaseRestoreLock(fullName);
                }
            }

            int total = restoredImages.size() + failedImages.size() + skippedImages.size();
            if (total == 0) {
                result.put("success", true);
                result.put("message", "没有需要恢复的缺失镜像");
            } else {
                result.put("success", true);
                String msg = String.format("自动恢复完成: 成功 %d 个, 失败 %d 个",
                        restoredImages.size(), failedImages.size());
                if (!skippedImages.isEmpty()) {
                    msg += String.format(", 跳过 %d 个(正在恢复/超过失败次数)", skippedImages.size());
                }
                result.put("message", msg);
            }
            result.put("restoredCount", restoredImages.size());
            result.put("restoredImages", restoredImages);
            result.put("failedImages", failedImages);
            return result;

        } finally {
            releaseGlobalRestoreLock();
        }
    }

    public Map<String, Object> createSnapshot(String repository, String tag) {
        Map<String, Object> result = new HashMap<>();

        String fullName = repository + ":" + tag;
        String backupFile = getBackupFileName(repository, tag);
        String backupPath = BACKUP_DIR + backupFile;

        String currentStatus = imageStatusCache.get(fullName);
        if ("processing".equals(currentStatus)) {
            result.put("success", false);
            result.put("message", "该镜像正在备份中，请勿重复点击");
            result.put("fullName", fullName);
            return result;
        }

        try {
            String checkCmd = "stat -c %s " + backupPath + " 2>/dev/null";
            String checkOutput = sshService.executeShortCommand(checkCmd, true);
            if (checkOutput != null && !checkOutput.trim().isEmpty()) {
                try {
                    long size = Long.parseLong(checkOutput.trim());
                    if (size > 1024 * 1024) { 
                        result.put("success", false);
                        result.put("message", "该镜像已有备份，如需重新备份请先删除旧备份");
                        result.put("fullName", fullName);
                        return result;
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {

        }

        imageStatusCache.put(fullName, "processing");
        appendLog("开始创建快照: " + fullName);

        snapshotExecutor.submit(() -> {
            String command = "docker save -o " + backupPath + " " + fullName;
            String output = sshService.executeLongRunningCommand(command, true);

            boolean hasRealError = output != null && !output.isEmpty()
                    && (output.toLowerCase().contains("error")
                    || output.toLowerCase().contains("no such image")
                    || output.toLowerCase().contains("permission denied")
                    || output.toLowerCase().contains("cannot connect")
                    || output.toLowerCase().contains("failed"));

            boolean tarExists = false;
            if (!hasRealError) {

                String checkCmd = "stat -c %s " + backupPath + " 2>/dev/null";
                String checkOutput = sshService.executeShortCommand(checkCmd, true);
                if (checkOutput != null && !checkOutput.trim().isEmpty()) {
                    try {
                        long size = Long.parseLong(checkOutput.trim());
                        tarExists = size > 0;
                    } catch (NumberFormatException ignored) {
                        tarExists = false;
                    }
                }
            }

            boolean success = !hasRealError && tarExists;

            if (success) {
                imageStatusCache.put(fullName, "normal");
                appendLog("快照创建成功: " + fullName + ", 路径: " + backupPath);
                lastBackupScanTime = 0;
            } else {
                imageStatusCache.remove(fullName);

                sshService.executeShortCommand("rm -f " + backupPath, true);
                appendLog("快照创建失败: " + fullName + ", 错误: " + output);
            }
        });

        result.put("success", true);
        result.put("message", "备份任务已提交，请等待后台执行完成");
        result.put("fullName", fullName);

        return result;
    }

    private String getBackupFileName(String repo, String tag) {
        String safeRepo = repo.replace("/", "-").replace(":", "-");
        String safeTag = tag.replace(":", "-");
        return safeRepo + "-" + safeTag + ".tar";
    }

    public List<String> getLogs() {
        List<String> logs = new ArrayList<>();
        String output = sshService.executeShortCommand("cat " + LOG_FILE, true);

        if (output != null && !output.isEmpty()) {
            String[] lines = output.split("\n");
            for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) {
                logs.add(lines[i]);
            }
        }

        return logs;
    }

    public boolean clearLogs() {
        String command = "> " + LOG_FILE;
        sshService.executeShortCommand(command, true);
        String output = sshService.executeShortCommand("cat " + LOG_FILE, true);
        return output == null || output.isEmpty();
    }

    public Map<String, Object> cleanDanglingImages() {
        Map<String, Object> result = new HashMap<>();

        appendLog("开始清理 <none> 悬挂镜像");

        String command = "docker image prune -f 2>&1";
        String output = sshService.executeShortCommand(command, true);

        appendLog("清理悬挂镜像输出: " + output);

        if (output != null && (output.contains("deleted") || output.contains("Deleted") || output.contains("untagged") || output.contains("Untagged") || output.contains("Total reclaimed space"))) {
            appendLog("清理悬挂镜像成功");
            result.put("success", true);
            result.put("message", "清理成功: " + output);
        } else if (output != null && output.contains("Nothing to prune")) {
            appendLog("没有需要清理的悬挂镜像");
            result.put("success", true);
            result.put("message", "没有需要清理的悬挂镜像");
        } else {
            appendLog("清理悬挂镜像失败: " + output);
            result.put("success", false);
            result.put("message", "清理失败: " + output);
        }

        return result;
    }

    public Map<String, Object> deleteImage(String fullName) {
        Map<String, Object> result = new HashMap<>();

        appendLog("开始删除镜像: " + fullName);

        String command = "docker rmi -f " + fullName + " 2>&1";
        String output = sshService.executeShortCommand(command, true);

        appendLog("删除镜像 " + fullName + " 命令输出: " + output);

        if (output == null || output.isEmpty() || output.contains("Untagged") || output.contains("Deleted")) {
            appendLog("删除镜像成功: " + fullName);
            imageStatusCache.remove(fullName);
            result.put("success", true);
            result.put("message", "删除成功");
        } else if (output.contains("image is being used") || output.contains("image has dependent children")) {

            appendLog("镜像被使用，尝试停止容器后强制删除: " + fullName);
            String stopCmd = "docker ps -a -q --filter ancestor=" + fullName + " | xargs -r docker stop 2>&1";
            String stopOutput = sshService.executeShortCommand(stopCmd, true);
            appendLog("停止容器输出: " + stopOutput);

            String retryOutput = sshService.executeShortCommand(command, true);
            appendLog("强制删除重试输出: " + retryOutput);

            if (retryOutput.contains("Untagged") || retryOutput.contains("Deleted") || retryOutput.isEmpty()) {
                appendLog("强制删除镜像成功: " + fullName);
                imageStatusCache.remove(fullName);
                result.put("success", true);
                result.put("message", "强制删除成功");
            } else {
                appendLog("删除镜像失败: " + fullName + ", 错误: " + retryOutput);
                result.put("success", false);
                result.put("message", "删除失败: " + retryOutput);
            }
        } else {
            appendLog("删除镜像失败: " + fullName + ", 错误: " + output);
            result.put("success", false);
            result.put("message", "删除失败: " + output);
        }

        return result;
    }

    private void appendLog(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String logLine = "[" + timestamp + "] " + message;
        String command = "echo '" + logLine + "' >> " + LOG_FILE;
        sshService.executeShortCommand(command, true);
    }

    @Scheduled(fixedRate = 18000)
    public void scheduledAutoRestore() {
        try {

            dockerLastHealthCheckAt = 0L;

            cleanupStuckRestoringStates();

            if (isInDockerCooldown()) {
                return;
            }

            if (!isDockerRunning()) {

                if (ensureDockerRunning()) {

                    return;
                }

                appendLog("【定时任务】Docker 无法启动，跳过本次检查");
                return;
            }

            if (!tryAcquireGlobalRestoreLock()) {
                return;
            }

            try {

                scanBackupFiles();

                Set<String> existingImages = new HashSet<>();
                String output = sshService.executeShortCommand(
                        "docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v '^<none>'", true);
                if (output != null && !output.isEmpty()) {
                    for (String line : output.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && trimmed.contains(":")) {
                            existingImages.add(trimmed);
                        }
                    }
                }

                String targetFullName = null;
                String targetTarPath = null;

                for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
                    String[] info = entry.getValue();
                    if (info == null) continue;

                    String fullName = info[0];

                    if (existingImages.contains(fullName)) {
                        String cachedStatus = imageStatusCache.get(fullName);
                        if ("restoring".equals(cachedStatus)) {
                            imageStatusCache.put(fullName, "normal");
                            restoringStartAt.remove(fullName);
                            restoringImages.remove(fullName);
                            failedRestoreCount.remove(fullName);
                            appendLog("【定时任务】检测到镜像 " + fullName + " 已在 Docker 中，清理恢复状态");
                        }
                        continue;
                    }

                    if (isRestoring(fullName)) {
                        String fileName2 = entry.getKey();
                        String tarPath2 = fileName2.startsWith("/") ? fileName2 : (BACKUP_DIR + fileName2);
                        String tarFile2 = tarPath2.substring(tarPath2.lastIndexOf('/') + 1);
                        if (isDockerLoadAlreadyRunning(tarFile2)) {

                            continue;
                        }

                        imageStatusCache.remove(fullName, "restoring");
                        restoringStartAt.remove(fullName);
                        restoringImages.remove(fullName);
                        appendLog("【定时任务】镜像 " + fullName + " 的恢复进程已结束但未成功，清理状态可重新恢复");
                    }

                    Integer fc = failedRestoreCount.get(fullName);
                    if (fc != null && fc >= MAX_AUTO_RESTORE_FAILURES) continue;

                    String fileName = entry.getKey();
                    targetFullName = fullName;
                    if (fileName.startsWith("/")) {
                        targetTarPath = fileName;
                    } else {
                        targetTarPath = BACKUP_DIR + fileName;
                    }
                    break;
                }

                if (targetFullName == null) {

                    return;
                }

                if (!tryAcquireRestoreLock(targetFullName)) {
                    return; 
                }

                imageStatusCache.put(targetFullName, "restoring");
                restoringStartAt.put(targetFullName, System.currentTimeMillis());
                appendLog("【定时任务】自动恢复: " + targetFullName);

                final String restoreTarPath = targetTarPath;
                final String restoreFullName = targetFullName;

                try {

                    String tarFile = restoreTarPath.substring(restoreTarPath.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile)) {
                        appendLog("【定时任务】检测到服务器上已有 docker load 进程处理 " + tarFile + "，跳过重复启动（等现有进程完成）");

                        return;
                    }

                    String restoreCommand = "docker load -i " + restoreTarPath + " 2>&1";
                    String restoreOutput = sshService.executeLongRunningCommand(restoreCommand, true);

                    if (restoreOutput != null && (restoreOutput.contains("Loaded image") || restoreOutput.contains("Loaded:"))) {
                        imageStatusCache.put(restoreFullName, "normal");
                        restoringStartAt.remove(restoreFullName);
                        failedRestoreCount.remove(restoreFullName);
                        appendLog("【定时任务】自动恢复成功: " + restoreFullName);
                    } else {
                        imageStatusCache.put(restoreFullName, "missing");
                        restoringStartAt.remove(restoreFullName);
                        failedRestoreCount.merge(restoreFullName, 1, Integer::sum);
                        int fc = failedRestoreCount.getOrDefault(restoreFullName, 0);
                        if (fc >= MAX_AUTO_RESTORE_FAILURES) {
                            imageStatusCache.put(restoreFullName, "restore_failed");
                            appendLog("【定时任务】恢复失败" + fc + "次: " + restoreFullName + "，后续将不再自动恢复，请手动操作");
                        } else {
                            appendLog("【定时任务】自动恢复失败: " + restoreFullName + ", 输出: " + restoreOutput);
                        }
                    }
                } finally {
                    releaseRestoreLock(restoreFullName);
                }
            } finally {
                releaseGlobalRestoreLock();
            }
        } catch (Exception e) {
            logger.error("【定时任务】自动恢复异常", e);
            appendLog("【定时任务】自动恢复异常: " + e.getMessage());

            globalRestoreInProgress = false;
        }
    }
}