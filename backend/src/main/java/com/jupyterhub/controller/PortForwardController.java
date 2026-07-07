/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.PortForwardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/port-forward")
public class PortForwardController {

    private static final Logger logger = LoggerFactory.getLogger(PortForwardController.class);

    @Autowired
    private PortForwardService portForwardService;

    @GetMapping("/status")
    public Result getForwardStatus() {
        try {
            List<Map<String, String>> status = portForwardService.getForwardStatus();
            return Result.success(status);
        } catch (Exception e) {
            logger.error("获取端口转发状态失败", e);
            return Result.error("获取端口转发状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/add")
    public Result addForward(@RequestBody Map<String, Object> body) {
        try {
            String student = (String) body.get("student");
            Object containerPortObj = body.get("containerPort");
            Object hostPortObj = body.get("hostPort");

            if (student == null || student.isEmpty()) {
                return Result.error("缺少必要参数: student");
            }

            int containerPort;
            int hostPort;

            try {
                if (containerPortObj instanceof Number) {
                    containerPort = ((Number) containerPortObj).intValue();
                } else {
                    containerPort = Integer.parseInt(containerPortObj.toString());
                }

                if (hostPortObj instanceof Number) {
                    hostPort = ((Number) hostPortObj).intValue();
                } else {
                    hostPort = Integer.parseInt(hostPortObj.toString());
                }
            } catch (Exception e) {
                return Result.error("端口号必须是数字");
            }

            if (containerPort < 1 || containerPort > 65535) {
                return Result.error("容器端口号必须在1-65535之间");
            }

            if (hostPort < 1 || hostPort > 65535) {
                return Result.error("宿主机端口号必须在1-65535之间");
            }

            logger.info("添加端口转发: {} -> {}:{}", hostPort, student, containerPort);

            String result = portForwardService.addForward(student, containerPort, hostPort);
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", result);
                response.put("student", student);
                response.put("containerPort", containerPort);
                response.put("hostPort", hostPort);
                return Result.success(response);
            } else {
                return Result.error("添加端口转发失败");
            }
        } catch (Exception e) {
            logger.error("添加端口转发失败", e);
            return Result.error("添加端口转发失败: " + e.getMessage());
        }
    }

    @PostMapping("/stop")
    public Result stopForward(@RequestBody Map<String, Object> body) {
        try {
            String student = (String) body.get("student");
            Object hostPortObj = body.get("hostPort");

            if (student == null || student.isEmpty()) {
                return Result.error("缺少必要参数: student");
            }

            int hostPort;
            try {
                if (hostPortObj instanceof Number) {
                    hostPort = ((Number) hostPortObj).intValue();
                } else {
                    hostPort = Integer.parseInt(hostPortObj.toString());
                }
            } catch (Exception e) {
                return Result.error("端口号必须是数字");
            }

            logger.info("停止端口转发进程: {}:{}", student, hostPort);

            String result = portForwardService.stopForward(student, hostPort);
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", result);
                response.put("student", student);
                response.put("hostPort", hostPort);
                return Result.success(response);
            } else {
                return Result.error("停止端口转发失败");
            }
        } catch (Exception e) {
            logger.error("停止端口转发失败", e);
            return Result.error("停止端口转发失败: " + e.getMessage());
        }
    }

    @PostMapping("/delete")
    public Result deleteForward(@RequestBody Map<String, Object> body) {
        try {
            String student = (String) body.get("student");
            Object hostPortObj = body.get("hostPort");

            if (student == null || student.isEmpty()) {
                return Result.error("缺少必要参数: student");
            }

            int hostPort;
            try {
                if (hostPortObj instanceof Number) {
                    hostPort = ((Number) hostPortObj).intValue();
                } else {
                    hostPort = Integer.parseInt(hostPortObj.toString());
                }
            } catch (Exception e) {
                return Result.error("端口号必须是数字");
            }

            logger.info("删除端口转发: {}:{}", student, hostPort);

            String result = portForwardService.deleteForward(student, hostPort);
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", result);
                response.put("student", student);
                response.put("hostPort", hostPort);
                return Result.success(response);
            } else {
                return Result.error("删除端口转发失败");
            }
        } catch (Exception e) {
            logger.error("删除端口转发失败", e);
            return Result.error("删除端口转发失败: " + e.getMessage());
        }
    }

    @PostMapping("/start-monitor")
    public Result startMonitor() {
        try {
            String result = portForwardService.startMonitor();
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", result);
                response.put("running", true);
                return Result.success(response);
            } else {
                return Result.error("启动监控失败");
            }
        } catch (Exception e) {
            logger.error("启动监控失败", e);
            return Result.error("启动监控失败: " + e.getMessage());
        }
    }

    @PostMapping("/stop-monitor")
    public Result stopMonitor() {
        try {
            String result = portForwardService.stopMonitor();
            if (result != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("message", result);
                response.put("running", false);
                return Result.success(response);
            } else {
                return Result.error("停止监控失败");
            }
        } catch (Exception e) {
            logger.error("停止监控失败", e);
            return Result.error("停止监控失败: " + e.getMessage());
        }
    }

    @GetMapping("/monitor-status")
    public Result getMonitorStatus() {
        try {
            boolean running = portForwardService.isMonitorRunning();
            Map<String, Object> response = new HashMap<>();
            response.put("running", running);
            return Result.success(response);
        } catch (Exception e) {
            logger.error("获取监控状态失败", e);
            return Result.error("获取监控状态失败: " + e.getMessage());
        }
    }

    @GetMapping("/logs")
    public Result getLogs() {
        try {
            List<String> logs = portForwardService.getLogs();
            Map<String, Object> response = new HashMap<>();
            response.put("logs", logs);
            response.put("count", logs.size());
            return Result.success(response);
        } catch (Exception e) {
            logger.error("获取日志失败", e);
            return Result.error("获取日志失败: " + e.getMessage());
        }
    }
}
