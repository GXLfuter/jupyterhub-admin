package com.jupyterhub.service;

import com.jupyterhub.model.ServerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 服务器监控服务 - 高性能版本
 */
@Service
public class MonitorService {

    private static final Logger logger = LoggerFactory.getLogger(MonitorService.class);

    @Autowired
    private SshService sshService;

    @Value("${jupyterhub.network-interface:}")
    private String specifiedNetworkInterface;

    // 缓存上次的网络数据用于计算速率
    private long lastNetIn = 0;
    private long lastNetOut = 0;
    private long lastNetTime = 0;

    /**
     * 获取服务器资源统计信息 - 单次SSH调用获取所有数据
     */
    public ServerStats getServerStats() {
        ServerStats stats = new ServerStats();
        
        try {
            // 将所有命令合并成一个脚本一次性执行
            // 使用分隔符区分不同数据块
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

            // 获取系统信息不需要sudo权限，避免密码输入问题
            String allOutput = sshService.executeCommand(script, false);
            
            if (allOutput != null && !allOutput.trim().isEmpty()) {
                parseAllOutput(allOutput, stats);
            } else {
                // 备用方案：返回默认值，不阻塞
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
            // 先按行分割
            String[] allLines = output.split("\n");
            String currentSection = null;
            int lineIndex = 0;
            
            while (lineIndex < allLines.length) {
                String line = allLines[lineIndex].trim();
                
                if (line.startsWith("===") && line.endsWith("===")) {
                    // 这是一个新的section标记
                    currentSection = line.substring(3, line.length() - 3).toLowerCase();
                    lineIndex++;
                    continue;
                }
                
                if (currentSection != null && !line.isEmpty()) {
                    // 根据当前section处理数据
                    switch (currentSection) {
                        case "cpu":
                            // CPU部分有3行数据：使用率、核心数、负载
                            stats.setCpuUsage(line.trim());
                            if (lineIndex + 1 < allLines.length) {
                                stats.setCpuCores(allLines[lineIndex + 1].trim());
                            }
                            if (lineIndex + 2 < allLines.length) {
                                stats.setLoadAverage(allLines[lineIndex + 2].trim());
                            }
                            lineIndex += 3; // 跳过这3行
                            continue;
                            
                        case "mem":
                            // 内存部分有1行数据
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
                            // 磁盘部分有1行数据
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
                            // OS部分有1行数据
                            stats.setOsInfo(line.trim());
                            lineIndex++;
                            continue;
                            
                        case "net":
                            // 网络部分有多行数据，解析所有网卡并选择合适的
                            parseAllNetworkInterfaces(allLines, lineIndex, stats);
                            // 跳到下一个section
                            while (lineIndex < allLines.length && !allLines[lineIndex].trim().startsWith("===")) {
                                lineIndex++;
                            }
                            continue;
                            
                        case "procs":
                            // 进程部分有2行数据：总进程数、运行中进程数
                            stats.setProcessCount(parseIntOrDefault(line.trim(), 0));
                            if (lineIndex + 1 < allLines.length) {
                                stats.setRunningProcesses(parseIntOrDefault(allLines[lineIndex + 1].trim(), 0));
                            }
                            lineIndex += 2;
                            continue;
                            
                        case "uptime":
                            // 运行时间部分有1行数据
                            parseUptime(line.trim(), stats);
                            lineIndex++;
                            continue;
                            
                        case "containers":
                            // 容器部分有2行数据：在线容器、总容器
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
    
    /**
     * 解析所有网络接口并选择合适的一个
     */
    private void parseAllNetworkInterfaces(String[] allLines, int startLineIndex, ServerStats stats) {
        String selectedInterface = null;
        long selectedNetIn = 0;
        long selectedNetOut = 0;
        
        // 跳过前两行（表头）
        int lineIndex = startLineIndex + 2;
        
        while (lineIndex < allLines.length) {
            String line = allLines[lineIndex].trim();
            
            // 如果遇到下一个section标记，停止解析
            if (line.startsWith("===")) {
                break;
            }
            
            if (line.isEmpty()) {
                lineIndex++;
                continue;
            }
            
            try {
                // 解析网卡行
                String[] parts = line.split(":");
                if (parts.length > 1) {
                    String iface = parts[0].trim();
                    String values = parts[1].trim();
                    String[] valueArray = values.split("\\s+");
                    
                    if (valueArray.length >= 9) {
                        long currentNetIn = Long.parseLong(valueArray[0]);
                        long currentNetOut = Long.parseLong(valueArray[8]);
                        
                        // 判断是否选择这个网卡
                        if (shouldSelectInterface(iface, selectedInterface)) {
                            selectedInterface = iface;
                            selectedNetIn = currentNetIn;
                            selectedNetOut = currentNetOut;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略解析错误的行
            }
            
            lineIndex++;
        }
        
        // 如果选择了合适的网卡，设置它
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
            // 没有找到合适的网卡，使用默认值
            stats.setNetInterface("eth0");
            stats.setNetIn("0.0");
            stats.setNetOut("0.0");
        }
    }
    
    /**
     * 判断是否应该选择这个网卡
     * 优先级：
     * 1. 配置文件中指定的网卡
     * 2. eno/ens/enp 开头的物理网卡
     * 3. eth 开头的网卡
     * 4. 排除虚拟网卡（veth, docker, br, lo, virbr等）
     */
    private boolean shouldSelectInterface(String iface, String currentSelected) {
        // 排除虚拟网卡
        if (iface.startsWith("veth") || iface.startsWith("docker") || 
            iface.startsWith("br-") || iface.equals("lo") || 
            iface.startsWith("virbr") || iface.startsWith("vnet")) {
            return false;
        }
        
        // 如果配置了指定的网卡，优先选择
        if (specifiedNetworkInterface != null && !specifiedNetworkInterface.trim().isEmpty()) {
            return iface.equals(specifiedNetworkInterface.trim());
        }
        
        // 如果没有配置，智能选择
        // 优先选择 eno/ens/enp 开头的物理网卡
        boolean isPhysicalInterface = iface.startsWith("eno") || 
                                      iface.startsWith("ens") || 
                                      iface.startsWith("enp");
        
        if (isPhysicalInterface) {
            return true;
        }
        
        // 其次选择 eth 开头的网卡
        if (iface.startsWith("eth")) {
            // 如果当前已经选择了物理网卡，就不要替换
            if (currentSelected != null && 
                (currentSelected.startsWith("eno") || 
                 currentSelected.startsWith("ens") || 
                 currentSelected.startsWith("enp"))) {
                return false;
            }
            return true;
        }
        
        // 默认不选择
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
