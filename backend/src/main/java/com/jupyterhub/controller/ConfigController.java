/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/config")
public class ConfigController {

    @Value("${jupyterhub.host}")
    private String host;

    @Value("${jupyterhub.port}")
    private int port;

    @Value("${jupyterhub.shared-dir}")
    private String sharedDir;

    @GetMapping
    public Result getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("host", host);
        config.put("port", port);
        config.put("sharedDir", sharedDir);
        return Result.success(config);
    }
}
