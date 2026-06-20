package com.jupyterhub.model;

import lombok.Data;
import java.util.List;

/**
 * 学生用户信息
 */
@Data
public class StudentUser {
    /**
     * 用户名
     */
    private String username;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 状态 (online/offline)
     */
    private String status;

    /**
     * 登录时间
     */
    private String loginTime;

    /**
     * 容器状态
     */
    private String containerStatus;

    /**
     * 是否在线
     */
    private boolean online;
}
