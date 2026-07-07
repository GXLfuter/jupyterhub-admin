/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.model;

import lombok.Data;

@Data
public class Container {

    private String name;

    private String username;

    private String id;

    private String status;

    private String created;

    private String image;

    private boolean online;

    private String size;

    private String uptime;

    private String ports;

    private String imageSize;

    private int mountCount;

    private String cpuUsage;

    private String memoryUsage;

    private String directorySize;
}
