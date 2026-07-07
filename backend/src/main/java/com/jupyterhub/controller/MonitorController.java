/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.model.ServerStats;
import com.jupyterhub.service.MonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/monitor")
public class MonitorController {

    @Autowired
    private MonitorService monitorService;

    @GetMapping("/stats")
    public Result getServerStats() {
        ServerStats stats = monitorService.getServerStats();
        return Result.success(stats);
    }
}
