package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.dto.CleanupRequest;
import com.jupyterhub.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 定时任务控制器
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    @Autowired
    private TaskService taskService;

    /**
     * 立即执行清理任务
     */
    @PostMapping("/cleanup")
    public Result executeCleanup(@RequestBody CleanupRequest request) {
        String result = taskService.executeCleanupNow(request);
        return Result.success(result);
    }

    /**
     * 创建定时清理任务
     */
    @PostMapping("/schedule")
    public Result createScheduledTask(@RequestBody Map<String, Object> request) {
        String taskName = (String) request.get("name");
        List<String> usernames = (List<String>) request.get("usernames");
        String cron = (String) request.get("cron"); // 格式: HH:mm

        String taskId = taskService.createScheduledCleanup(taskName, usernames, cron);

        if (taskId != null) {
            return Result.success("定时任务已创建，ID: " + taskId);
        }

        return Result.error("创建定时任务失败");
    }

    /**
     * 获取定时任务列表
     */
    @GetMapping("/scheduled")
    public Result getScheduledTasks() {
        List<TaskService.ScheduledTask> tasks = taskService.getScheduledTasks();
        return Result.success(tasks);
    }

    /**
     * 删除定时任务
     */
    @DeleteMapping("/scheduled/{taskId}")
    public Result deleteScheduledTask(@PathVariable String taskId) {
        boolean success = taskService.deleteScheduledTask(taskId);

        if (success) {
            return Result.success("定时任务已删除");
        }

        return Result.error("删除定时任务失败");
    }

    /**
     * 启用/禁用定时任务
     */
    @PutMapping("/scheduled/{taskId}")
    public Result toggleScheduledTask(@PathVariable String taskId, @RequestParam boolean enabled) {
        boolean success = taskService.toggleTask(taskId, enabled);

        if (success) {
            return Result.success("定时任务状态已更新");
        }

        return Result.error("更新定时任务失败");
    }

    /**
     * 获取任务执行历史
     */
    @GetMapping("/history")
    public Result getTaskHistory() {
        List<TaskService.TaskRecord> history = taskService.getTaskHistory();
        return Result.success(history);
    }

    /**
     * 清除所有任务执行历史
     */
    @DeleteMapping("/history")
    public Result clearTaskHistory() {
        taskService.clearTaskHistory();
        return Result.success("已清除所有执行历史");
    }
}
