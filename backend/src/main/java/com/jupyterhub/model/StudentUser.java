/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.model;

import lombok.Data;
import java.util.List;

@Data
public class StudentUser {

    private String username;

    private String containerName;

    private String status;

    private String loginTime;

    private String containerStatus;

    private boolean online;
}
