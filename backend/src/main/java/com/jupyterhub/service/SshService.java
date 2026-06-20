package com.jupyterhub.service;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SSH服务 - 高性能版本（连接复用）
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

    /**
     * 获取或创建SSH会话（复用机制）
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
                } else {
                    // 会话太久没使用，断开重连
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
     */
    public String executeCommand(String command) {
        return executeCommandInternal(command, true);
    }

    /**
     * 执行SSH命令
     * @param command 命令
     * @param useSudo 是否使用sudo
     */
    public String executeCommand(String command, boolean useSudo) {
        return executeCommandInternal(command, useSudo);
    }

    /**
     * 内部方法：执行SSH命令（高性能版本）
     */
    private String executeCommandInternal(String command, boolean useSudo) {
        Session session = null;
        ChannelExec channelExec = null;

        try {
            session = getSession();

            channelExec = (ChannelExec) session.openChannel("exec");

            String execCommand;
            if (useSudo) {
                execCommand = "echo '" + password + "' | sudo -S sh -c '" + command.replace("'", "'\"'\"'") + "'";
            } else {
                execCommand = command;
            }

            channelExec.setCommand(execCommand);
            channelExec.setInputStream(null);
            channelExec.setErrStream(java.lang.System.err);

            StringBuilder result = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(channelExec.getInputStream(), StandardCharsets.UTF_8));

            channelExec.connect(5000); // 5秒连接超时

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
                    "echo '" + password + "' | sudo -S sh -c '" + command.replace("'", "'\"'\"'") + "'" : command;
                
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
                try {
                    channelExec.disconnect();
                } catch (Exception ignored) {}
            }
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

            String moveCommand = "echo '" + password + "' | sudo -S mv '" + tempPath + "' '" + remotePath + "'";

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