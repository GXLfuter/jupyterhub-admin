/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import com.jupyterhub.model.ServerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    private static final Logger logger = LoggerFactory.getLogger(MonitorService.class);

    @Autowired
    private SshService sshService;

    @Value("${jupyterhub.network-interface:}")
    private String specifiedNetworkInterface;

    private long lastNetIn = 0;
    private long lastNetOut = 0;
    private long lastNetTime = 0;

    public ServerStats getServerStats() {
        ServerStats stats = new ServerStats();

        try {

            String script = "\n" +
                "# 获取所有系统信息\n" +
                "echo '===CPU==='\n" +
                "top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | cut -d'%' -f1\n" +
                "nproc\n" +
                "uptime | awk -F'load average:' '{print $2}'\n" +
                "echo '===MEM==='\n" +
                "free -b | grep Mem\n" +
                "echo '===DISK==='\n" +
                "df -B1 / | tail -1\n" +
                "echo '===OS==='\n" +
                "cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | awk -F\"'\" '{print $2}' || echo 'Linux'\n" +
                "echo '===NET==='\n" +
                "cat /proc/net/dev\n" +
                "echo '===PROCS==='\n" +
                "ps aux --no-headers 2>/dev/null | wc -l\n" +
                "ps -eo state --no-headers 2>/dev/null | grep -E 'R|D' | wc -l\n" +
                "echo '===UPTIME==='\n" +
                "cat /proc/uptime\n" +
                "echo '===CONTAINERS==='\n" +
                "docker ps --filter 'name=jupyter-' -q 2>/dev/null | wc -l\n" +
                "docker ps -a --filter 'name=jupyter-' -q 2>/dev/null | wc -l\n";

            String allOutput = sshService.executeCommand(script, false);

            if (allOutput != null && !allOutput.trim().isEmpty()) {
                parseAllOutput(allOutput, stats);
            } else {

                fillDefaultValues(stats);
            }

        } catch (Exception e) {
            logger.error("获取服务器统计信息失败: {}", e.getMessage());
            fillDefaultValues(stats);
        }

        return stats;
    }

    private void parseAllOutput(String output, ServerStats stats) {
        try {

            String[] allLines = output.split("\n");
            String currentSection = null;
            int lineIndex = 0;

            while (lineIndex < allLines.length) {
                String line = allLines[lineIndex].trim();

                if (line.startsWith("===") && line.endsWith("===")) {

                    currentSection = line.substring(3, line.length() - 3).toLowerCase();
                    lineIndex++;
                    continue;
                }

                if (currentSection != null && !line.isEmpty()) {

                    switch (currentSection) {
                        case "cpu":

                            stats.setCpuUsage(line.trim());
                            if (lineIndex + 1 < allLines.length) {
                                stats.setCpuCores(allLines[lineIndex + 1].trim());
                            }
                            if (lineIndex + 2 < allLines.length) {
                                stats.setLoadAverage(allLines[lineIndex + 2].trim());
                            }
                            lineIndex += 3; 
                            continue;

                        case "mem":

                            String memLine = line.trim();
                            String[] memParts = memLine.split("\\s+");
                            if (memParts.length >= 3) {
                                try {
                                    long total = Long.parseLong(memParts[1]);
                                    long used = Long.parseLong(memParts[2]);
                                    double usagePercent = (used * 100.0) / total;
                                    stats.setMemoryTotal(formatBytes(total));
                                    stats.setMemoryUsed(formatBytes(used));
                                    stats.setMemoryUsage(String.format("%.1f", usagePercent));
                                } catch (Exception e) {}
                            }
                            lineIndex++;
                            continue;

                        case "disk":

                            String diskLine = line.trim();
                            String[] diskParts = diskLine.split("\\s+");
                            if (diskParts.length >= 5) {
                                try {
                                    stats.setDiskTotal(formatBytes(Long.parseLong(diskParts[1])));
                                    stats.setDiskUsed(formatBytes(Long.parseLong(diskParts[2])));
                                    stats.setDiskUsage(diskParts[4].replace("%", ""));
                                } catch (Exception e) {}
                            }
                            lineIndex++;
                            continue;

                        case "os":

                            stats.setOsInfo(line.trim());
                            lineIndex++;
                            continue;

                        case "net":

                            parseAllNetworkInterfaces(allLines, lineIndex, stats);

                            while (lineIndex < allLines.length && !allLines[lineIndex].trim().startsWith("===")) {
                                lineIndex++;
                            }
                            continue;

                        case "procs":

                            stats.setProcessCount(parseIntOrDefault(line.trim(), 0));
                            if (lineIndex + 1 < allLines.length) {
                                stats.setRunningProcesses(parseIntOrDefault(allLines[lineIndex + 1].trim(), 0));
                            }
                            lineIndex += 2;
                            continue;

                        case "uptime":

                            parseUptime(line.trim(), stats);
                            lineIndex++;
                            continue;

                        case "containers":

                            stats.setOnlineContainers(parseIntOrDefault(line.trim(), 0));
                            if (lineIndex + 1 < allLines.length) {
                                stats.setTotalContainers(parseIntOrDefault(allLines[lineIndex + 1].trim(), 0));
                            }
                            lineIndex += 2;
                            continue;
                    }
                }

                lineIndex++;
            }
        } catch (Exception e) {
            logger.error("解析统计信息失败: {}", e.getMessage());
        }
    }

    private void parseAllNetworkInterfaces(String[] allLines, int startLineIndex, ServerStats stats) {
        String selectedInterface = null;
        long selectedNetIn = 0;
        long selectedNetOut = 0;

        int lineIndex = startLineIndex + 2;

        while (lineIndex < allLines.length) {
            String line = allLines[lineIndex].trim();

            if (line.startsWith("===")) {
                break;
            }

            if (line.isEmpty()) {
                lineIndex++;
                continue;
            }

            try {

                String[] parts = line.split(":");
                if (parts.length > 1) {
                    String iface = parts[0].trim();
                    String values = parts[1].trim();
                    String[] valueArray = values.split("\\s+");

                    if (valueArray.length >= 9) {
                        long currentNetIn = Long.parseLong(valueArray[0]);
                        long currentNetOut = Long.parseLong(valueArray[8]);

                        if (shouldSelectInterface(iface, selectedInterface)) {
                            selectedInterface = iface;
                            selectedNetIn = currentNetIn;
                            selectedNetOut = currentNetOut;
                        }
                    }
                }
            } catch (Exception e) {

            }

            lineIndex++;
        }

        if (selectedInterface != null) {
            stats.setNetInterface(selectedInterface);

            long now = System.currentTimeMillis();
            if (lastNetTime > 0 && lastNetIn > 0 && lastNetOut > 0) {
                long timeDiff = (now - lastNetTime) / 1000;
                if (timeDiff > 0) {
                    stats.setNetIn(String.format("%.1f", Math.max(0, (selectedNetIn - lastNetIn) / 1024.0 / timeDiff)));
                    stats.setNetOut(String.format("%.1f", Math.max(0, (selectedNetOut - lastNetOut) / 1024.0 / timeDiff)));
                } else {
                    stats.setNetIn("0.0");
                    stats.setNetOut("0.0");
                }
            } else {
                stats.setNetIn("0.0");
                stats.setNetOut("0.0");
            }

            lastNetIn = selectedNetIn;
            lastNetOut = selectedNetOut;
            lastNetTime = now;
        } else {

            stats.setNetInterface("eth0");
            stats.setNetIn("0.0");
            stats.setNetOut("0.0");
        }
    }

    private boolean shouldSelectInterface(String iface, String currentSelected) {

        if (iface.startsWith("veth") || iface.startsWith("docker") || 
            iface.startsWith("br-") || iface.equals("lo") || 
            iface.startsWith("virbr") || iface.startsWith("vnet")) {
            return false;
        }

        if (specifiedNetworkInterface != null && !specifiedNetworkInterface.trim().isEmpty()) {
            return iface.equals(specifiedNetworkInterface.trim());
        }

        boolean isPhysicalInterface = iface.startsWith("eno") || 
                                      iface.startsWith("ens") || 
                                      iface.startsWith("enp");

        if (isPhysicalInterface) {
            return true;
        }

        if (iface.startsWith("eth")) {

            if (currentSelected != null && 
                (currentSelected.startsWith("eno") || 
                 currentSelected.startsWith("ens") || 
                 currentSelected.startsWith("enp"))) {
                return false;
            }
            return true;
        }

        return false;
    }

    private void parseUptime(String uptimeLine, ServerStats stats) {
        try {
            double sec = Double.parseDouble(uptimeLine.trim().split("\\s+")[0]);
            long days = (long) (sec / 86400);
            long hours = (long) ((sec % 86400) / 3600);
            long mins = (long) ((sec % 3600) / 60);
            stats.setUptime(String.format("up %d days, %d hours, %d mins", days, hours, mins));
        } catch (Exception e) {
            stats.setUptime("N/A");
        }
    }

    private void fillDefaultValues(ServerStats stats) {
        stats.setCpuUsage("0");
        stats.setCpuCores("1");
        stats.setLoadAverage("N/A");
        stats.setMemoryUsage("0");
        stats.setMemoryTotal("N/A");
        stats.setMemoryUsed("N/A");
        stats.setDiskUsage("0");
        stats.setDiskTotal("N/A");
        stats.setDiskUsed("N/A");
        stats.setOsInfo("Linux");
        stats.setNetInterface("eth0");
        stats.setNetIn("0.0");
        stats.setNetOut("0.0");
        stats.setProcessCount(0);
        stats.setRunningProcesses(0);
        stats.setUptime("N/A");
        stats.setOnlineContainers(0);
        stats.setTotalContainers(0);
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1073741824) {
            return String.format("%.1f GB", bytes / 1073741824.0);
        } else if (bytes >= 1048576) {
            return String.format("%.1f MB", bytes / 1048576.0);
        } else if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }
}
