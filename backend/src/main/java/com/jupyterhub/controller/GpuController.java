package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.GpuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/gpu")
public class GpuController {

    private static final Logger logger = LoggerFactory.getLogger(GpuController.class);

    @Autowired
    private GpuService gpuService;

    @GetMapping("/status")
    public Result getGpuStatus() {
        try {
            Map<String, Object> status = gpuService.getGpuStatus();
            return Result.success(status);
        } catch (Exception e) {
            logger.error("获取GPU状态失败", e);
            return Result.error("获取GPU状态失败: " + e.getMessage());
        }
    }

    @GetMapping("/config")
    public Result getGpuConfig() {
        try {
            int config = gpuService.getGpuConfig();
            Map<String, Object> result = new HashMap<>();
            result.put("value", config);
            return Result.success(result);
        } catch (Exception e) {
            logger.error("获取GPU配置失败", e);
            return Result.error("获取GPU配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/config")
    public Result setGpuConfig(@RequestBody Map<String, Object> body) {
        try {
            Object valueObj = body.get("value");
            if (valueObj == null) {
                return Result.error("缺少必要参数: value");
            }

            int value;
            if (valueObj instanceof Number) {
                value = ((Number) valueObj).intValue();
            } else {
                try {
                    value = Integer.parseInt(valueObj.toString());
                } catch (NumberFormatException e) {
                    return Result.error("无效的配置值，必须是0-3之间的整数");
                }
            }

            if (value < 0 || value > 3) {
                return Result.error("无效的配置值，必须是0-3之间的整数");
            }

            logger.info("开始更新GPU配置为: {}", value);

            boolean updateSuccess = gpuService.setGpuConfig(value);
            if (!updateSuccess) {
                return Result.error("更新配置文件失败");
            }

            boolean restartSuccess = gpuService.restartJupyterHub();
            if (!restartSuccess) {
                return Result.error("配置已更新，但重启服务失败，请手动重启");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("value", value);
            result.put("message", "配置已更新并重启服务");
            return Result.success(result);
        } catch (Exception e) {
            logger.error("设置GPU配置失败", e);
            return Result.error("设置GPU配置失败: " + e.getMessage());
        }
    }
}
