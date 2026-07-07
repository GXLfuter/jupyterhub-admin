/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.dto.CleanupRequest;
import com.jupyterhub.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    @Autowired
    private TaskService taskService;

    @PostMapping("/cleanup")
    public Result executeCleanup(@RequestBody CleanupRequest request) {
        String result = taskService.executeCleanupNow(request);
        return Result.success(result);
    }

    @PostMapping("/schedule")
    public Result createScheduledTask(@RequestBody Map<String, Object> request) {
        String taskName = (String) request.get("name");
        List<String> usernames = (List<String>) request.get("usernames");
        String cron = (String) request.get("cron"); 

        String taskId = taskService.createScheduledCleanup(taskName, usernames, cron);

        if (taskId != null) {
            return Result.success("定时任务已创建，ID: " + taskId);
        }

        return Result.error("创建定时任务失败");
    }

    @GetMapping("/scheduled")
    public Result getScheduledTasks() {
        List<TaskService.ScheduledTask> tasks = taskService.getScheduledTasks();
        return Result.success(tasks);
    }

    @DeleteMapping("/scheduled/{taskId}")
    public Result deleteScheduledTask(@PathVariable String taskId) {
        boolean success = taskService.deleteScheduledTask(taskId);

        if (success) {
            return Result.success("定时任务已删除");
        }

        return Result.error("删除定时任务失败");
    }

    @PutMapping("/scheduled/{taskId}")
    public Result toggleScheduledTask(@PathVariable String taskId, @RequestParam boolean enabled) {
        boolean success = taskService.toggleTask(taskId, enabled);

        if (success) {
            return Result.success("定时任务状态已更新");
        }

        return Result.error("更新定时任务失败");
    }

    @GetMapping("/history")
    public Result getTaskHistory() {
        List<TaskService.TaskRecord> history = taskService.getTaskHistory();
        return Result.success(history);
    }

    @DeleteMapping("/history")
    public Result clearTaskHistory() {
        taskService.clearTaskHistory();
        return Result.success("已清除所有执行历史");
    }
}
