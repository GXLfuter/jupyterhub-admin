/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public class ChatMessage {
    private Long id;
    private String sender;
    private String receiver;
    private String content;
    private String messageType;
    private String attachments;

    @JsonProperty("isGroup")
    private Boolean isGroup;

    private Boolean isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;

    public ChatMessage() {
    }

    public ChatMessage(String sender, String content, String messageType) {
        this.sender = sender;
        this.content = content;
        this.messageType = messageType;
        this.isGroup = false;
        this.isRead = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public Boolean getIsGroup() {
        return isGroup;
    }

    public void setIsGroup(Boolean isGroup) {
        this.isGroup = isGroup;
    }

    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }
}
