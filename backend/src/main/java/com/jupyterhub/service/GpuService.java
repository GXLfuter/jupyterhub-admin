/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GpuService {

    private static final Logger logger = LoggerFactory.getLogger(GpuService.class);

    @Autowired
    private SshService sshService;

    private static final String CONFIG_FILE = "/opt/jupyterhub_prod/config/jupyterhub_config.py";

    private static final Pattern CONFIG_PATTERN = Pattern.compile(
        "^\\s*student1234_use_gpu_settings\\s*=\\s*([0-9])\\s*(?:#.*)?$"
    );

    public Map<String, Object> getGpuStatus() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> gpus = new ArrayList<>();

        String nvidiaSmiOutput = sshService.executeCommand(
            "nvidia-smi --query-gpu=index,name,memory.total,memory.used,memory.free," +
            "utilization.gpu,temperature.gpu,driver_version,power.draw,power.limit " +
            "--format=csv,noheader,nounits"
        );

        if (nvidiaSmiOutput == null || nvidiaSmiOutput.isEmpty() || nvidiaSmiOutput.startsWith("ERROR")) {
            result.put("available", false);
            result.put("message", "NVIDIA显卡不可用或驱动未安装");
            result.put("gpus", gpus);
            return result;
        }

        String[] lines = nvidiaSmiOutput.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length >= 10) {
                Map<String, String> gpu = new HashMap<>();
                gpu.put("index", parts[0].trim());
                gpu.put("name", parts[1].trim());
                gpu.put("memoryTotal", parts[2].trim() + " MB");
                gpu.put("memoryUsed", parts[3].trim() + " MB");
                gpu.put("memoryFree", parts[4].trim() + " MB");
                gpu.put("utilization", parts[5].trim() + "%");
                gpu.put("temperature", parts[6].trim() + "°C");
                gpu.put("driverVersion", parts[7].trim());
                gpu.put("powerDraw", parts[8].trim() + " W");
                gpu.put("powerLimit", parts[9].trim() + " W");

                try {
                    int total = Integer.parseInt(parts[2].trim());
                    int used = Integer.parseInt(parts[3].trim());
                    int percent = total > 0 ? (int) ((used * 100.0) / total) : 0;
                    gpu.put("memoryPercent", String.valueOf(percent));
                } catch (NumberFormatException e) {
                    gpu.put("memoryPercent", "0");
                }

                try {
                    double pDraw = Double.parseDouble(parts[8].trim());
                    double pLimit = Double.parseDouble(parts[9].trim());
                    int powerPercent = pLimit > 0 ? (int) ((pDraw * 100.0) / pLimit) : 0;
                    gpu.put("powerPercent", String.valueOf(powerPercent));
                } catch (NumberFormatException e) {
                    gpu.put("powerPercent", "0");
                }

                gpus.add(gpu);
            }
        }

        result.put("available", true);
        result.put("count", gpus.size());
        result.put("gpus", gpus);
        return result;
    }

    public int getGpuConfig() {
        String content = sshService.executeCommand("cat " + CONFIG_FILE, true);
        if (content == null || content.isEmpty()) {
            logger.warn("读取配置文件失败或内容为空");
            return -1;
        }

        String[] lines = content.split("\n");
        for (String line : lines) {
            Matcher matcher = CONFIG_PATTERN.matcher(line);
            if (matcher.matches()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    logger.warn("解析GPU配置失败: {}", line);
                }
            }
        }

        logger.warn("未找到GPU配置行: student1234_use_gpu_settings");
        return -1;
    }

    public boolean setGpuConfig(int value) {
        if (value < 0 || value > 3) {
            return false;
        }

        String sedCommand = "sed -i \"s/^student1234_use_gpu_settings\\s*=\\s*[0-9]/student1234_use_gpu_settings = " + value + "/\" " + CONFIG_FILE;
        String result = sshService.executeCommand(sedCommand);

        if (result != null && !result.startsWith("ERROR")) {
            logger.info("GPU配置已更新为: {}", value);
            return true;
        }

        logger.error("更新GPU配置失败: {}", result);
        return false;
    }

    public boolean restartJupyterHub() {
        String result = sshService.executeCommand("systemctl restart jupyterhub.service");
        if (result != null && !result.startsWith("ERROR")) {
            logger.info("JupyterHub服务已重启");
            return true;
        }
        logger.error("重启JupyterHub服务失败: {}", result);
        return false;
    }
}
