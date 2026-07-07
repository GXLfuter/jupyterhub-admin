/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.SshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private SshService sshService;

    @GetMapping("/ssh")
    public Result testSsh() {
        boolean success = sshService.testConnection();
        if (success) {
            return Result.success("SSH连接成功");
        }
        return Result.error("SSH连接失败，请检查配置");
    }

    @GetMapping("/command")
    public Result testCommand() {
        String result = sshService.executeCommand("echo 'Hello'");
        return Result.success(result);
    }
}
