package com.jupyterhub.model;

import lombok.Data;

/**
 * 服务器资源统计信息
 */
@Data
public class ServerStats {
    /**
     * CPU使用率 (百分比)
     */
    private String cpuUsage;

    /**
     * CPU核心数
     */
    private String cpuCores;

    /**
     * 内存使用率 (百分比)
     */
    private String memoryUsage;

    /**
     * 内存总量 (GB)
     */
    private String memoryTotal;

    /**
     * 内存使用量 (GB)
     */
    private String memoryUsed;

    /**
     * 磁盘使用率 (百分比)
     */
    private String diskUsage;

    /**
     * 磁盘总量
     */
    private String diskTotal;

    /**
     * 磁盘使用量
     */
    private String diskUsed;

    /**
     * 在线容器数量
     */
    private int onlineContainers;

    /**
     * 总容器数量
     */
    private int totalContainers;

    /**
     * 系统负载
     */
    private String loadAverage;

    /**
     * 操作系统信息
     */
    private String osInfo;
    
    /**
     * 网络入站流量 (KB/s)
     */
    private String netIn;
    
    /**
     * 网络出站流量 (KB/s)
     */
    private String netOut;
    
    /**
     * 网络接口名称
     */
    private String netInterface;
    
    /**
     * 进程总数
     */
    private int processCount;
    
    /**
     * 运行中的进程数
     */
    private int runningProcesses;
    
    /**
     * 系统运行时间
     */
    private String uptime;
}
