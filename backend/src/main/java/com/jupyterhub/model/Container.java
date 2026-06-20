package com.jupyterhub.model;

import lombok.Data;

/**
 * Docker容器信息
 */
@Data
public class Container {
    /**
     * 容器名称
     */
    private String name;

    /**
     * 用户名
     */
    private String username;

    /**
     * 容器ID
     */
    private String id;

    /**
     * 状态 (running, exited, etc.)
     */
    private String status;

    /**
     * 创建时间
     */
    private String created;

    /**
     * 镜像
     */
    private String image;

    /**
     * 是否在线
     */
    private boolean online;
    
    /**
     * 容器大小
     */
    private String size;
    
    /**
     * 运行时间
     */
    private String uptime;
    
    /**
     * 端口
     */
    private String ports;
    
    /**
     * 镜像大小
     */
    private String imageSize;
    
    /**
     * 挂载卷数量
     */
    private int mountCount;
    
    /**
     * CPU使用率
     */
    private String cpuUsage;
    
    /**
     * 内存使用率
     */
    private String memoryUsage;
    
    /**
     * 学生home目录大小
     */
    private String directorySize;
}
