package com.jupyterhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 认证服务
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    // 硬编码的管理员账号
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "hsj@2024";

    // 学生账号统一密码
    private static final String STUDENT_PASSWORD = "test1234";
    private static final Pattern STUDENT_PATTERN = Pattern.compile("^student([1-9]|[1-5][0-9]|60)$");

    // Token存储（生产环境应使用Redis）
    private final ConcurrentHashMap<String, Long> tokenStore = new ConcurrentHashMap<>();
    private static final long TOKEN_EXPIRE_TIME = 24 * 60 * 60 * 1000; // 24小时

    /**
     * 用户登录验证
     * @param username 用户名
     * @param password 密码
     * @return 成功返回包含 token, username, role 的 Map；失败返回 null
     */
    public Map<String, String> login(String username, String password) {
        // 管理员账号
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

        // 学生账号 student1 ~ student60
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

    /**
     * 验证token是否有效
     * @param token token
     * @return 是否有效
     */
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

    /**
     * 登出，移除token
     * @param token token
     */
    public void logout(String token) {
        tokenStore.remove(token);
    }
}
