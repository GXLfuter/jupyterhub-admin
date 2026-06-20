package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统配置控制器
 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    @Value("${jupyterhub.host}")
    private String host;

    @Value("${jupyterhub.port}")
    private int port;

    @Value("${jupyterhub.shared-dir}")
    private String sharedDir;

    /**
     * 获取服务器配置
     */
    @GetMapping
    public Result getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("host", host);
        config.put("port", port);
        config.put("sharedDir", sharedDir);
        return Result.success(config);
    }
}
