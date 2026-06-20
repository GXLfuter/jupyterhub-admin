package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.ImageSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/image-snapshot")
public class ImageSnapshotController {

    private static final Logger logger = LoggerFactory.getLogger(ImageSnapshotController.class);

    @Autowired
    private ImageSnapshotService imageSnapshotService;

    @GetMapping("/status")
    public Result getImageStatus() {
        try {
            List<Map<String, Object>> status = imageSnapshotService.getImageStatus();
            return Result.success(status);
        } catch (Exception e) {
            logger.error("获取镜像状态失败", e);
            return Result.error("获取镜像状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/restore")
    public Result restoreImage(@RequestBody Map<String, Object> body) {
        try {
            String fullName = (String) body.get("fullName");

            if (fullName == null || fullName.isEmpty()) {
                return Result.error("缺少必要参数: fullName");
            }

            logger.info("恢复镜像: {}", fullName);

            Map<String, Object> result = imageSnapshotService.restoreImage(fullName);
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("恢复镜像失败", e);
            return Result.error("恢复镜像失败: " + e.getMessage());
        }
    }

    @PostMapping("/create")
    public Result createSnapshot(@RequestBody Map<String, Object> body) {
        try {
            String repository = (String) body.get("repository");
            String tag = (String) body.get("tag");

            if (repository == null || repository.isEmpty()) {
                return Result.error("缺少必要参数: repository");
            }

            if (tag == null || tag.isEmpty()) {
                return Result.error("缺少必要参数: tag");
            }

            logger.info("创建镜像快照: {}:{}", repository, tag);

            Map<String, Object> result = imageSnapshotService.createSnapshot(repository, tag);
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("创建镜像快照失败", e);
            return Result.error("创建镜像快照失败: " + e.getMessage());
        }
    }

    @GetMapping("/logs")
    public Result getLogs() {
        try {
            List<String> logs = imageSnapshotService.getLogs();
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("logs", logs);
            response.put("count", logs.size());
            return Result.success(response);
        } catch (Exception e) {
            logger.error("获取日志失败", e);
            return Result.error("获取日志失败: " + e.getMessage());
        }
    }

    @PostMapping("/auto-restore")
    public Result autoRestoreMissingImages() {
        try {
            logger.info("开始自动恢复缺失镜像");
            Map<String, Object> result = imageSnapshotService.autoRestoreMissingImages();
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("自动恢复失败", e);
            return Result.error("自动恢复失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete-backup")
    public Result deleteBackup(@RequestBody Map<String, String> request) {
        try {
            String fullName = request.get("fullName");
            logger.info("删除备份: " + fullName);
            Map<String, Object> result = imageSnapshotService.deleteBackup(fullName);
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("删除备份失败", e);
            return Result.error("删除备份失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/clear-logs")
    public Result clearLogs() {
        try {
            logger.info("清空操作日志");
            boolean success = imageSnapshotService.clearLogs();
            if (success) {
                return Result.success(null);
            } else {
                return Result.error("清空日志失败");
            }
        } catch (Exception e) {
            logger.error("清空日志失败", e);
            return Result.error("清空日志失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete-image")
    public Result deleteImage(@RequestBody Map<String, String> request) {
        try {
            String fullName = request.get("fullName");
            logger.info("删除镜像: " + fullName);
            Map<String, Object> result = imageSnapshotService.deleteImage(fullName);
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("删除镜像失败", e);
            return Result.error("删除镜像失败: " + e.getMessage());
        }
    }

    @GetMapping("/docker-health")
    public Result getDockerHealth() {
        try {
            Map<String, Object> health = imageSnapshotService.getDockerHealth();
            return Result.success(health);
        } catch (Exception e) {
            logger.error("获取Docker状态失败", e);
            return Result.error("获取Docker状态失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/clean-dangling")
    public Result cleanDangling() {
        try {
            logger.info("清理 <none> 镜像");
            Map<String, Object> result = imageSnapshotService.cleanDanglingImages();
            if ((Boolean) result.get("success")) {
                return Result.success(result);
            } else {
                return Result.error((String) result.get("message"));
            }
        } catch (Exception e) {
            logger.error("清理悬挂镜像失败", e);
            return Result.error("清理悬挂镜像失败: " + e.getMessage());
        }
    }
}