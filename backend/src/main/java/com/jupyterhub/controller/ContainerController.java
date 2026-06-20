package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.model.Container;
import com.jupyterhub.service.ContainerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 容器管理控制器
 */
@RestController
@RequestMapping("/containers")
public class ContainerController {

    @Autowired
    private ContainerService containerService;

    /**
     * 获取所有容器列表
     */
    @GetMapping
    public Result listContainers() {
        List<Container> containers = containerService.listContainers();
        return Result.success(containers);
    }

    /**
     * 获取在线容器列表
     */
    @GetMapping("/online")
    public Result listOnlineContainers() {
        List<Container> containers = containerService.listOnlineContainers();
        return Result.success(containers);
    }

    /**
     * 获取指定学生的容器
     */
    @GetMapping("/{username}")
    public Result getContainer(@PathVariable String username) {
        Container container = containerService.getContainer(username);
        if (container != null) {
            return Result.success(container);
        }
        return Result.error("未找到该学生的容器");
    }

    /**
     * 删除指定学生的容器（保留数据）
     */
    @DeleteMapping("/{username}")
    public Result deleteContainer(@PathVariable String username) {
        boolean success = containerService.deleteContainer(username);
        if (success) {
            return Result.success("容器已删除");
        }
        return Result.error("删除容器失败");
    }

    /**
     * 批量删除容器（保留数据）
     */
    @DeleteMapping("/batch")
    public Result batchDeleteContainers(@RequestBody Map<String, List<String>> request) {
        List<String> usernames = request.get("usernames");
        if (usernames == null || usernames.isEmpty()) {
            return Result.error("请选择要删除的学生");
        }
        
        int successCount = 0;
        int failCount = 0;
        
        for (String username : usernames) {
            boolean success = containerService.deleteContainer(username);
            if (success) {
                successCount++;
            } else {
                failCount++;
            }
        }
        
        if (failCount == 0) {
            return Result.success("成功删除 " + successCount + " 个容器");
        } else if (successCount == 0) {
            return Result.error("批量删除失败");
        } else {
            return Result.success("成功删除 " + successCount + " 个容器，失败 " + failCount + " 个");
        }
    }

    /**
     * 清理指定学生的数据（只删数据，不删容器）
     */
    @DeleteMapping("/{username}/data")
    public Result cleanupStudentData(@PathVariable String username) {
        boolean success = containerService.cleanupStudentData(username);
        if (success) {
            return Result.success("数据已清理，容器保留");
        }
        return Result.error("清理数据失败");
    }

    /**
     * 批量清理学生数据（只删数据，不删容器）
     */
    @DeleteMapping("/batch/data")
    public Result batchCleanupStudentData(@RequestBody Map<String, List<String>> request) {
        List<String> usernames = request.get("usernames");
        if (usernames == null || usernames.isEmpty()) {
            return Result.error("请选择要清理数据的学生");
        }
        
        int successCount = 0;
        int failCount = 0;
        
        for (String username : usernames) {
            boolean success = containerService.cleanupStudentData(username);
            if (success) {
                successCount++;
            } else {
                failCount++;
            }
        }
        
        if (failCount == 0) {
            return Result.success("成功清理 " + successCount + " 个学生的数据");
        } else if (successCount == 0) {
            return Result.error("批量清理失败");
        } else {
            return Result.success("成功清理 " + successCount + " 个学生的数据，失败 " + failCount + " 个");
        }
    }

    /**
     * 删除指定学生的容器和数据
     */
    @DeleteMapping("/{username}/all")
    public Result deleteContainerAndData(@PathVariable String username) {
        boolean success = containerService.deleteContainerAndData(username);
        if (success) {
            return Result.success("容器和数据已清理");
        }
        return Result.error("清理失败");
    }

    /**
     * 获取学生列表
     */
    @GetMapping("/students")
    public Result getStudentList() {
        List<String> students = containerService.getStudentList();
        return Result.success(students);
    }

    /**
     * 清理所有学生数据（只删数据目录，不删容器）
     */
    @DeleteMapping("/cleanup/data")
    public Result cleanupAllStudentData() {
        String result = containerService.cleanupAllStudentData();
        return Result.success(result);
    }
}
