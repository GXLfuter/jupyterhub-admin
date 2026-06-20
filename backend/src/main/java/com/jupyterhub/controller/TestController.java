package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.SshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 系统测试控制器
 */
@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private SshService sshService;

    /**
     * 测试SSH连接
     */
    @GetMapping("/ssh")
    public Result testSsh() {
        boolean success = sshService.testConnection();
        if (success) {
            return Result.success("SSH连接成功");
        }
        return Result.error("SSH连接失败，请检查配置");
    }

    /**
     * 测试执行命令
     */
    @GetMapping("/command")
    public Result testCommand() {
        String result = sshService.executeCommand("echo 'Hello'");
        return Result.success(result);
    }
}
