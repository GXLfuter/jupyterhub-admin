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
    private volatile boolean dockerRestarting = false;

    // 关键镜像（系统自动恢复）及其固定备份文件名
    private static final Map<String, String> CRITICAL_IMAGES = new LinkedHashMap<>();
    static {
        CRITICAL_IMAGES.put("jupyter/base-notebook:latest", "jupyter-base-notebook.tar");
        CRITICAL_IMAGES.put("jupyter-cpu-zh:v18", "jupyter-cpu-zh-v18.tar");
        CRITICAL_IMAGES.put("jupyter-gpu-zh:v18", "jupyter-gpu-zh-v18.tar");
    }

    private final ConcurrentHashMap<String, String> imageStatusCache = new ConcurrentHashMap<>();
    // 存储所有 tar 备份文件的元信息: filename -> [original_image_name, repo, tag]
    private final ConcurrentHashMap<String, String[]> backupFileMapping = new ConcurrentHashMap<>();
    private long lastBackupScanTime = 0;

    private final ExecutorService snapshotExecutor = Executors.newFixedThreadPool(2);

    // 正在恢复中的镜像集合（防止重复执行）
    private final ConcurrentHashMap<String, Long> restoringImages = new ConcurrentHashMap<>();

    /**
     * 扫描备份目录，建立 tar 文件与原始镜像名的映射关系
     * 通过 `docker load --input xxx.tar` 的输出获取原始镜像名
     */
    private void scanBackupFiles() {
        long now = System.currentTimeMillis();
        // 5秒内不重复扫描
        if (now - lastBackupScanTime < 5000 && !backupFileMapping.isEmpty()) {
            return;
        }
        lastBackupScanTime = now;

        String command = "ls " + BACKUP_DIR + "*.tar 2>/dev/null";
        String output = sshService.executeCommand(command, true);
        if (output == null || output.isEmpty()) {
            return;
        }

        Map<String, String[]> newMapping = new ConcurrentHashMap<>();
        String[] files = output.split("\n");
        for (String file : files) {
            file = file.trim();
            if (!file.endsWith(".tar")) continue;

            String fileName = file.substring(file.lastIndexOf("/") + 1);
            // 通过 docker load 获取镜像名（不实际执行加载，只获取信息）
            String[] info = getImageInfoFromTar(file);
            newMapping.put(fileName, info);
        }
        backupFileMapping.clear();
        backupFileMapping.putAll(newMapping);
    }

    /**
     * 从 tar 文件获取原始镜像名
     * 解析 tar 中的 manifest.json 或 repositories 文件
     */
    private String[] getImageInfoFromTar(String tarPath) {
        // 方法1：解析 manifest.json
        String command = "tar -xOf " + tarPath + " manifest.json 2>/dev/null | head -c 2000";
        String output = sshService.executeCommand(command, true);

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

        // 方法2：从文件名查找
        String fileName = tarPath.substring(tarPath.lastIndexOf("/") + 1);

        // 方法3：从文件名反推（作为最后 fallback）
        // 文件名格式: hello-world-latest.tar -> hello-world:latest
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

    /**
     * 通过 tar 文件名找到对应的镜像信息
     */
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

    /**
     * 通过 fullName 找到对应的 tar 文件名
     */
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
        scanBackupFiles();
        List<Map<String, Object>> images = new ArrayList<>();

        // 1. 扫描所有 docker 镜像
        String output = sshService.executeCommand("docker images --format '{{.Repository}}|{{.Tag}}|{{.ID}}|{{.Size}}'", true);
        Set<String> existingImages = new HashSet<>();

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

                    // 查找对应的备份文件
                    String backupFile = findBackupFileByImageName(fullName);
                    if (backupFile == null && isCritical) {
                        backupFile = CRITICAL_IMAGES.get(fullName);
                    }
                    boolean hasBackup = backupFile != null && checkBackupExists(backupFile);
                    image.put("hasBackup", hasBackup);
                    image.put("backupPath", hasBackup ? BACKUP_DIR + backupFile : "");

                    image.put("snapshotTime", hasBackup ? getSnapshotTime(backupFile) : "");

                    String status = imageStatusCache.getOrDefault(fullName, "normal");
                    image.put("status", status);

                    images.add(image);
                }
            }
        }

        // 2. 检查备份目录中存在但 docker 中缺失的镜像（需要恢复）
        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String fileName = entry.getKey();
            String[] info = entry.getValue();
            if (info == null) continue;

            String fullName = info[0];
            if (existingImages.contains(fullName)) continue;

            // 这个备份存在但 docker 中没有，需要恢复
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
            image.put("snapshotTime", getSnapshotTime(fileName));

            // 如果正在恢复中，保留restoring状态，否则设置为missing
            String cachedStatus = imageStatusCache.get(fullName);
            if ("restoring".equals(cachedStatus)) {
                image.put("status", "restoring");
            } else {
                imageStatusCache.put(fullName, "missing");
                image.put("status", "missing");
            }

            images.add(image);
        }

        // 排序：已备份的排前面，未备份的排后面
        images.sort((a, b) -> {
            boolean aHasBackup = (Boolean) a.get("hasBackup");
            boolean bHasBackup = (Boolean) b.get("hasBackup");

            // 已备份的优先排前面
            if (aHasBackup && !bHasBackup) return -1;
            if (!aHasBackup && bHasBackup) return 1;

            // 关键镜像优先
            boolean aCritical = (Boolean) a.get("isCritical");
            boolean bCritical = (Boolean) b.get("isCritical");
            if (aCritical && !bCritical) return -1;
            if (!aCritical && bCritical) return 1;

            String aName = (String) a.get("fullName");
            String bName = (String) b.get("fullName");
            return aName.compareTo(bName);
        });

        return images;
    }

    private boolean checkBackupExists(String backupFile) {
        String command = "ls -la " + BACKUP_DIR + backupFile + " 2>/dev/null";
        String output = sshService.executeCommand(command, true);
        return output != null && !output.isEmpty() && !output.startsWith("ERROR");
    }

    private String getSnapshotTime(String backupFile) {
        String command = "stat -c %y " + BACKUP_DIR + backupFile + " 2>/dev/null | cut -d' ' -f1,2";
        String output = sshService.executeCommand(command, true);
        if (output != null && !output.isEmpty() && !output.startsWith("ERROR")) {
            return output.trim();
        }
        return "";
    }

    /**
     * 检查镜像是否正在恢复中，若是则返回false，否则设置为恢复中状态
     * @param fullName 镜像全名
     * @return true-可以执行恢复，false-正在恢复中
     */
    private boolean tryAcquireRestoreLock(String fullName) {
        Long existing = restoringImages.putIfAbsent(fullName, System.currentTimeMillis());
        if (existing != null) {
            // 检查是否超时（超过30分钟认为之前的恢复已失效）
            if (System.currentTimeMillis() - existing > 30 * 60 * 1000) {
                restoringImages.remove(fullName, existing);
                Long recheck = restoringImages.putIfAbsent(fullName, System.currentTimeMillis());
                return recheck == null;
            }
            return false;
        }
        return true;
    }

    /**
     * 释放镜像恢复锁
     */
    private void releaseRestoreLock(String fullName) {
        restoringImages.remove(fullName);
    }

    private boolean ensureDockerRunning() {
        String output = sshService.executeCommand("docker info 2>&1", true);
        if (output != null && (output.contains("Server Version") || output.contains("Docker"))) {
            return true;
        }

        if (dockerRestarting) {
            return false;
        }

        dockerRestarting = true;
        try {
            appendLog("检测到 Docker 未运行，尝试重启 Docker 服务...");

            sshService.executeCommand("systemctl start docker.service 2>&1");
            Thread.sleep(3000);

            output = sshService.executeCommand("docker info 2>&1", true);
            if (output != null && (output.contains("Server Version") || output.contains("Docker"))) {
                appendLog("Docker 服务重启成功");
                return true;
            }

            sshService.executeCommand("systemctl start docker 2>&1");
            Thread.sleep(3000);

            output = sshService.executeCommand("docker info 2>&1", true);
            if (output != null && (output.contains("Server Version") || output.contains("Docker"))) {
                appendLog("Docker 服务重启成功 (systemctl start docker)");
                return true;
            }

            appendLog("Docker 服务重启失败，无法恢复镜像");
            return false;
        } catch (Exception e) {
            appendLog("Docker 服务重启异常: " + e.getMessage());
            return false;
        } finally {
            dockerRestarting = false;
        }
    }

    /**
     * 检查镜像是否正在恢复中
     */
    private boolean isRestoring(String fullName) {
        Long time = restoringImages.get(fullName);
        if (time == null) return false;
        // 超过30分钟未完成，认为已失效
        if (System.currentTimeMillis() - time > 30 * 60 * 1000) {
            restoringImages.remove(fullName, time);
            return false;
        }
        return true;
    }

    public Map<String, Object> restoreImage(String fullName) {
        Map<String, Object> result = new HashMap<>();

        // 检查是否正在恢复中
        if (isRestoring(fullName)) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "镜像 " + fullName + " 正在恢复中，请稍候");
            return result;
        }

        // 查找备份文件
        String backupFile = findBackupFileByImageName(fullName);
        if (backupFile == null) {
            // 关键镜像使用固定名称
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

        if (!ensureDockerRunning()) {
            result.put("success", false);
            result.put("message", "Docker 服务未运行，已尝试重启但失败，请手动启动 Docker");
            return result;
        }

        // 获取恢复锁
        if (!tryAcquireRestoreLock(fullName)) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "镜像 " + fullName + " 正在恢复中，请稍候");
            return result;
        }

        try {
            imageStatusCache.put(fullName, "restoring");
            appendLog("开始恢复镜像: " + fullName);

            String command = "docker load -i " + backupPath;
            String output = sshService.executeCommand(command);

            if (output != null && output.contains("Loaded image")) {
                imageStatusCache.put(fullName, "normal");
                appendLog("镜像恢复成功: " + fullName);
                result.put("success", true);
                result.put("message", "镜像恢复成功");
            } else {
                imageStatusCache.put(fullName, "missing");
                appendLog("镜像恢复失败: " + fullName + ", 错误: " + (output != null ? output : "未知"));
                result.put("success", false);
                result.put("message", "镜像恢复失败: " + (output != null ? output : "未知错误"));
            }
        } finally {
            releaseRestoreLock(fullName);
        }

        return result;
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
        String output = sshService.executeCommand(command);

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

        scanBackupFiles();
        String output = sshService.executeCommand("docker images --format '{{.Repository}}|{{.Tag}}'", true);
        Set<String> existingImages = new HashSet<>();

        if (output != null && !output.isEmpty()) {
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty()) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 2) {
                        existingImages.add(parts[0].trim() + ":" + parts[1].trim());
                    }
                }
            }
        }

        boolean hasMissing = false;
        for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
            String[] info = entry.getValue();
            if (info == null) continue;

            String fullName = info[0];
            if (existingImages.contains(fullName)) continue;

            // 检查是否正在恢复中
            if (isRestoring(fullName)) {
                appendLog("镜像 " + fullName + " 正在恢复中，跳过");
                continue;
            }

            // 获取恢复锁
            if (!tryAcquireRestoreLock(fullName)) {
                appendLog("镜像 " + fullName + " 获取恢复锁失败，跳过");
                continue;
            }

            hasMissing = true;
            imageStatusCache.put(fullName, "restoring");
            appendLog("自动恢复镜像: " + fullName);

            String backupFile = entry.getKey();
            String backupPath = BACKUP_DIR + backupFile;
            String command = "docker load -i " + backupPath;

            try {
                String cmdOutput = sshService.executeCommand(command);

                if (cmdOutput != null && cmdOutput.contains("Loaded image")) {
                    imageStatusCache.put(fullName, "normal");
                    appendLog("自动恢复成功: " + fullName);
                    restoredImages.add(fullName);
                } else {
                    imageStatusCache.put(fullName, "missing");
                    appendLog("自动恢复失败: " + fullName + ", 错误: " + (cmdOutput != null ? cmdOutput : "未知"));
                    failedImages.add(fullName);
                }
            } finally {
                releaseRestoreLock(fullName);
            }
        }

        if (!hasMissing) {
            result.put("success", true);
            result.put("message", "没有需要恢复的缺失镜像");
            result.put("restoredCount", 0);
            result.put("restoredImages", restoredImages);
            result.put("failedImages", failedImages);
        } else {
            result.put("success", restoredImages.size() > 0);
            result.put("message", String.format("自动恢复完成: 成功 %d 个, 失败 %d 个", restoredImages.size(), failedImages.size()));
            result.put("restoredCount", restoredImages.size());
            result.put("restoredImages", restoredImages);
            result.put("failedImages", failedImages);
        }

        return result;
    }

    public Map<String, Object> createSnapshot(String repository, String tag) {
        Map<String, Object> result = new HashMap<>();

        String fullName = repository + ":" + tag;
        String backupFile = getBackupFileName(repository, tag);
        String backupPath = BACKUP_DIR + backupFile;

        imageStatusCache.put(fullName, "processing");
        appendLog("开始创建快照: " + fullName);

        snapshotExecutor.submit(() -> {
            String command = "docker save -o " + backupPath + " " + fullName;
            String output = sshService.executeCommand(command);

            boolean success = (output == null || output.isEmpty() || output.contains("Saved"));

            if (success) {
                imageStatusCache.put(fullName, "normal");
                String snapshotTime = getSnapshotTime(backupFile);
                appendLog("快照创建成功: " + fullName + ", 路径: " + backupPath);
                lastBackupScanTime = 0;
            } else {
                imageStatusCache.remove(fullName);
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
        String output = sshService.executeCommand("cat " + LOG_FILE, true);

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
        sshService.executeCommand(command, true);
        String output = sshService.executeCommand("cat " + LOG_FILE, true);
        return output == null || output.isEmpty();
    }

    public Map<String, Object> cleanDanglingImages() {
        Map<String, Object> result = new HashMap<>();

        appendLog("开始清理 <none> 悬挂镜像");

        // docker image prune -f 清理所有未使用的镜像
        String command = "docker image prune -f 2>&1";
        String output = sshService.executeCommand(command);

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

        // 第一步：尝试强制删除指定镜像
        String command = "docker rmi -f " + fullName + " 2>&1";
        String output = sshService.executeCommand(command);

        appendLog("删除镜像 " + fullName + " 命令输出: " + output);

        if (output == null || output.isEmpty() || output.contains("Untagged") || output.contains("Deleted")) {
            appendLog("删除镜像成功: " + fullName);
            imageStatusCache.remove(fullName);
            result.put("success", true);
            result.put("message", "删除成功");
        } else if (output.contains("image is being used") || output.contains("image has dependent children")) {
            // 镜像被使用，尝试停止相关容器后强制删除
            appendLog("镜像被使用，尝试停止容器后强制删除: " + fullName);
            String stopCmd = "docker ps -a -q --filter ancestor=" + fullName + " | xargs -r docker stop 2>&1";
            String stopOutput = sshService.executeCommand(stopCmd);
            appendLog("停止容器输出: " + stopOutput);

            // 再次强制删除
            String retryOutput = sshService.executeCommand(command);
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
        sshService.executeCommand(command, true);
    }

    /**
     * 定时任务：每 18 秒自动检测并恢复缺失的备份镜像
     * 不受页面切换影响，后台持续运行
     */
    @Scheduled(fixedRate = 18000)
    public void scheduledAutoRestore() {
        try {
            if (!ensureDockerRunning()) {
                appendLog("【定时任务】Docker 未运行，跳过自动恢复");
                return;
            }

            String command = "docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v '^<none>'";
            String output = sshService.executeCommand(command, true);

            Set<String> existingImages = new HashSet<>();
            if (output != null && !output.isEmpty()) {
                for (String line : output.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.contains(":")) {
                        existingImages.add(trimmed);
                    }
                }
            }
//            logger.info("【定时任务】当前存在的镜像数量: " + existingImages.size());

            // 遍历所有备份文件，检查是否缺失
            String backupListCmd = "ls " + BACKUP_DIR + "*.tar 2>/dev/null";
            String backupList = sshService.executeCommand(backupListCmd, true);

            if (backupList != null && !backupList.isEmpty()) {
                int restoredCount = 0;
                for (String line : backupList.split("\n")) {
                    String tarFile = line.trim();
                    if (tarFile.isEmpty() || !tarFile.endsWith(".tar")) continue;

                    String tarPath;
                    if (tarFile.startsWith("/")) {
                        tarPath = tarFile;
                    } else {
                        tarPath = BACKUP_DIR + tarFile;
                    }

                    String[] imageInfo = getImageInfoFromTar(tarPath);
                    
                    String fullName = null;
                    if (imageInfo != null && imageInfo.length > 0) {
                        fullName = imageInfo[0];
                    } else {
                        if (tarFile.endsWith(".tar")) {
                            String baseName = tarFile.substring(0, tarFile.length() - 4);
                            int lastDash = baseName.lastIndexOf("-");
                            if (lastDash > 0) {
                                String repo = baseName.substring(0, lastDash);
                                String tag = baseName.substring(lastDash + 1);
                                fullName = repo + ":" + tag;
                            }
                        }
                    }

                    if (fullName != null && !fullName.isEmpty()) {
                        if (!existingImages.contains(fullName)) {
                            // 检查是否正在恢复中
                            if (isRestoring(fullName)) {
                                continue;
                            }

                            // 获取恢复锁
                            if (!tryAcquireRestoreLock(fullName)) {
                                continue;
                            }

                            imageStatusCache.put(fullName, "restoring");
                            appendLog("【定时任务】检测到缺失镜像，自动恢复: " + fullName);

                            final String restoreFullName = fullName;
                            final String restoreTarPath = tarPath;
                            snapshotExecutor.submit(() -> {
                                try {
                                    String restoreCommand = "docker load -i " + restoreTarPath + " 2>&1";
                                    String restoreOutput = sshService.executeCommand(restoreCommand);

                                    if (restoreOutput != null && (restoreOutput.contains("Loaded image") || restoreOutput.contains("Loaded:"))) {
                                        imageStatusCache.remove(restoreFullName);
                                        appendLog("【定时任务】自动恢复成功: " + restoreFullName);
                                    } else {
                                        imageStatusCache.put(restoreFullName, "missing");
                                        appendLog("【定时任务】自动恢复失败: " + restoreFullName + ", 输出: " + restoreOutput);
                                    }
                                } finally {
                                    releaseRestoreLock(restoreFullName);
                                }
                            });

                            restoredCount++;
                        }
                    } else {
                        logger.warn("【定时任务】无法解析备份文件: " + tarFile);
                    }
                }
                if (restoredCount != 0) logger.info("【定时任务】成功恢复镜像数量: " + restoredCount);

            } else {
                logger.info("【定时任务】备份目录为空或无tar文件");
            }

//            logger.info("【定时任务】自动检测完成");
        } catch (Exception e) {
            logger.error("【定时任务】自动恢复异常", e);
            appendLog("【定时任务】自动恢复异常: " + e.getMessage());
        }
    }
}