/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.dto.LoginRequest;
import com.jupyterhub.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private AuthService authService;

    @PostMapping("/login")
    public Result login(@RequestBody LoginRequest request) {
        System.out.println(request.getUsername() + "\n" + request.getPassword());
        Map<String, String> loginInfo = authService.login(request.getUsername(), request.getPassword());
        System.out.println(loginInfo);

        if (loginInfo != null) {
            logger.info("登录成功: {} (角色: {})", request.getUsername(), loginInfo.get("role"));
            return Result.success(loginInfo);
        }

        logger.warn("登录失败: {}", request.getUsername());
        return Result.error("用户名或密码错误");
    }

    @GetMapping("/validate")
    public Result validateToken(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (authService.validateToken(token)) {
            return Result.success();
        }

        return Result.error(401, "Token无效或已过期");
    }

    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        authService.logout(token);
        return Result.success();
    }
}
