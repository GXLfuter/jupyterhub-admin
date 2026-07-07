/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.service;

import com.jupyterhub.model.ChatMessage;
import com.jupyterhub.model.ChatSettings;
import com.jupyterhub.model.ChatUnreadCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static final String MESSAGE_TYPE_TEXT = "TEXT";
    public static final String MESSAGE_TYPE_HAND_RAISE = "HAND_RAISE";

    public static final String SETTING_GROUP_CHAT_ENABLED = "group_chat_enabled";
    public static final String SETTING_PRIVATE_CHAT_ENABLED = "private_chat_enabled";

    public ChatMessage sendMessage(ChatMessage message) {
        String sql = "INSERT INTO chat_message (sender, receiver, content, message_type, attachments, is_group, is_read, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())";
        jdbcTemplate.update(sql,
            message.getSender(),
            message.getReceiver(),
            message.getContent(),
            message.getMessageType() != null ? message.getMessageType() : MESSAGE_TYPE_TEXT,
            message.getAttachments(),
            message.getIsGroup() != null && message.getIsGroup() ? 1 : 0,
            0
        );

        Long messageId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        updateUnreadCount(message);

        message.setId(messageId);
        message.setCreatedAt(LocalDateTime.now());

        try {
            if (message.getIsGroup() != null && message.getIsGroup()) {
                com.jupyterhub.handler.ChatWebSocketHandler.broadcastMessage(message);
            } else {
                com.jupyterhub.handler.ChatWebSocketHandler.sendMessageToUser(message.getReceiver(), message);
                com.jupyterhub.handler.ChatWebSocketHandler.sendMessageToUser("admin", message);
                com.jupyterhub.handler.ChatWebSocketHandler.sendMessageToUser(message.getSender(), message);
            }
        } catch (Exception e) {
            logger.error("WebSocket推送消息失败", e);
        }

        return message;
    }

    private void updateUnreadCount(ChatMessage message) {
        if (message.getIsGroup() != null && message.getIsGroup()) {
            String sql = "INSERT INTO chat_unread_count (username, sender, unread_count, last_message_id, updated_at) " +
                        "VALUES (?, ?, 1, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE unread_count = unread_count + 1, last_message_id = VALUES(last_message_id), updated_at = NOW()";

            String sender = message.getSender();

            if (!sender.startsWith("admin")) {
                jdbcTemplate.update(sql, "admin", "群聊", message.getId());
            }

            for (int i = 1; i <= 60; i++) {
                String studentName = "student" + i;
                if (!sender.equals(studentName) && !sender.startsWith(studentName)) {
                    jdbcTemplate.update(sql, studentName, "群聊", message.getId());
                }
            }
        } else {
            if (message.getReceiver() != null) {
                String sql = "INSERT INTO chat_unread_count (username, sender, unread_count, last_message_id, updated_at) " +
                            "VALUES (?, ?, 1, ?, NOW()) " +
                            "ON DUPLICATE KEY UPDATE unread_count = unread_count + 1, last_message_id = VALUES(last_message_id), updated_at = NOW()";
                jdbcTemplate.update(sql, message.getReceiver(), message.getSender(), message.getId());
            }
        }
    }

    public List<ChatMessage> getPrivateMessages(String currentUser, String otherUser, int limit, int offset) {
        String sql = "SELECT * FROM chat_message " +
                    "WHERE is_group = 0 AND (" +
                    "  (sender LIKE ? AND receiver LIKE ?) " +
                    "  OR (sender LIKE ? AND receiver LIKE ?) " +
                    ") " +
                    "ORDER BY created_at DESC " +
                    "LIMIT ? OFFSET ?";
        List<ChatMessage> messages = jdbcTemplate.query(sql,
            new Object[]{currentUser + "%", otherUser + "%",
                         otherUser + "%", currentUser + "%",
                         limit, offset},
            new ChatMessageRowMapper()
        );
        Collections.reverse(messages);
        return messages;
    }

    public List<ChatMessage> getGroupMessages(int limit, int offset) {
        String sql = "SELECT * FROM chat_message " +
                    "WHERE is_group = 1 " +
                    "ORDER BY created_at DESC " +
                    "LIMIT ? OFFSET ?";
        List<ChatMessage> messages = jdbcTemplate.query(sql,
            new Object[]{limit, offset},
            new ChatMessageRowMapper()
        );
        Collections.reverse(messages);
        return messages;
    }

    public List<Map<String, Object>> getLatestMessages(String username, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        String sql = "SELECT " +
                    "  CASE WHEN sender = ? OR sender LIKE ? THEN receiver ELSE sender END as other_user, " +
                    "  MAX(id) as last_msg_id, MAX(created_at) as last_time " +
                    "FROM chat_message " +
                    "WHERE ((sender = ? OR sender LIKE ?) OR (receiver = ? OR receiver LIKE ?)) " +
                    "  AND is_group = 0 " +
                    "GROUP BY CASE WHEN sender = ? OR sender LIKE ? THEN receiver ELSE sender END " +
                    "ORDER BY last_time DESC";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql,
            username, username + "-%",
            username, username + "-%",
            username, username + "-%",
            username, username + "-%");

        Set<String> existingUsers = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String otherUser = (String) row.get("other_user");
            if (otherUser == null || existingUsers.contains(otherUser)) {
                continue;
            }
            Object lastMsgIdObj = row.get("last_msg_id");
            if (lastMsgIdObj == null) {
                continue;
            }
            Long lastMsgId = ((Number) lastMsgIdObj).longValue();

            ChatMessage lastMessage = getMessageById(lastMsgId);
            Integer unreadCount = getUnreadCount(username, otherUser);

            Map<String, Object> item = new HashMap<>();
            item.put("otherUser", otherUser);
            item.put("lastMessage", lastMessage);
            item.put("unreadCount", unreadCount);
            item.put("lastTime", row.get("last_time"));
            result.add(item);
            existingUsers.add(otherUser);
        }

        String groupSql = "SELECT MAX(id) as last_msg_id, MAX(created_at) as last_time FROM chat_message WHERE is_group = 1";
        try {
            Map<String, Object> groupRow = jdbcTemplate.queryForMap(groupSql);
            Object groupLastMsgId = groupRow.get("last_msg_id");
            if (groupLastMsgId != null) {
                Long lastMsgId = ((Number) groupLastMsgId).longValue();
                ChatMessage lastMessage = getMessageById(lastMsgId);
                Integer unreadCount = getUnreadCount(username, "群聊");

                Map<String, Object> groupItem = new HashMap<>();
                groupItem.put("otherUser", "群聊");
                groupItem.put("lastMessage", lastMessage);
                groupItem.put("unreadCount", unreadCount);
                groupItem.put("lastTime", groupRow.get("last_time"));
                result.add(groupItem);
            }
        } catch (Exception e) {
            logger.warn("获取群聊信息失败", e);
        }

        return result;
    }

    private ChatMessage getMessageById(Long id) {
        String sql = "SELECT * FROM chat_message WHERE id = ?";
        List<ChatMessage> messages = jdbcTemplate.query(sql, new Object[]{id}, new ChatMessageRowMapper());
        return messages.isEmpty() ? null : messages.get(0);
    }

    public int clearChatHistory(String username, String targetUser) {
        int count;
        if ("群聊".equals(targetUser)) {
            String sql = "DELETE FROM chat_message WHERE is_group = 1";
            count = jdbcTemplate.update(sql);
        } else {
            String sql = "DELETE FROM chat_message WHERE is_group = 0 AND " +
                        "((sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) OR " +
                        "(sender = ? AND receiver LIKE ?) OR (sender LIKE ? AND receiver = ?) OR " +
                        "(sender = ? AND receiver LIKE ?) OR (sender LIKE ? AND receiver = ?))";
            count = jdbcTemplate.update(sql,
                username, targetUser, targetUser, username,
                username, targetUser + "-%", targetUser + "-%", username,
                targetUser, username + "-%", username + "-%", targetUser);
        }

        try {
            com.jupyterhub.handler.ChatWebSocketHandler.broadcastClearEvent("one", targetUser);
        } catch (Exception e) {
            logger.error("广播清空事件失败", e);
        }

        return count;
    }

    public Integer getUnreadCount(String username, String sender) {
        String sql = "SELECT COALESCE(SUM(unread_count), 0) FROM chat_unread_count " +
                    "WHERE (username = ? OR username LIKE ?) AND sender = ?";
        try {
            return jdbcTemplate.queryForObject(sql, Integer.class, username, username + "-%", sender);
        } catch (Exception e) {
            return 0;
        }
    }

    public Integer getTotalUnreadCount(String username) {
        String sql = "SELECT COALESCE(SUM(unread_count), 0) FROM chat_unread_count WHERE username = ? OR username LIKE ?";
        return jdbcTemplate.queryForObject(sql, Integer.class, username, username + "-%");
    }

    public void markAsRead(String username, String sender) {
        if ("群聊".equals(sender)) {
            String deleteSql = "DELETE FROM chat_unread_count WHERE username = ? OR username LIKE ?";
            jdbcTemplate.update(deleteSql, username, username + "-%");

            String updateSql = "UPDATE chat_message SET is_read = 1, read_at = NOW() " +
                              "WHERE is_group = 1 AND is_read = 0";
            jdbcTemplate.update(updateSql);
        } else {
            String deleteSql = "DELETE FROM chat_unread_count WHERE (username = ? OR username LIKE ?) AND sender = ?";
            jdbcTemplate.update(deleteSql, username, username + "-%", sender);

            String updateSql = "UPDATE chat_message SET is_read = 1, read_at = NOW() " +
                              "WHERE (sender = ? OR sender LIKE ?) AND (receiver = ? OR receiver LIKE ?) AND is_read = 0";
            jdbcTemplate.update(updateSql, sender, sender + "-%", username, username + "-%");
        }
    }

    public List<Map<String, Object>> getUnreadContacts(String username) {
        String sql = "SELECT sender, unread_count FROM chat_unread_count WHERE username = ? OR username LIKE ? ORDER BY updated_at DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, username, username + "-%");

        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("sender", row.get("sender"));
            item.put("unreadCount", row.get("unread_count"));
            result.add(item);
        }

        return result;
    }

    public List<Map<String, Object>> getAllStudents() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            String studentName = "student" + i;
            Map<String, Object> item = new HashMap<>();
            item.put("username", studentName);
            item.put("unreadCount", getTotalUnreadCount(studentName));

            List<Map<String, Object>> latestMessages = getLatestMessages(studentName, 1);
            if (!latestMessages.isEmpty()) {
                item.put("lastMessage", latestMessages.get(0).get("lastMessage"));
                item.put("lastTime", latestMessages.get(0).get("lastTime"));
            }

            result.add(item);
        }
        return result;
    }

    public List<ChatMessage> getHandRaiseMessages() {
        String sql = "SELECT * FROM chat_message WHERE message_type = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, new Object[]{MESSAGE_TYPE_HAND_RAISE}, new ChatMessageRowMapper());
    }

    public Map<String, String> getChatSettings() {
        String sql = "SELECT setting_key, setting_value FROM chat_settings";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        Map<String, String> settings = new HashMap<>();
        for (Map<String, Object> row : rows) {
            settings.put((String) row.get("setting_key"), (String) row.get("setting_value"));
        }
        return settings;
    }

    public void updateChatSetting(String key, String value) {
        String sql = "UPDATE chat_settings SET setting_value = ? WHERE setting_key = ?";
        jdbcTemplate.update(sql, value, key);
    }

    public void clearChatMessages(String type) {
        if ("all".equals(type)) {
            jdbcTemplate.update("DELETE FROM chat_message");
            jdbcTemplate.update("DELETE FROM chat_unread_count");
        } else if ("group".equals(type)) {
            jdbcTemplate.update("DELETE FROM chat_message WHERE is_group = 1");
            jdbcTemplate.update("DELETE FROM chat_unread_count");
        }

        try {
            com.jupyterhub.handler.ChatWebSocketHandler.broadcastClearEvent(type, null);
        } catch (Exception e) {
            logger.error("广播清空事件失败", e);
        }
    }

    private static class ChatMessageRowMapper implements RowMapper<ChatMessage> {
        @Override
        public ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            ChatMessage message = new ChatMessage();
            message.setId(rs.getLong("id"));
            message.setSender(rs.getString("sender"));
            message.setReceiver(rs.getString("receiver"));
            message.setContent(rs.getString("content"));
            message.setMessageType(rs.getString("message_type"));
            message.setAttachments(rs.getString("attachments"));
            message.setIsGroup(rs.getInt("is_group") == 1);
            message.setIsRead(rs.getInt("is_read") == 1);
            message.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            if (rs.getTimestamp("read_at") != null) {
                message.setReadAt(rs.getTimestamp("read_at").toLocalDateTime());
            }
            return message;
        }
    }
}
