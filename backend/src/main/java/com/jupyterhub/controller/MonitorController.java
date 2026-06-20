package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.model.ServerStats;
import com.jupyterhub.service.MonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 服务器监控控制器
 */
@RestController
@RequestMapping("/monitor")
public class MonitorController {

    @Autowired
    private MonitorService monitorService;

    /**
     * 获取服务器资源统计
     */
    @GetMapping("/stats")
    public Result getServerStats() {
        ServerStats stats = monitorService.getServerStats();
        return Result.success(stats);
    }
}
