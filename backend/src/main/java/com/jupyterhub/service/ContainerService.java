/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import com.jupyterhub.model.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContainerService {

    private static final Logger logger = LoggerFactory.getLogger(ContainerService.class);

    @Autowired
    private SshService sshService;

    @Value("${jupyterhub.container-prefix}")
    private String containerPrefix;

    @Value("${jupyterhub.data-dir}")
    private String dataDir;

    public List<Container> listContainers() {
        List<Container> containers = new ArrayList<>();

        String result = sshService.executeCommand(
            "docker ps -a --filter 'name=" + containerPrefix + "' --format '{{.Names}}|{{.Status}}|{{.Image}}|{{.ID}}|{{.CreatedAt}}|{{.Size}}'"
        );

        Map<String, String> directorySizes = getDirectorySizes();

        if (result == null || result.isEmpty() || result.startsWith("ERROR")) {
            return containers;
        }

        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            String[] parts = line.split("\\|");
            if (parts.length >= 4) {
                Container container = new Container();
                container.setName(parts[0].trim());
                container.setStatus(parts[1].trim());
                container.setImage(parts[2].trim());
                container.setId(parts[3].trim());
                if (parts.length >= 5) {
                    container.setCreated(parts[4].trim());
                }
                if (parts.length >= 6) {
                    container.setSize(parts[5].trim());
                }

                String name = container.getName();
                if (name.startsWith(containerPrefix)) {
                    container.setUsername(name.substring(containerPrefix.length()));
                } else {
                    container.setUsername(name);
                }

                container.setOnline(container.getStatus().toLowerCase().contains("up"));

                container.setUptime(parseUptime(container.getStatus()));
                container.setMountCount(4);
                container.setImageSize("N/A");
                container.setCpuUsage("N/A");
                container.setMemoryUsage("N/A");

                String dirSize = directorySizes.get(container.getUsername());
                container.setDirectorySize(dirSize != null ? dirSize : "N/A");

                containers.add(container);
            }
        }

        return containers;
    }

    private Map<String, String> getDirectorySizes() {
        Map<String, String> sizeMap = new HashMap<>();
        try {
            String result = sshService.executeCommand("du -sh " + dataDir + "* 2>/dev/null", false);
            if (result != null && !result.isEmpty() && !result.startsWith("ERROR")) {
                String[] lines = result.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length >= 2) {
                        String size = parts[0].trim();
                        String path = parts[1].trim();
                        String username = path.substring(path.lastIndexOf("/") + 1);
                        sizeMap.put(username, size);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("获取目录大小失败: {}", e.getMessage());
        }
        return sizeMap;
    }

    private String parseUptime(String status) {
        if (status == null) return "N/A";

        try {
            int upIndex = status.indexOf("Up ");
            if (upIndex >= 0) {
                String upPart = status.substring(upIndex + 3).trim();

                int bracketIndex = upPart.indexOf("(");
                if (bracketIndex > 0) {
                    upPart = upPart.substring(0, bracketIndex).trim();
                }

                return convertToChinese(upPart);
            }
        } catch (Exception e) {

        }
        return "N/A";
    }

    private String convertToChinese(String text) {
        if (text == null) return "N/A";

        String result = text;
        result = result.replace("About a ", "1 ");
        result = result.replace("About an ", "1 ");
        result = result.replace("About ", "");
        result = result.replace("virtual", "虚拟");
        result = result.replace("hours", "小时");
        result = result.replace("hour", "小时");
        result = result.replace("minutes", "分钟");
        result = result.replace("minute", "分钟");
        result = result.replace("seconds", "秒");
        result = result.replace("second", "秒");
        result = result.replace("days", "天");
        result = result.replace("day", "天");

        return result;
    }

    public List<Container> listOnlineContainers() {
        List<Container> allContainers = listContainers();
        List<Container> onlineContainers = new ArrayList<>();

        for (Container container : allContainers) {
            if (container.isOnline()) {
                onlineContainers.add(container);
            }
        }

        return onlineContainers;
    }

    public Container getContainer(String username) {
        String result = sshService.executeCommand(
            "docker ps -a --filter 'name=" + containerPrefix + username + "' --format '{{.Names}}|{{.Status}}|{{.Image}}|{{.ID}}'"
        );

        if (result == null || result.isEmpty() || result.startsWith("ERROR")) {
            return null;
        }

        String[] parts = result.split("\\|");
        if (parts.length >= 3) {
            Container container = new Container();
            container.setName(parts[0].trim());
            container.setStatus(parts[1].trim());
            container.setImage(parts[2].trim());
            if (parts.length >= 4) {
                container.setId(parts[3].trim());
            }
            container.setUsername(username);
            container.setOnline(container.getStatus().toLowerCase().contains("up"));
            return container;
        }

        return null;
    }

    public boolean deleteContainer(String username) {
        String containerName = containerPrefix + username;

        try {

            sshService.executeCommand("docker stop " + containerName);

            String result = sshService.executeCommand("docker rm " + containerName);

            logger.info("删除容器 {}: {}", containerName, result);
            return true;

        } catch (Exception e) {
            logger.error("删除容器失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean cleanupStudentData(String username) {
        try {
            String userDir = dataDir + username;

            String cmd = "cd " + userDir + "/ && for item in * .*; do if [ \"$item\" != \".\" ] && [ \"$item\" != \"..\" ] && [ \"$item\" != \"shared\" ] && [ \"$item\" != \"shared_rw\" ]; then rm -rf \"$item\"; fi; done";
            sshService.executeCommand(cmd);

            logger.info("清理用户数据 {}: 已删除除 shared/ 和 shared_rw/ 以外的所有内容", username);
            return true;

        } catch (Exception e) {
            logger.error("清理用户数据失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean deleteContainerAndData(String username) {
        boolean containerDeleted = deleteContainer(username);
        boolean dataDeleted = cleanupStudentData(username);
        return containerDeleted || dataDeleted;
    }

    public String cleanupAllStudentData() {
        StringBuilder result = new StringBuilder();
        int successCount = 0;

        try {
            for (int i = 1; i <= 60; i++) {
                String username = "student" + i;
                String userDir = dataDir + username;

                String cmd = "cd " + userDir + "/ && for item in * .*; do if [ \"$item\" != \".\" ] && [ \"$item\" != \"..\" ] && [ \"$item\" != \"shared\" ] && [ \"$item\" != \"shared_rw\" ]; then rm -rf \"$item\"; fi; done";
                sshService.executeCommand(cmd);

                successCount++;
            }

            result.append("已清理所有学生数据（共").append(successCount).append("个学生）");
            result.append("\n保留了每个学生目录下的 shared/ 和 shared_rw/ 文件夹");

            return result.toString();

        } catch (Exception e) {
            logger.error("清理所有学生数据失败: {}", e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public List<String> getStudentList() {
        List<String> students = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            students.add("student" + i);
        }
        return students;
    }
}
