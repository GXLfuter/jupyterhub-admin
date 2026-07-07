/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupyterhub.dto.CleanupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TaskService {

    private static final Logger logger = LoggerFactory.getLogger(TaskService.class);

    @Autowired
    private ContainerService containerService;

    private final Map<String, ScheduledTask> scheduledTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private final List<TaskRecord> taskHistory = new ArrayList<>();

    private final ObjectMapper objectMapper;

    private static final String TASKS_FILE = "data/tasks.json";
    private static final String HISTORY_FILE = "data/history.json";

    public TaskService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        loadTasksFromFile();
        loadHistoryFromFile();

        for (ScheduledTask task : scheduledTasks.values()) {
            if (task.isEnabled()) {

                task.setNextRunTime(calculateNextRun(task.getHour(), task.getMinute()));
                scheduleTask(task);
            }
        }
        logger.info("定时任务服务初始化完成，已加载 {} 个任务，{} 条历史记录", scheduledTasks.size(), taskHistory.size());
    }

    public String createScheduledCleanup(String taskName, List<String> usernames, String cronExpression) {
        try {

            String[] parts = cronExpression.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);

            ScheduledTask task = new ScheduledTask();
            task.setId(UUID.randomUUID().toString());
            task.setName(taskName);
            task.setUsernames(usernames);
            task.setHour(hour);
            task.setMinute(minute);
            task.setEnabled(true);
            task.setCreatedAt(LocalDateTime.now());

            LocalDateTime nextRun = calculateNextRun(hour, minute);
            task.setNextRunTime(nextRun);

            scheduledTasks.put(task.getId(), task);

            scheduleTask(task);

            saveTasksToFile();

            logger.info("创建定时任务: {} at {}:{}", taskName, hour, minute);
            return task.getId();

        } catch (Exception e) {
            logger.error("创建定时任务失败: {}", e.getMessage());
            return null;
        }
    }

    public String executeCleanupNow(CleanupRequest request) {
        StringBuilder result = new StringBuilder();
        List<String> usernames = request.isAll() ? containerService.getStudentList() : request.getUsernames();

        for (String username : usernames) {
            try {
                boolean success = containerService.cleanupStudentData(username);
                result.append(username)
                      .append(": ")
                      .append(success ? "清理成功" : "清理失败")
                      .append("\n");

                addTaskRecord(username, success ? "成功" : "失败", "手动执行");

            } catch (Exception e) {
                result.append(username).append(": 清理失败 - ").append(e.getMessage()).append("\n");
                addTaskRecord(username, "失败: " + e.getMessage(), "手动执行");
            }
        }

        return result.toString();
    }

    public List<ScheduledTask> getScheduledTasks() {
        return new ArrayList<>(scheduledTasks.values());
    }

    public boolean deleteScheduledTask(String taskId) {
        ScheduledTask task = scheduledTasks.remove(taskId);
        if (task != null) {
            task.setEnabled(false);

            saveTasksToFile();
            logger.info("删除定时任务: {}", task.getName());
            return true;
        }
        return false;
    }

    public boolean toggleTask(String taskId, boolean enabled) {
        ScheduledTask task = scheduledTasks.get(taskId);
        if (task != null) {
            task.setEnabled(enabled);
            if (enabled) {

                task.setNextRunTime(calculateNextRun(task.getHour(), task.getMinute()));
                scheduleTask(task);
            }

            saveTasksToFile();
            logger.info("任务 {} 状态: {}", task.getName(), enabled ? "启用" : "禁用");
            return true;
        }
        return false;
    }

    public List<TaskRecord> getTaskHistory() {
        return new ArrayList<>(taskHistory);
    }

    private void scheduleTask(ScheduledTask task) {
        long delay = calculateDelayMillis(task.getHour(), task.getMinute());

        scheduler.scheduleAtFixedRate(() -> {
            if (task.isEnabled()) {
                executeScheduledTask(task);
            }
        }, delay, 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void executeScheduledTask(ScheduledTask task) {
        logger.info("执行定时清理任务: {}", task.getName());

        for (String username : task.getUsernames()) {
            try {
                boolean success = containerService.cleanupStudentData(username);
                addTaskRecord(username, success ? "成功" : "失败", "定时任务: " + task.getName());

            } catch (Exception e) {
                addTaskRecord(username, "失败: " + e.getMessage(), "定时任务: " + task.getName());
            }
        }

        task.setNextRunTime(calculateNextRun(task.getHour(), task.getMinute()));
    }

    private long calculateDelayMillis(int hour, int minute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute));

        if (next.isBefore(now)) {
            next = next.plusDays(1);
        }

        return java.time.Duration.between(now, next).toMillis();
    }

    private LocalDateTime calculateNextRun(int hour, int minute) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute));

        if (next.isBefore(now)) {
            next = next.plusDays(1);
        }

        return next;
    }

    private void addTaskRecord(String username, String result, String trigger) {
        TaskRecord record = new TaskRecord();
        record.setId(UUID.randomUUID().toString());
        record.setUsername(username);
        record.setResult(result);
        record.setTrigger(trigger);
        record.setExecuteTime(LocalDateTime.now());

        taskHistory.add(record);

        if (taskHistory.size() > 100) {
            taskHistory.remove(0);
        }

        saveHistoryToFile();
    }

    public void clearTaskHistory() {
        taskHistory.clear();
        saveHistoryToFile();
        logger.info("已清除所有任务执行历史");
    }

    private void saveTasksToFile() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            objectMapper.writeValue(new File(TASKS_FILE), new ArrayList<>(scheduledTasks.values()));
        } catch (IOException e) {
            logger.error("保存定时任务到文件失败: {}", e.getMessage());
        }
    }

    private void loadTasksFromFile() {
        try {
            File file = new File(TASKS_FILE);
            if (file.exists()) {
                List<ScheduledTask> tasks = objectMapper.readValue(file, new TypeReference<List<ScheduledTask>>() {});
                for (ScheduledTask task : tasks) {
                    scheduledTasks.put(task.getId(), task);
                }
            }
        } catch (IOException e) {
            logger.error("从文件加载定时任务失败: {}", e.getMessage());
        }
    }

    private void saveHistoryToFile() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }
            objectMapper.writeValue(new File(HISTORY_FILE), taskHistory);
        } catch (IOException e) {
            logger.error("保存历史记录到文件失败: {}", e.getMessage());
        }
    }

    private void loadHistoryFromFile() {
        try {
            File file = new File(HISTORY_FILE);
            if (file.exists()) {
                List<TaskRecord> history = objectMapper.readValue(file, new TypeReference<List<TaskRecord>>() {});
                taskHistory.addAll(history);
            }
        } catch (IOException e) {
            logger.error("从文件加载历史记录失败: {}", e.getMessage());
        }
    }

    public static class ScheduledTask {
        private String id;
        private String name;
        private List<String> usernames;
        private int hour;
        private int minute;
        private boolean enabled;
        private LocalDateTime createdAt;
        private LocalDateTime nextRunTime;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getUsernames() { return usernames; }
        public void setUsernames(List<String> usernames) { this.usernames = usernames; }
        public int getHour() { return hour; }
        public void setHour(int hour) { this.hour = hour; }
        public int getMinute() { return minute; }
        public void setMinute(int minute) { this.minute = minute; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getNextRunTime() { return nextRunTime; }
        public void setNextRunTime(LocalDateTime nextRunTime) { this.nextRunTime = nextRunTime; }
    }

    public static class TaskRecord {
        private String id;
        private String username;
        private String result;
        private String trigger;
        private LocalDateTime executeTime;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public String getTrigger() { return trigger; }
        public void setTrigger(String trigger) { this.trigger = trigger; }
        public LocalDateTime getExecuteTime() { return executeTime; }
        public void setExecuteTime(LocalDateTime executeTime) { this.executeTime = executeTime; }
    }
}
