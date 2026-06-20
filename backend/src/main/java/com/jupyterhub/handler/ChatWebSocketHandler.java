package com.jupyterhub.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupyterhub.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = getUsernameFromSession(session);
        sessions.put(username, session);
        logger.info("WebSocket connection established for user: {}", username);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String username = getUsernameFromSession(session);
        sessions.remove(username);
        logger.info("WebSocket connection closed for user: {}", username);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Client sends messages via REST API, WebSocket is only for pushing
    }

    public static void broadcastMessage(ChatMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            logger.error("Failed to serialize message", e);
            return;
        }

        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            String username = entry.getKey();
            WebSocketSession session = entry.getValue();

            // 排除发送者自己
            if (message.getSender() != null && message.getSender().equals(username)) {
                continue;
            }

            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    logger.error("Failed to send message to user: {}", username, e);
                }
            }
        }
    }

    public static void sendMessageToUser(String username, ChatMessage message) {
        WebSocketSession session = sessions.get(username);
        if (session != null && session.isOpen()) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                logger.error("Failed to send message to user: {}", username, e);
            }
        }
    }

    public static void broadcastClearEvent(String type, String targetUser) {
        Map<String, Object> event = new ConcurrentHashMap<>();
        event.put("type", "CLEAR");
        event.put("clearType", type);
        event.put("targetUser", targetUser);

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            logger.error("Failed to serialize clear event", e);
            return;
        }

        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    logger.error("Failed to send clear event to user: {}", entry.getKey(), e);
                }
            }
        }
    }

    private String getUsernameFromSession(WebSocketSession session) {
        String uri = session.getUri().toString();
        int idx = uri.indexOf("?username=");
        if (idx > 0) {
            return uri.substring(idx + 10);
        }
        return "anonymous";
    }
}
