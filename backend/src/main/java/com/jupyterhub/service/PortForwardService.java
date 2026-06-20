package com.jupyterhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PortForwardService {

    private static final Logger logger = LoggerFactory.getLogger(PortForwardService.class);

    @Autowired
    private SshService sshService;

    private static final String SCRIPT_PATH = "/opt/jupyterhub_prod/ip_res.sh";
    private static final String LOG_FILE = "/tmp/ip_res_monitor.log";
    private static final String MONITOR_PID_FILE = "/tmp/ip_res_monitor.pid";

    public List<Map<String, String>> getForwardStatus() {
        List<Map<String, String>> forwards = new ArrayList<>();
        String output = sshService.executeCommand(SCRIPT_PATH + " status", true);

        if (output == null || output.isEmpty()) {
            return forwards;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("当前活跃的转发：")) {
                continue;
            }

            Map<String, String> forward = new HashMap<>();

            if (line.contains("->")) {
                String[] parts = line.split("->");
                String leftPart = parts[0].trim();
                String rightPart = parts[1].trim();

                int colonIndex = leftPart.indexOf(":");
                if (colonIndex > 0) {
                    forward.put("student", leftPart.substring(0, colonIndex).trim());
                    String hostPortStr = leftPart.substring(colonIndex + 1).trim();
                    hostPortStr = hostPortStr.replace("宿主机端口", "").trim();
                    forward.put("hostPort", hostPortStr);
                }

                String containerPortStr = rightPart;
                if (containerPortStr.contains("容器端口")) {
                    containerPortStr = containerPortStr.substring(containerPortStr.indexOf("容器端口") + 4).trim();
                    int spaceIndex = containerPortStr.indexOf(" ");
                    if (spaceIndex > 0) {
                        containerPortStr = containerPortStr.substring(0, spaceIndex).trim();
                    }
                }
                forward.put("containerPort", containerPortStr);

                if (rightPart.contains("运行中")) {
                    forward.put("status", "running");
                } else if (rightPart.contains("已失效")) {
                    forward.put("status", "failed");
                } else {
                    forward.put("status", "unknown");
                }

                int pidStart = rightPart.indexOf("(pid ");
                if (pidStart > 0) {
                    int pidEnd = rightPart.indexOf(")", pidStart);
                    if (pidEnd > pidStart) {
                        forward.put("pid", rightPart.substring(pidStart + 5, pidEnd).trim());
                    }
                }

                forwards.add(forward);
            }
        }

        return forwards;
    }

    public String addForward(String student, int containerPort, int hostPort) {
        String command = String.format("%s %s %d %d", SCRIPT_PATH, student, containerPort, hostPort);
        String result = sshService.executeCommand(command);

        if (result != null && !result.startsWith("ERROR")) {
            logger.info("添加端口转发成功: {} -> {}:{}", hostPort, student, containerPort);
            return result;
        }

        logger.error("添加端口转发失败: {}", result);
        return null;
    }

    public String stopForward(String student, int hostPort) {
        String pidFile = "/tmp/ip_res_pids/" + student + "_" + hostPort + ".pid";
        String command = "cat " + pidFile + " 2>/dev/null | xargs -r kill -TERM 2>/dev/null; sleep 1; cat " + pidFile + " 2>/dev/null | xargs -r kill -9 2>/dev/null";
        String result = sshService.executeCommand(command, true);
        logger.info("停止端口转发进程: {}:{}", student, hostPort);
        return "进程已停止";
    }

    public String deleteForward(String student, int hostPort) {
        String command = String.format("%s stop %s %d", SCRIPT_PATH, student, hostPort);
        String result = sshService.executeCommand(command);

        if (result != null && !result.startsWith("ERROR")) {
            logger.info("删除端口转发成功: {}:{}", student, hostPort);
            return result;
        }

        logger.error("删除端口转发失败: {}", result);
        return null;
    }

    public String startMonitor() {
        String command = SCRIPT_PATH + " start-monitor";
        String result = sshService.executeCommand(command);

        if (result != null && !result.startsWith("ERROR")) {
            logger.info("启动端口监控成功");
            return result;
        }

        logger.error("启动端口监控失败: {}", result);
        return null;
    }

    public String stopMonitor() {
        String command = SCRIPT_PATH + " stop-monitor";
        String result = sshService.executeCommand(command);

        if (result != null && !result.startsWith("ERROR")) {
            logger.info("停止端口监控成功");
            return result;
        }

        logger.error("停止端口监控失败: {}", result);
        return null;
    }

    public boolean isMonitorRunning() {
        String output = sshService.executeCommand("cat " + MONITOR_PID_FILE, true);
        if (output == null || output.isEmpty()) {
            return false;
        }

        try {
            int pid = Integer.parseInt(output.trim());
            String result = sshService.executeCommand("kill -0 " + pid, true);
            return result != null && !result.startsWith("ERROR");
        } catch (NumberFormatException e) {
            return false;
        }
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
}
