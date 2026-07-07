/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "hsj@2024";

    private static final String STUDENT_PASSWORD = "test1234";
    private static final Pattern STUDENT_PATTERN = Pattern.compile("^student([1-9]|[1-5][0-9]|60)$");

    private final ConcurrentHashMap<String, Long> tokenStore = new ConcurrentHashMap<>();
    private static final long TOKEN_EXPIRE_TIME = 24 * 60 * 60 * 1000; 

    public Map<String, String> login(String username, String password) {

        if (ADMIN_USERNAME.equals(username) && ADMIN_PASSWORD.equals(password)) {
            String token = UUID.randomUUID().toString();
            tokenStore.put(token, System.currentTimeMillis() + TOKEN_EXPIRE_TIME);
            logger.info("管理员登录成功: {}", username);
            Map<String, String> result = new HashMap<>();
            result.put("token", token);
            result.put("username", username);
            result.put("role", "ADMIN");
            return result;
        }

        if (STUDENT_PATTERN.matcher(username).matches() && STUDENT_PASSWORD.equals(password)) {
            String token = UUID.randomUUID().toString();
            tokenStore.put(token, System.currentTimeMillis() + TOKEN_EXPIRE_TIME);
            logger.info("学生登录成功: {}", username);
            Map<String, String> result = new HashMap<>();
            result.put("token", token);
            result.put("username", username);
            result.put("role", "STUDENT");
            return result;
        }

        logger.warn("登录失败: {}", username);
        return null;
    }

    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Long expireTime = tokenStore.get(token);
        if (expireTime == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireTime) {
            tokenStore.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        tokenStore.remove(token);
    }
}
