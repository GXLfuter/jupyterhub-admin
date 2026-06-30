package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理控制器
 */
@RestController
@RequestMapping("/files")
public class FileController {

    @Autowired
    private FileService fileService;

    /**
     * 获取文件列表
     */
    @GetMapping("/list")
    public Result listFiles() {
        return Result.success(fileService.listFiles());
    }

    /**
     * 上传文件（支持多文件）
     */
    @PostMapping("/upload")
    public Result uploadFiles(@RequestParam("file") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return Result.error("请选择要上传的文件");
        }

        List<String> successFiles = new ArrayList<>();
        List<String> failFiles = new ArrayList<>();

        for (MultipartFile file : files) {
            String result = fileService.uploadFile(file);
            if (result.startsWith("SUCCESS")) {
                successFiles.add(file.getOriginalFilename());
            } else {
                failFiles.add(file.getOriginalFilename() + " (" + result.replace("ERROR: ", "") + ")");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("success", successFiles);
        data.put("fail", failFiles);

        if (successFiles.size() == files.length) {
            return Result.success(data).put("msg", "所有文件上传成功");
        } else if (successFiles.size() > 0) {
            return Result.success(data).put("msg", "部分文件上传成功");
        } else {
            return Result.error("所有文件上传失败");
        }
    }

    /**
     * 删除单个文件
     */
    @PostMapping("/delete")
    public Result deleteFile(@RequestBody Map<String, String> params) {
        String path = params.get("path");
        boolean success = fileService.deleteFile(path);
        if (success) {
            return Result.success("删除成功");
        } else {
            return Result.error("删除失败");
        }
    }


    @PostMapping("/delete/batch")
    public Result deleteFiles(@RequestBody Map<String, List<String>> params) {
        List<String> paths = params.get("paths");
        if (paths == null || paths.isEmpty()) {
            return Result.error("请选择要删除的文件");
        }

        int successCount = 0;
        int failCount = 0;

        for (String path : paths) {
            if (fileService.deleteFile(path)) {
                successCount++;
            } else {
                failCount++;
            }
        }

        if (failCount == 0) {
            return Result.success("全部删除成功");
        } else if (successCount > 0) {
            return Result.success("部分删除成功").put("msg", String.format("成功删除 %d 个文件，失败 %d 个文件", successCount, failCount));
        } else {
            return Result.error("全部删除失败");
        }
    }
}
