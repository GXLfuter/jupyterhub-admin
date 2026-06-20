package com.jupyterhub.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SSH终端控制器
 * SSH连接通过WebSocket处理，此处可提供配置信息
 */
@RestController
@RequestMapping("/ssh")
public class SshController {

    /**
     * 获取SSH连接配置（可选，用于前端验证连接）
     */
    // @GetMapping("/config")
    // public Result getConfig() {
    //     Map<String, Object> config = new HashMap<>();
    //     config.put("host", "192.168.29.220");
    //     config.put("port", 22);
    //     return Result.success(config);
    // }
}
