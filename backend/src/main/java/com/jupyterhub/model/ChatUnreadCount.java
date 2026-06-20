package com.jupyterhub.model;

import java.time.LocalDateTime;

/**
 * 未读消息计数实体类
 */
public class ChatUnreadCount {
    private Long id;
    private String username;
    private String sender;
    private Integer unreadCount;
    private Long lastMessageId;
    private LocalDateTime updatedAt;

    public ChatUnreadCount() {
    }

    public ChatUnreadCount(String username, String sender) {
        this.username = username;
        this.sender = sender;
        this.unreadCount = 0;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public Integer getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(Integer unreadCount) {
        this.unreadCount = unreadCount;
    }

    public Long getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(Long lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
