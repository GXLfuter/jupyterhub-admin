/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.model;

import lombok.Data;

@Data
public class ServerStats {

    private String cpuUsage;

    private String cpuCores;

    private String memoryUsage;

    private String memoryTotal;

    private String memoryUsed;

    private String diskUsage;

    private String diskTotal;

    private String diskUsed;

    private int onlineContainers;

    private int totalContainers;

    private String loadAverage;

    private String osInfo;

    private String netIn;

    private String netOut;

    private String netInterface;

    private int processCount;

    private int runningProcesses;

    private String uptime;
}
