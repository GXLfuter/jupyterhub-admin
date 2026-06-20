package com.jupyterhub.service;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH服务 - 高性能版本（连接复用 + 长命令独立会话）
 * 
 * 关键设计：
 * 1. 短命令使用 cachedSession（复用，减少连接开销）
 * 2. 长命令（如 docker load 加载 24GB 镜像）使用独立新建的 session
 *    避免 cachedSession 的 15秒 超时逻辑把正在执行的长命令断开
 * 3. executingSessionCount 标记有多少线程正在使用 cachedSession
 *    有线程在执行时，其他线程不应断开 cachedSession
 */
@Service
public class SshService {

    private static final Logger logger = LoggerFactory.getLogger(SshService.class);

    @Value("${jupyterhub.host}")
    private String host;

    @Value("${jupyterhub.port}")
    private int port;

    @Value("${jupyterhub.username}")
    private String username;

    @Value("${jupyterhub.password}")
    private String password;

    // 缓存的SSH会话
    private Session cachedSession = null;
    private long lastUsedTime = 0;
    private static final long SESSION_TIMEOUT_MS = 30000; // 30秒超时
    private static final long SESSION_REUSE_TIMEOUT_MS = 15000; // 15秒内复用
    private final ReentrantLock sessionLock = new ReentrantLock();

    // 关键修复：有多少线程正在使用 cachedSession 执行命令
    // > 0 时，getSession() 不应该断开 cachedSession
    private final AtomicInteger executingOnCached = new AtomicInteger(0);

    // 长命令最大执行时间（10 分钟）
    private static final long LONG_COMMAND_TIMEOUT_MS = 10 * 60 * 1000L;

    // 长命令定义：只匹配真正可能跑几分钟的命令
    // 注意：必须非常精确，否则 tar/ls/grep 等毫秒级命令也会走独立 session，造成 SSH 风暴
    // "docker load -i" 是唯一真正长的恢复命令
    // "docker save -o" 是唯一真正长的备份命令
    // 其他所有命令（包括 tar -xOf 读取 manifest、docker info、docker images、ps aux 等）都是短命令
    private static final String[] LONG_COMMAND_KEYWORDS = {
        "docker load -i",
        "docker save -o",
    };

    /**
     * 获取或创建SSH会话（复用机制）
     * 
     * 关键修复：有线程正在使用 cachedSession 执行命令时，
     *          其他线程应等待，而不应粗暴地断开 session。
     *          否则正在执行的 docker load 会变成孤儿进程继续在服务器上运行！
     */
    private Session getSession() throws JSchException {
        sessionLock.lock();
        try {
            long now = System.currentTimeMillis();
            
            // 如果缓存的会话存在且有效，直接复用
            if (cachedSession != null && cachedSession.isConnected()) {
                // 检查会话是否超时未使用
                if (now - lastUsedTime < SESSION_REUSE_TIMEOUT_MS) {
                    lastUsedTime = now;
                    return cachedSession;
                }
                // ==== 关键修复 ====
                // 如果有线程正在使用 cachedSession 执行命令（executingOnCached > 0）
                // 即使 lastUsedTime 超过了15秒，也不要断开它！
                // 正在执行的 docker load 进程还在等着这个 session！
                else if (executingOnCached.get() > 0) {
                    lastUsedTime = now; // 延长使用时间
                    return cachedSession;
                } else {
                    // 没有线程在使用，可以安全断开重连
                    try {
                        cachedSession.disconnect();
                    } catch (Exception e) {
                        // 忽略断开异常
                    }
                    cachedSession = null;
                }
            }

            // 创建新会话
            JSch jSch = new JSch();
            jSch.getHostKeyRepository().remove(host, null);
            
            Session session = jSch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password");
            session.setConfig("ServerAliveInterval", "5000");
            session.setConfig("ServerAliveCountMax", "3");
            session.setTimeout(8000); // 缩短超时时间
            session.connect();
            
            cachedSession = session;
            lastUsedTime = now;
            
            logger.debug("SSH会话已建立");
            return session;
            
        } finally {
            sessionLock.unlock();
        }
    }

    /**
     * 执行SSH命令（使用sudo）
     * 
     * 自动检测命令类型：如果是 docker load/tar 等长命令，会自动走独立 session 路径
     */
    public String executeCommand(String command) {
        return executeCommand(command, true);
    }

    /**
     * 执行SSH命令
     * @param command 命令
     * @param useSudo 是否使用sudo
     * 
     * 智能路由：检测命令是否是长命令（如 docker load -i）
     * 如果是，走独立 session 路径；否则走 cachedSession 路径
     */
    public String executeCommand(String command, boolean useSudo) {
        // ==== 关键：检测是否是长命令（如 docker load -i）
        // 如果是，走独立 session 路径，避免与 cachedSession 的15秒超时冲突
        if (isLongRunningCommand(command)) {
            return executeLongRunningCommand(command, useSudo);
        }
        // 短命令走原来的 cachedSession 路径
        return executeCommandInternal(command, useSudo);
    }

    /**
     * 【显式短命令】明确走 cachedSession 路径，完全绕过长命令判断
     * 适用于：docker info、docker images、tar -xOf、ps aux、ls、stat 等毫秒级命令
     * 
     * @param command 命令
     * @param useSudo 是否使用sudo
     * @return 命令输出
     */
    public String executeShortCommand(String command, boolean useSudo) {
        return executeCommandInternal(command, useSudo);
    }

    /**
     * 检测命令是否是长运行命令（可能超过15秒）
     */
    private boolean isLongRunningCommand(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();
        for (String keyword : LONG_COMMAND_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【长命令专用】为 docker load -i / docker save -o 等长命令创建独立的 session
     * 不使用 cachedSession，避免 15秒 超时逻辑把正在执行的长命令断开
     * 也不会被其他线程 getSession() 时粗暴断开
     * 
     * 最大执行时间：10 分钟
     */
    public String executeLongRunningCommand(String command, boolean useSudo) {
        logger.info("长命令执行 [独立Session]: {}", command);

        Session session = null;
        ChannelExec channelExec = null;

        try {
            // ==== 为这个长命令创建全新的 session（不使用缓存）
            JSch jSch = new JSch();
            jSch.getHostKeyRepository().remove(host, null);
            session = jSch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password");
            session.setConfig("ServerAliveInterval", "30000"); // 30秒保活
            session.setConfig("ServerAliveCountMax", "20");   // 允许20次保活，约10分钟
            session.setTimeout(15000);
            session.connect();

            // 构造执行命令
            // 关键：用 sudo -S -p '' 抑制密码提示（否则 "[sudo] password for user:" 会污染 stderr 输出）
            String execCommand;
            if (useSudo) {
                execCommand = "echo '" + password + "' | sudo -S -p '' sh -c '" + command.replace("'", "'\"'\"'") + "'";
            } else {
                execCommand = command;
            }

            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(execCommand);
            channelExec.setInputStream(null);

            // 同时读取 stdout 和 stderr
            InputStream stdout = channelExec.getInputStream();
            InputStream stderr = channelExec.getErrStream();

            StringBuilder result = new StringBuilder();
            channelExec.connect(5000);

            // ==== 执行长命令：带超时机制，最多等待 10 分钟
            long startTime = System.currentTimeMillis();
            BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
            BufferedReader stderrReader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));

            String line;
            while (!channelExec.isClosed()) {
                // 读取 stdout
                while (stdoutReader.ready() && (line = stdoutReader.readLine()) != null) {
                    result.append(line).append("\n");
                }
                // 读取 stderr（可能包含有用的错误信息）
                StringBuilder errBuf = new StringBuilder();
                while (stderrReader.ready() && (line = stderrReader.readLine()) != null) {
                    errBuf.append(line).append("\n");
                }
                if (errBuf.length() > 0) {
                    result.append("[stderr] ").append(errBuf);
                }

                // 超时检测：最长 10 分钟
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > LONG_COMMAND_TIMEOUT_MS) {
                    logger.warn("长命令执行超时 ({}ms > {}ms): {}",
                            elapsed, LONG_COMMAND_TIMEOUT_MS, command);
                    break;
                }

                try {
                    Thread.sleep(500); // 长命令等待时间稍长
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("长命令被中断: {}", command);
                    break;
                }
            }

            // channel 关闭后读取剩余输出
            while (stdoutReader.ready() && (line = stdoutReader.readLine()) != null) {
                result.append(line).append("\n");
            }
            while (stderrReader.ready() && (line = stderrReader.readLine()) != null) {
                result.append("[stderr] ").append(line).append("\n");
            }

            String output = result.toString().trim();
            logger.info("长命令执行完成，耗时 {}ms，输出长度 {}",
                    System.currentTimeMillis() - startTime, output.length());
            return output;

        } catch (Exception e) {
            logger.error("长命令执行失败 [{}]: {} - {}", command, e.getClass().getSimpleName(), e.getMessage());
            return "ERROR: " + e.getMessage();
        } finally {
            // ==== 长命令执行完后，独立断开 session（不走缓存逻辑）
            if (channelExec != null) {
                try { channelExec.disconnect(); } catch (Exception ignored) {}
            }
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 内部方法：执行SSH命令（短命令版本，走 cachedSession）
     */
    private String executeCommandInternal(String command, boolean useSudo) {
        Session session = null;
        ChannelExec channelExec = null;

        try {
            // 标记：这个线程要开始使用 cachedSession 执行了
            executingOnCached.incrementAndGet();

            session = getSession();

            channelExec = (ChannelExec) session.openChannel("exec");

            String execCommand;
            if (useSudo) {
                execCommand = "echo '" + password + "' | sudo -S -p '' sh -c '" + command.replace("'", "'\"'\"'") + "'";
            } else {
                execCommand = command;
            }

            channelExec.setCommand(execCommand);
            channelExec.setInputStream(null);
            channelExec.setErrStream(java.lang.System.err);

            StringBuilder result = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(channelExec.getInputStream(), StandardCharsets.UTF_8));

            channelExec.connect(5000);

            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            // 等待命令执行完成
            while (!channelExec.isClosed()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            String output = result.toString().trim();
            return output;

        } catch (JSchException e) {
            logger.warn("SSH连接异常，尝试重新连接: {}", e.getMessage());
            // 重置缓存会话，下次请求会重新建立
            sessionLock.lock();
            try {
                if (cachedSession != null) {
                    try {
                        cachedSession.disconnect();
                    } catch (Exception ignored) {}
                    cachedSession = null;
                }
            } finally {
                sessionLock.unlock();
            }
            // 尝试重新执行一次
            try {
                session = getSession();
                channelExec = (ChannelExec) session.openChannel("exec");
                
                String execCommand = useSudo ? 
                    "echo '" + password + "' | sudo -S -p '' sh -c '" + command.replace("'", "'\"'\"'") + "'" : command;
                
                channelExec.setCommand(execCommand);
                channelExec.setInputStream(null);
                
                StringBuilder result = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(channelExec.getInputStream(), StandardCharsets.UTF_8));
                channelExec.connect(5000);
                
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append("\n");
                }
                
                while (!channelExec.isClosed()) {
                    Thread.sleep(100);
                }
                
                return result.toString().trim();
            } catch (Exception e2) {
                logger.error("SSH命令执行失败: {} - {}", e2.getClass().getSimpleName(), e2.getMessage());
                return "ERROR: " + e2.getMessage();
            }
        } catch (Exception e) {
            logger.error("SSH命令执行失败: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return "ERROR: " + e.getMessage();
        } finally {
            if (channelExec != null) {
                try { channelExec.disconnect(); } catch (Exception ignored) {}
            }
            // 关键：标记执行完成
            executingOnCached.decrementAndGet();
        }
    }

    /**
     * 测试SSH连接
     */
    public boolean testConnection() {
        Session session = null;

        try {
            session = getSession();
            logger.info("SSH连接测试成功");
            return true;

        } catch (Exception e) {
            logger.error("SSH连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 上传文件到服务器（通过SFTP，然后移动到目标目录）
     */
    public boolean uploadFile(String localFilePath, String remotePath) {
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            session = getSession();

            Channel channel = session.openChannel("sftp");
            channel.connect(5000);
            sftpChannel = (ChannelSftp) channel;

            String filename = remotePath.substring(remotePath.lastIndexOf("/") + 1);
            String tempPath = "/home/huawei/" + filename;

            sftpChannel.put(localFilePath, tempPath);

            String moveCommand = "echo '" + password + "' | sudo -S -p '' mv '" + tempPath + "' '" + remotePath + "'";

            ChannelExec execChannel = (ChannelExec) session.openChannel("exec");
            execChannel.setCommand(moveCommand);
            execChannel.connect(5000);
            execChannel.disconnect();

            logger.info("文件上传成功: {}", remotePath);
            return true;

        } catch (Exception e) {
            logger.error("文件上传失败: {}", e.getMessage());
            return false;
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
        }
    }

    /**
     * 上传文件到远程临时目录（纯SFTP，不需要sudo）
     */
    public boolean uploadFileToTemp(String localFilePath, String remoteTempPath) {
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            session = getSession();

            Channel channel = session.openChannel("sftp");
            channel.connect(5000);
            sftpChannel = (ChannelSftp) channel;

            sftpChannel.put(localFilePath, remoteTempPath);
            logger.info("SFTP上传成功: {}", remoteTempPath);
            return true;

        } catch (Exception e) {
            logger.error("SFTP上传失败: {}", e.getMessage());
            return false;
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
        }
    }

    /**
     * 下载文件（通过SFTP）
     */
    public boolean downloadFile(String remoteFilePath, String localFilePath) {
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            session = getSession();

            Channel channel = session.openChannel("sftp");
            channel.connect(5000);
            sftpChannel = (ChannelSftp) channel;

            sftpChannel.get(remoteFilePath, localFilePath);
            return true;

        } catch (Exception e) {
            logger.error("文件下载失败: {}", e.getMessage());
            return false;
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
        }
    }

    /**
     * 关闭缓存的SSH会话
     */
    public void closeSession() {
        sessionLock.lock();
        try {
            if (cachedSession != null && cachedSession.isConnected()) {
                cachedSession.disconnect();
                cachedSession = null;
                logger.debug("SSH会话已关闭");
            }
        } catch (Exception e) {
            logger.warn("关闭SSH会话时出错: {}", e.getMessage());
        } finally {
            sessionLock.unlock();
        }
    }
}