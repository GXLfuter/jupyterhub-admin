/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import com.jupyterhub.model.SharedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class FileService {

    private static final Logger logger = LoggerFactory.getLogger(FileService.class);

    @Autowired
    private SshService sshService;

    @Value("${jupyterhub.shared-dir}")
    private String sharedDir;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
        "txt", "doc", "docx", "xls", "xlsx", "pdf", "zip", "ppt", "pptx", "rar", "7z",
        "jpg", "jpeg", "png", "gif", "bmp", "mp4", "avi", "mov", "webm",
        "py", "java", "c", "cpp", "cc", "h", "hpp", "js", "ts", "html", "css", "scss",
        "json", "xml", "yaml", "yml", "md", "markdown", "sh", "bash", "bat",
        "sql", "go", "rs", "rb", "php", "cs", "swift", "kt", "scala", "r"
    );

    public List<SharedFile> listFiles() {
        List<SharedFile> files = new ArrayList<>();

        String result = sshService.executeCommand("LC_ALL=en_US.UTF-8 ls -la " + sharedDir, false);

        logger.info("ls 命令结果: [{}]", result);

        if (result == null || result.isEmpty() || result.startsWith("ERROR")) {
            logger.warn("ls 命令失败或无结果");
            return files;
        }

        String[] lines = result.split("\n");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            if (parts.length >= 9) {
                SharedFile file = new SharedFile();

                String perms = parts[0];
                file.setIsDirectory(perms.startsWith("d"));

                try {
                    long size = Long.parseLong(parts[4]);
                    file.setSize(size);
                    file.setSizeFormatted(formatFileSize(size));
                } catch (NumberFormatException e) {
                    file.setSize(0);
                    file.setSizeFormatted("N/A");
                }

                file.setModifyTime(parts[5] + " " + parts[6]);

                StringBuilder name = new StringBuilder();
                for (int j = 8; j < parts.length; j++) {
                    if (name.length() > 0) name.append(" ");
                    name.append(parts[j]);
                }
                file.setName(name.toString());
                file.setPath(sharedDir + file.getName());

                if (file.getName().equals(".") || file.getName().equals("..")) {
                    continue;
                }

                file.setType(getFileType(file.getName()));
                files.add(file);
            }
        }

        logger.info("返回文件列表，数量: {}", files.size());
        return files;
    }

    public String uploadFile(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            return "ERROR: 文件为空";
        }

        String originalFilename = multipartFile.getOriginalFilename();
        if (originalFilename == null) {
            return "ERROR: 文件名无效";
        }

        String extension = getFileExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            return "ERROR: 不支持的文件类型: " + extension;
        }

        try {

            String tempPath = System.getProperty("java.io.tmpdir") + File.separator + System.currentTimeMillis() + "_" + originalFilename;
            multipartFile.transferTo(new File(tempPath));

            String remoteTempPath = "/home/huawei/" + originalFilename;
            boolean uploadOk = sshService.uploadFileToTemp(tempPath, remoteTempPath);

            if (!uploadOk) {
                new File(tempPath).delete();
                return "ERROR: SFTP上传失败";
            }

            String remoteFinalPath = sharedDir + originalFilename;
            String mvResult = sshService.executeCommand("mv " + remoteTempPath + " " + remoteFinalPath);
            logger.info("mv结果: {}", mvResult);

            new File(tempPath).delete();

            logger.info("文件上传成功: {}", originalFilename);
            return "SUCCESS: " + originalFilename;

        } catch (Exception e) {
            logger.error("文件上传失败: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public boolean deleteFile(String filepath) {
        try {
            String result = sshService.executeCommand("rm -rf " + filepath);
            logger.info("删除文件: {}", result);
            return true;
        } catch (Exception e) {
            logger.error("删除文件失败: {}", e.getMessage());
            return false;
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }

    private String getFileType(String filename) {
        String ext = getFileExtension(filename).toLowerCase();
        switch (ext) {
            case "txt": case "md": case "markdown":
                return "文本文件";
            case "py": case "java": case "c": case "cpp": case "cc": case "h": case "hpp":
            case "js": case "ts": case "go": case "rs": case "rb": case "php":
            case "cs": case "swift": case "kt": case "scala": case "r": case "sh": case "bash": case "bat":
                return "代码文件";
            case "html": case "css": case "scss": case "json": case "xml": case "yaml": case "yml": case "sql":
                return "配置/脚本";
            case "doc": case "docx":
                return "Word文档";
            case "xls": case "xlsx":
                return "Excel表格";
            case "pdf":
                return "PDF文档";
            case "ppt": case "pptx":
                return "PowerPoint演示";
            case "zip": case "rar": case "7z":
                return "压缩文件";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
                return "图片文件";
            case "mp4": case "avi": case "mov": case "webm":
                return "视频文件";
            default:
                return "其他文件";
        }
    }

    private String formatFileSize(long size) {
        if (size >= 1073741824) {
            return String.format("%.2f GB", size / 1073741824.0);
        } else if (size >= 1048576) {
            return String.format("%.2f MB", size / 1048576.0);
        } else if (size >= 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return size + " B";
        }
    }
}
