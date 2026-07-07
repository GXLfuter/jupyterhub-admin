/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}
