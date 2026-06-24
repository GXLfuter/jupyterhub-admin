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

    // ==================== Docker 健康状态相关 ====================
    // Docker刚刚重启的冷却期：30秒内不自动恢复任何镜像
    private static final long DOCKER_RECOVERY_COOLDOWN_MS = 30000L;
    // Docker健康检查间隔：避免每18秒都去ssh查一遍
    private static final long DOCKER_HEALTH_CHECK_INTERVAL_MS = 10000L;
    // 单个镜像自动恢复失败上限：超过3次不再自动恢复，需手动
    private static final int MAX_AUTO_RESTORE_FAILURES = 3;
    // "restoring"状态超时：10分钟还没完成算失败
    private static final long RESTORING_STATE_TIMEOUT_MS = 10 * 60 * 1000L;
    // getImageStatus 结果缓存时间：3秒内重复请求直接返回缓存，避免前端频繁刷新压垮 SSH
    private static final long STATUS_CACHE_TTL_MS = 3000L;

    private volatile long dockerLastRestartAt = 0L;     // Docker最近一次重启时间
    private volatile long dockerLastHealthCheckAt = 0L;  // Docker最近一次健康检查时间
    private volatile String dockerLastHealthState = "unknown"; // running|down|recovering

    // getImageStatus 结果缓存
    private volatile List<Map<String, Object>> statusCache = null;
    private volatile long statusCacheAt = 0L;

    // 清除所有缓存（操作后调用，确保下次请求获取最新数据）
    private void invalidateAllCaches() {
        dockerLastHealthCheckAt = 0L;
        statusCache = null;
        statusCacheAt = 0L;
    }

    // ==================== 原有配置 ====================
    // 关键镜像（系统自动恢复）及其固定备份文件名
    private static final Map<String, String> CRITICAL_IMAGES = new LinkedHashMap<>();
    static {
        CRITICAL_IMAGES.put("jupyter/base-notebook:latest", "jupyter-base-notebook.tar");
        CRITICAL_IMAGES.put("jupyter-cpu-zh:v18", "jupyter-cpu-zh-v18.tar");
        CRITICAL_IMAGES.put("jupyter-gpu-zh:v18", "jupyter-gpu-zh-v18.tar");
    }

    // 镜像状态缓存：fullName -> status (normal/missing/restoring/processing/restore_failed)
    private final ConcurrentHashMap<String, String> imageStatusCache = new ConcurrentHashMap<>();
    // restoring状态的起始时间：fullName -> timestamp，用于超时清理
    private final ConcurrentHashMap<String, Long> restoringStartAt = new ConcurrentHashMap<>();
    // 存储所有 tar 备份文件的元信息: filename -> [original_image_name, repo, tag]
    private final ConcurrentHashMap<String, String[]> backupFileMapping = new ConcurrentHashMap<>();
    private long lastBackupScanTime = 0;

    // 全局恢复锁：同一时间只有一个线程在恢复镜像（定时任务或手动）
    private final Object globalRestoreLock = new Object();
    private volatile boolean globalRestoreInProgress = false;

    // 顺序执行镜像恢复：避免大量并发 docker load 导致CPU占满
    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor();

    // 正在恢复中的镜像集合（防止重复执行）：fullName -> startTime
    private final ConcurrentHashMap<String, Long> restoringImages = new ConcurrentHashMap<>();

    // 自动恢复失败次数计数：fullName -> count
    private final ConcurrentHashMap<String, Integer> failedRestoreCount = new ConcurrentHashMap<>();

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
        // 先清理超时的 restoring 状态
        cleanupStuckRestoringStates();

        // ========== 缓存检查：3秒内重复请求直接返回缓存 ==========
        long now = System.currentTimeMillis();
        if (statusCache != null && now - statusCacheAt < STATUS_CACHE_TTL_MS) {
            return statusCache;
        }

        // 注意：不再强制刷新 Docker 健康状态，让 10 秒缓存生效
        // 只有定时任务或手动操作后才强制刷新

        scanBackupFiles();
        List<Map<String, Object>> images = new ArrayList<>();

        // ============== Docker 状态检查 ==============
        boolean dockerOK = isDockerRunning();
        boolean inCooldown = isInDockerCooldown();

        // ========== 一次性获取所有备份文件的元信息（只执行1次SSH命令）==========
        Map<String, String[]> backupInfoMap = getAllBackupFileInfo();

        // 1. 扫描所有 docker 镜像（Docker正常时才查）
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
                        // 使用缓存的备份文件信息，不再单独执行 SSH 命令
                        boolean hasBackup = backupFile != null && backupInfoMap.containsKey(backupFile);
                        image.put("hasBackup", hasBackup);
                        image.put("backupPath", hasBackup ? BACKUP_DIR + backupFile : "");
                        String[] bin = backupInfoMap.get(backupFile);
                        image.put("snapshotTime", hasBackup && bin != null ? bin[1] : "");

                        // 正在恢复 / 正在备份的优先显示对应状态
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

        // 2. 检查备份目录中存在但 docker 中缺失的镜像
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
            // 使用缓存的备份信息
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

        // 排序：已备份的排前面
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

        // 更新结果缓存
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
            return output.trim();
        }
        return "";
    }

    // 一次性获取所有备份文件的元信息（文件名 -> [大小, 修改时间]）
    // 只执行一次 SSH 命令，替代每个文件单独 stat/ls
    private Map<String, String[]> getAllBackupFileInfo() {
        Map<String, String[]> result = new HashMap<>();
        String command = "ls -l --time-style=long-iso " + BACKUP_DIR + "*.tar 2>/dev/null";
        String output = sshService.executeShortCommand(command, true);
        if (output == null || output.isEmpty()) {
            return result;
        }
        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("total")) continue;
            // ls -l 格式: -rw-r--r-- 1 root root  12345 2025-01-20 15:30 filename.tar
            String[] parts = line.split("\\s+");
            if (parts.length >= 7) {
                String fileName = parts[parts.length - 1];
                String size = parts[4];
                String date = parts[5] + " " + parts[6];
                // 把大小转成人类可读的（可选）
                String sizeReadable = formatSize(Long.parseLong(size));
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

    /**
     * 【核心防并发】检测服务器上是否已经有 docker load 进程在处理指定的 tar 文件
     * 问题根源：docker load 加载大镜像（vllm 24GB）需要 5-10 分钟
     *   → SSH连接可能超时/中断 → Java代码认为失败 → 释放锁 → 下一个周期又启动新的 docker load
     *   → 服务器上累计 12+ 个 docker load 进程 → CPU 42% 占满
     * 解决：每次启动 docker load 前，先用 pgrep 检测是否已有进程在跑该 tar 文件
     *
     * @param tarFileName 只取文件名部分（例如 vllm-vllm-openai-v0.21.0.tar），不使用完整路径
     * @return true = 已有该 tar 文件的 docker load 进程在运行
     */
    private boolean isDockerLoadAlreadyRunning(String tarFileName) {
        try {
            // 在服务器端执行：查找包含 "docker load" 和 这个具体 tar 文件名的进程
            // 用 grep -v grep 排除 grep 自身，用 wc -l 统计数量
            String checkCmd = "ps aux | grep 'docker load' | grep '" + tarFileName + "' | grep -v grep | wc -l";
            String output = sshService.executeShortCommand(checkCmd, true);
            if (output != null) {
                String trimmed = output.trim();
                try {
                    int count = Integer.parseInt(trimmed);
                    return count > 0;
                } catch (NumberFormatException e) {
                    // 解析失败，保守处理：如果输出非空，认为有进程在跑
                    return !trimmed.isEmpty() && !trimmed.equals("0");
                }
            }
        } catch (Exception e) {
            // 检测失败，保守处理：认为有进程在跑（宁等不重复）
            logger.warn("检测 docker load 进程失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检查 Docker 是否真正运行中（有缓存，避免每18秒都去ssh查）
     * 只有距上次检查超过10秒才重新查
     *
     * 严格判断规则：
     * 1. docker info 输出中必须包含 "Server Version:"（docker daemon 正常才会输出这个字段）
     * 2. 同时输出中不能包含 "Cannot connect"（这是 docker daemon 挂掉的明确标志）
     * 注：不能简单判断是否包含 "Docker"，因为错误信息中也会出现这个词
     */
    private boolean isDockerRunning() {
        long now = System.currentTimeMillis();
        if (now - dockerLastHealthCheckAt < DOCKER_HEALTH_CHECK_INTERVAL_MS) {
            // 使用缓存值
            return "running".equals(dockerLastHealthState);
        }
        // 真正去检查
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

    /**
     * 检查 Docker 是否处于重启冷却期内
     * @return true=冷却期，不应自动恢复任何镜像
     */
    private boolean isInDockerCooldown() {
        if (dockerLastRestartAt == 0L) return false;
        return (System.currentTimeMillis() - dockerLastRestartAt) < DOCKER_RECOVERY_COOLDOWN_MS;
    }

    /**
     * 给前端返回的 Docker 健康状态。
     * 每次调用都会强制重新检查（不使用 10 秒缓存），保证前端状态指示器准确。
     */
    public Map<String, Object> getDockerHealth() {
        Map<String, Object> result = new HashMap<>();
        // 强制刷新 Docker 健康状态（忽略缓存）
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

    /**
     * 确保 Docker 正在运行。如果 Docker 挂掉会尝试重启，并设置冷却期。
     */
    private boolean ensureDockerRunning() {
        if (isDockerRunning()) {
            return true;
        }

        // Docker 确实挂掉了，尝试重启
        try {
            appendLog("检测到 Docker 未运行，尝试重启 Docker 服务...");
            sshService.executeShortCommand("systemctl start docker.service 2>&1", true);
            Thread.sleep(5000);

            invalidateAllCaches(); // Docker重启后清除所有缓存
            if (isDockerRunning()) {
                dockerLastRestartAt = System.currentTimeMillis();
                appendLog("Docker 重启成功，进入 " + (DOCKER_RECOVERY_COOLDOWN_MS / 1000) + " 秒冷却期");
                return true;
            }

            // 备选方案
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

    /**
     * 检查镜像是否正在恢复中
     */
    private boolean isRestoring(String fullName) {
        Long time = restoringImages.get(fullName);
        if (time == null) return false;
        // 超过10分钟未完成，认为已失效（避免永久卡住）
        if (System.currentTimeMillis() - time > RESTORING_STATE_TIMEOUT_MS) {
            restoringImages.remove(fullName, time);
            imageStatusCache.remove(fullName, "restoring");
            restoringStartAt.remove(fullName);
            return false;
        }
        return true;
    }

    /**
     * 清理超时的 restoring 状态（防止永久"恢复中"）
     */
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

        // 强制刷新 Docker 健康状态（忽略 10 秒缓存）
        dockerLastHealthCheckAt = 0L;

        // 检查是否正在恢复中
        if (isRestoring(fullName)) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "镜像 " + fullName + " 正在恢复中，请稍候");
            return result;
        }

        // 全局锁：同一时间只有一个线程可以恢复镜像（避免并发执行 docker load）
        if (!tryAcquireGlobalRestoreLock()) {
            result.put("success", true);
            result.put("restoring", true);
            result.put("message", "系统正在恢复其他镜像，" + fullName + " 稍后自动开始");
            return result;
        }

        try {
            // 查找备份文件
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

            // Docker 冷却期：刚重启过的话，手动恢复也提示用户等一下
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

            // 获取单镜像恢复锁
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

                // ==== 关键防并发：检测服务器上是否已有这个 tar 的 docker load 进程在跑 ====
                // 从完整路径中提取文件名（如 "vllm-vllm-openai-v0.21.0.tar"）
                String tarFile = backupPath.substring(backupPath.lastIndexOf('/') + 1);
                if (isDockerLoadAlreadyRunning(tarFile)) {
                    appendLog("检测到服务器上已有 docker load 进程处理 " + tarFile + "，等待其完成");
                    // 关键修复：等待现有进程结束，然后验证镜像是否真的被加载了
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
                    // 进程结束了，验证结果
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
                        // 继续执行下面的 docker load
                    }
                }

                String command = "docker load -i " + backupPath;
                String output = sshService.executeLongRunningCommand(command, true);

                if (output != null && (output.contains("Loaded image") || output.contains("Loaded:"))) {
                    imageStatusCache.put(fullName, "normal");
                    restoringStartAt.remove(fullName);
                    failedRestoreCount.remove(fullName); // 成功后清除失败计数
                    appendLog("镜像恢复成功: " + fullName);
                    result.put("success", true);
                    result.put("message", "镜像恢复成功");
                } else {
                    imageStatusCache.put(fullName, "missing");
                    restoringStartAt.remove(fullName);
                    // 记录失败次数
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

    /**
     * 尝试获取全局恢复锁（同一时间只有一个恢复线程执行）
     */
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

    /**
     * 手动触发：恢复所有缺失镜像。
     *
     * 注意：此方法被 Controller 调用，需要严格控制资源使用：
     * 1. 先检查 Docker 是否运行；未运行则尝试启动并进入冷却期
     * 2. 检查冷却期：Docker 刚重启的 30 秒内不恢复任何镜像
     * 3. 获取全局恢复锁：确保同一时间只有一个恢复线程
     * 4. 顺序恢复，逐个 docker load，不并发
     * 5. 连续失败 3 次以上的镜像自动跳过（标记为 restore_failed）
     */
    public Map<String, Object> autoRestoreMissingImages() {
        Map<String, Object> result = new HashMap<>();
        List<String> restoredImages = new ArrayList<>();
        List<String> failedImages = new ArrayList<>();
        List<String> skippedImages = new ArrayList<>();

        // ========== 1. 检查冷却期（Docker 刚重启过就直接返回）==========
        if (isInDockerCooldown()) {
            long remaining = (DOCKER_RECOVERY_COOLDOWN_MS - (System.currentTimeMillis() - dockerLastRestartAt)) / 1000;
            result.put("success", false);
            result.put("message", "Docker 刚刚重启，等待 " + remaining + " 秒后再试。请耐心等待 Docker 完全就绪。");
            return result;
        }

        // ========== 2. 检查 Docker 是否运行；若未运行尝试启动 ==========
        if (!isDockerRunning()) {
            if (!ensureDockerRunning()) {
                result.put("success", false);
                result.put("message", "Docker 服务未运行，已尝试重启但失败。请手动启动 Docker 后再试。");
                return result;
            }
            // Docker 重启成功，进入冷却期，本次不做恢复
            long remaining = DOCKER_RECOVERY_COOLDOWN_MS / 1000;
            result.put("success", true);
            result.put("message", "Docker 已成功启动。为保证稳定，等待 " + remaining + " 秒冷却期后再恢复镜像。");
            return result;
        }

        // ========== 3. 获取全局恢复锁（与定时任务共享同一把锁）==========
        if (!tryAcquireGlobalRestoreLock()) {
            result.put("success", true);
            result.put("message", "已有恢复任务正在进行中，请稍候...");
            return result;
        }

        try {
            // ========== 4. 扫描备份 & 查询 Docker 中现有镜像 ==========
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

            // ========== 5. 顺序恢复：一个一个 docker load（不并发，不同时恢复多个）==========
            for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
                String[] info = entry.getValue();
                if (info == null) continue;

                String fullName = info[0];

                // 关键修复：如果镜像已在 Docker 中但 Java 端仍标记为 restoring，清理状态
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

                // 正在恢复中的：检查是真的有进程在跑还是之前的进程异常结束了
                if (isRestoring(fullName)) {
                    String fileName2 = entry.getKey();
                    String tarPath2 = fileName2.startsWith("/") ? fileName2 : (BACKUP_DIR + fileName2);
                    String tarFile2 = tarPath2.substring(tarPath2.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile2)) {
                        skippedImages.add(fullName);
                        continue;
                    }
                    // 进程不在了，清理状态，允许重新恢复
                    imageStatusCache.remove(fullName, "restoring");
                    restoringStartAt.remove(fullName);
                    restoringImages.remove(fullName);
                }

                // 连续失败 3 次以上的不再自动恢复
                Integer fc = failedRestoreCount.get(fullName);
                if (fc != null && fc >= MAX_AUTO_RESTORE_FAILURES) {
                    imageStatusCache.put(fullName, "restore_failed");
                    skippedImages.add(fullName);
                    continue;
                }

                // 单镜像恢复锁
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

                    // ==== 关键防并发：检测服务器上是否已有这个 tar 的 docker load 进程 ====
                    String tarFile = backupPath.substring(backupPath.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile)) {
                        appendLog("【手动】检测到服务器上已有 docker load 进程处理 " + tarFile + "，等待其完成");
                        // 等待进程结束后验证结果
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
                        // 验证镜像是否被加载
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
                        // 没加载成功，继续执行 docker load
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

            // ========== 6. 构建结果 ==========
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

        // ======== 幂等性检查：如果已经在备份中，不重复提交 ========
        String currentStatus = imageStatusCache.get(fullName);
        if ("processing".equals(currentStatus)) {
            result.put("success", false);
            result.put("message", "该镜像正在备份中，请勿重复点击");
            result.put("fullName", fullName);
            return result;
        }

        // ======== 幂等性检查2：如果 tar 文件已存在且有内容，说明已备份 ========
        try {
            String checkCmd = "stat -c %s " + backupPath + " 2>/dev/null";
            String checkOutput = sshService.executeShortCommand(checkCmd, true);
            if (checkOutput != null && !checkOutput.trim().isEmpty()) {
                try {
                    long size = Long.parseLong(checkOutput.trim());
                    if (size > 1024 * 1024) { // 大于1MB认为已有有效备份
                        result.put("success", false);
                        result.put("message", "该镜像已有备份，如需重新备份请先删除旧备份");
                        result.put("fullName", fullName);
                        return result;
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            // 检查出错不影响后续逻辑
        }

        imageStatusCache.put(fullName, "processing");
        appendLog("开始创建快照: " + fullName);

        snapshotExecutor.submit(() -> {
            String command = "docker save -o " + backupPath + " " + fullName;
            String output = sshService.executeLongRunningCommand(command, true);

            // docker save 成功时什么都不输出（不像 docker load 输出 "Loaded image"）
            // 所以不能检查 output.contains("Saved")
            // 正确判断：1) 输出不含真正的 Docker 错误
            //          2) tar 文件确实被创建且大小 > 0
            boolean hasRealError = output != null && !output.isEmpty()
                    && (output.toLowerCase().contains("error")
                    || output.toLowerCase().contains("no such image")
                    || output.toLowerCase().contains("permission denied")
                    || output.toLowerCase().contains("cannot connect")
                    || output.toLowerCase().contains("failed"));

            boolean tarExists = false;
            if (!hasRealError) {
                // 用短命令检查 tar 文件是否存在且有内容
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
                // 清理失败的空文件
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

        // docker image prune -f 清理所有未使用的镜像
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

        // 第一步：尝试强制删除指定镜像
        String command = "docker rmi -f " + fullName + " 2>&1";
        String output = sshService.executeShortCommand(command, true);

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
            String stopOutput = sshService.executeShortCommand(stopCmd, true);
            appendLog("停止容器输出: " + stopOutput);

            // 再次强制删除
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

    /**
     * 定时任务：每 18 秒自动检测并恢复缺失的备份镜像
     * 关键改动：
     *   1) Docker 刚重启的30秒冷却期内不做任何恢复（等Docker就绪）
     *   2) 同一时间只恢复 1 个镜像（顺序执行，避免CPU爆掉）
     *   3) 使用已有的 backupFileMapping，不再每次解析 tar 文件
     *   4) 失败超过3次的镜像不再自动恢复
     *   5) 有其他恢复任务在进行时，跳过本次检查
     */
    @Scheduled(fixedRate = 18000)
    public void scheduledAutoRestore() {
        try {
            // ==== 0. 强制刷新 Docker 健康状态（忽略 10 秒缓存）====
            // 确保我们拿到最新的 Docker 运行状态，而不是缓存
            dockerLastHealthCheckAt = 0L;

            // ==== 1. 清理超时的 restoring 状态 ====
            cleanupStuckRestoringStates();

            // ==== 2. 检查 Docker 是否处于冷却期 ====
            // 冷却期：Docker刚重启的30秒内，不要做任何恢复操作
            if (isInDockerCooldown()) {
                return;
            }

            // ==== 3. 确保 Docker 运行；如果需要重启，就标记冷却期并返回 ====
            if (!isDockerRunning()) {
                // 尝试重启一次
                if (ensureDockerRunning()) {
                    // 重启成功，进入冷却期，本次不做任何恢复
                    return;
                }
                // 重启失败，直接退出
                appendLog("【定时任务】Docker 无法启动，跳过本次检查");
                return;
            }

            // ==== 4. 全局恢复锁：有其他恢复正在进行，就跳过本次 ====
            if (!tryAcquireGlobalRestoreLock()) {
                return;
            }

            try {
                // ==== 5. 扫描备份文件（已有缓存不会重复执行） ====
                scanBackupFiles();

                // ==== 6. 获取当前 Docker 中已有的镜像 ====
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

                // ==== 7. 找出第一个需要恢复的镜像（只恢复1个，顺序执行）====
                String targetFullName = null;
                String targetTarPath = null;

                for (Map.Entry<String, String[]> entry : backupFileMapping.entrySet()) {
                    String[] info = entry.getValue();
                    if (info == null) continue;

                    String fullName = info[0];

                    // 关键修复：如果镜像已经在 Docker 中了（可能是服务器上之前的 docker load 进程完成的）
                    // 但 Java 端还标记为 restoring，需要清理 restoring 状态
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

                    // 正在恢复中的：检查是否已有 docker load 进程在跑
                    // 如果有进程在跑，跳过；如果没有进程但还标记为 restoring，说明之前的进程异常结束了，允许重新恢复
                    if (isRestoring(fullName)) {
                        String fileName2 = entry.getKey();
                        String tarPath2 = fileName2.startsWith("/") ? fileName2 : (BACKUP_DIR + fileName2);
                        String tarFile2 = tarPath2.substring(tarPath2.lastIndexOf('/') + 1);
                        if (isDockerLoadAlreadyRunning(tarFile2)) {
                            // 确实有进程在跑，等下一次
                            continue;
                        }
                        // 没有进程在跑了，之前的进程可能异常结束，清理状态让它可以被重新恢复
                        imageStatusCache.remove(fullName, "restoring");
                        restoringStartAt.remove(fullName);
                        restoringImages.remove(fullName);
                        appendLog("【定时任务】镜像 " + fullName + " 的恢复进程已结束但未成功，清理状态可重新恢复");
                    }

                    // 失败超过3次的，不再自动恢复
                    Integer fc = failedRestoreCount.get(fullName);
                    if (fc != null && fc >= MAX_AUTO_RESTORE_FAILURES) continue;

                    // 找到第一个可以恢复的
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
                    // 没有需要恢复的，正常退出
                    return;
                }

                // ==== 8. 顺序执行恢复（同步，不提交到线程池）====
                // 因为已经拿到全局恢复锁了，这里直接在当前线程执行 docker load
                // 这样保证同一时刻只有一个 docker load 在跑，不会爆CPU
                if (!tryAcquireRestoreLock(targetFullName)) {
                    return; // 理论上不会走到这里
                }

                imageStatusCache.put(targetFullName, "restoring");
                restoringStartAt.put(targetFullName, System.currentTimeMillis());
                appendLog("【定时任务】自动恢复: " + targetFullName);

                final String restoreTarPath = targetTarPath;
                final String restoreFullName = targetFullName;

                try {
                    // ==== 关键防并发：检测服务器上是否已有这个 tar 的 docker load 进程 ====
                    // 问题：docker load 运行 5 分钟，SSH 可能中断 → Java 认为失败 → 下一周期又启动新的 docker load
                    // 导致 12+ 进程并发，CPU 42% 占满
                    String tarFile = restoreTarPath.substring(restoreTarPath.lastIndexOf('/') + 1);
                    if (isDockerLoadAlreadyRunning(tarFile)) {
                        appendLog("【定时任务】检测到服务器上已有 docker load 进程处理 " + tarFile + "，跳过重复启动（等现有进程完成）");
                        // 保持 restoring 状态，等后台进程完成
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
            // 发生异常也要释放全局锁（虽然try-finally已经处理了，但以防万一）
            globalRestoreInProgress = false;
        }
    }
}