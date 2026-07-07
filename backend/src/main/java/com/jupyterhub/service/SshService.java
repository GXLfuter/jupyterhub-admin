/*
 * 作者：nailong
 * 时间：2026/6/12
 */

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

    private Session cachedSession = null;
    private long lastUsedTime = 0;
    private static final long SESSION_TIMEOUT_MS = 30000; 
    private static final long SESSION_REUSE_TIMEOUT_MS = 15000; 
    private final ReentrantLock sessionLock = new ReentrantLock();

    private final AtomicInteger executingOnCached = new AtomicInteger(0);

    private static final long LONG_COMMAND_TIMEOUT_MS = 10 * 60 * 1000L;

    private static final String[] LONG_COMMAND_KEYWORDS = {
        "docker load -i",
        "docker save -o",
    };

    private Session getSession() throws JSchException {
        sessionLock.lock();
        try {
            long now = System.currentTimeMillis();

            if (cachedSession != null && cachedSession.isConnected()) {

                if (now - lastUsedTime < SESSION_REUSE_TIMEOUT_MS) {
                    lastUsedTime = now;
                    return cachedSession;
                }

                else if (executingOnCached.get() > 0) {
                    lastUsedTime = now; 
                    return cachedSession;
                } else {

                    try {
                        cachedSession.disconnect();
                    } catch (Exception e) {

                    }
                    cachedSession = null;
                }
            }

            JSch jSch = new JSch();
            jSch.getHostKeyRepository().remove(host, null);

            Session session = jSch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password");
            session.setConfig("ServerAliveInterval", "5000");
            session.setConfig("ServerAliveCountMax", "3");
            session.setTimeout(8000); 
            session.connect();

            cachedSession = session;
            lastUsedTime = now;

            logger.debug("SSH会话已建立");
            return session;

        } finally {
            sessionLock.unlock();
        }
    }

    public String executeCommand(String command) {
        return executeCommand(command, true);
    }

    public String executeCommand(String command, boolean useSudo) {

        if (isLongRunningCommand(command)) {
            return executeLongRunningCommand(command, useSudo);
        }

        return executeCommandInternal(command, useSudo);
    }

    public String executeShortCommand(String command, boolean useSudo) {
        return executeCommandInternal(command, useSudo);
    }

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

    public String executeLongRunningCommand(String command, boolean useSudo) {
        logger.info("长命令执行 [独立Session]: {}", command);

        Session session = null;
        ChannelExec channelExec = null;

        try {

            JSch jSch = new JSch();
            jSch.getHostKeyRepository().remove(host, null);
            session = jSch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "password");
            session.setConfig("ServerAliveInterval", "30000"); 
            session.setConfig("ServerAliveCountMax", "20");   
            session.setTimeout(15000);
            session.connect();

            String execCommand;
            if (useSudo) {
                execCommand = "echo '" + password + "' | sudo -S -p '' sh -c '" + command.replace("'", "'\"'\"'") + "'";
            } else {
                execCommand = command;
            }

            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(execCommand);
            channelExec.setInputStream(null);

            InputStream stdout = channelExec.getInputStream();
            InputStream stderr = channelExec.getErrStream();

            StringBuilder result = new StringBuilder();
            channelExec.connect(5000);

            long startTime = System.currentTimeMillis();
            BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
            BufferedReader stderrReader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));

            String line;
            while (!channelExec.isClosed()) {

                while (stdoutReader.ready() && (line = stdoutReader.readLine()) != null) {
                    result.append(line).append("\n");
                }

                StringBuilder errBuf = new StringBuilder();
                while (stderrReader.ready() && (line = stderrReader.readLine()) != null) {
                    errBuf.append(line).append("\n");
                }
                if (errBuf.length() > 0) {
                    result.append("[stderr] ").append(errBuf);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > LONG_COMMAND_TIMEOUT_MS) {
                    logger.warn("长命令执行超时 ({}ms > {}ms): {}",
                            elapsed, LONG_COMMAND_TIMEOUT_MS, command);
                    break;
                }

                try {
                    Thread.sleep(500); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("长命令被中断: {}", command);
                    break;
                }
            }

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

            if (channelExec != null) {
                try { channelExec.disconnect(); } catch (Exception ignored) {}
            }
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private String executeCommandInternal(String command, boolean useSudo) {
        Session session = null;
        ChannelExec channelExec = null;

        try {

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

            executingOnCached.decrementAndGet();
        }
    }

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