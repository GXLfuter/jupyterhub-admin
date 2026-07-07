/*
 * 作者：nailong
 * 时间：2026/6/12
 */

package com.jupyterhub.controller;

import com.jupyterhub.common.Result;
import com.jupyterhub.model.ChatMessage;
import com.jupyterhub.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Value("${chat.upload.dir:./uploads/chat}")
    private String chatUploadDir;

    @GetMapping("/settings")
    public Result getChatSettings() {
        try {
            return Result.success(chatService.getChatSettings());
        } catch (Exception e) {
            logger.error("获取聊天设置失败", e);
            return Result.error("获取聊天设置失败: " + e.getMessage());
        }
    }

    @PostMapping("/settings")
    public Result updateChatSetting(@RequestBody Map<String, String> request) {
        try {
            String key = request.get("key");
            String value = request.get("value");
            if (key == null || value == null) {
                return Result.error("参数不完整");
            }
            chatService.updateChatSetting(key, value);
            return Result.success("设置更新成功");
        } catch (Exception e) {
            logger.error("更新聊天设置失败", e);
            return Result.error("更新聊天设置失败: " + e.getMessage());
        }
    }

    @PostMapping("/send")
    public Result sendMessage(@RequestBody Map<String, Object> request) {
        try {
            String sender = (String) request.get("sender");
            String content = (String) request.get("content");
            String receiver = (String) request.get("receiver");
            String messageType = (String) request.get("messageType");
            String attachments = (String) request.get("attachments");
            Boolean isGroup = request.get("isGroup") != null ? (Boolean) request.get("isGroup") : false;

            if (sender == null || sender.isEmpty()) {
                return Result.error("发送者不能为空");
            }
            if (content == null || content.trim().isEmpty()) {
                return Result.error("消息内容不能为空");
            }

            if (isGroup) {
                Map<String, String> settings = chatService.getChatSettings();
                if (!"true".equals(settings.get(ChatService.SETTING_GROUP_CHAT_ENABLED))) {
                    return Result.error("群聊功能已关闭");
                }
            }

            if (!isGroup && receiver != null) {
                Map<String, String> settings = chatService.getChatSettings();
                if (!"true".equals(settings.get(ChatService.SETTING_PRIVATE_CHAT_ENABLED))) {
                    return Result.error("私信功能已关闭");
                }
            }

            ChatMessage message = new ChatMessage();
            message.setSender(sender);
            message.setContent(content);
            message.setReceiver(receiver);
            message.setMessageType(messageType != null ? messageType : ChatService.MESSAGE_TYPE_TEXT);
            message.setAttachments(attachments);
            message.setIsGroup(isGroup);

            ChatMessage savedMessage = chatService.sendMessage(message);
            return Result.success(savedMessage);
        } catch (Exception e) {
            logger.error("发送消息失败", e);
            return Result.error("发送消息失败: " + e.getMessage());
        }
    }

    @GetMapping("/private-messages")
    public Result getPrivateMessages(
            @RequestParam String currentUser,
            @RequestParam String otherUser,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            List<ChatMessage> messages = chatService.getPrivateMessages(currentUser, otherUser, limit, offset);
            return Result.success(messages);
        } catch (Exception e) {
            logger.error("获取私信消息失败", e);
            return Result.error("获取私信消息失败: " + e.getMessage());
        }
    }

    @GetMapping("/group-messages")
    public Result getGroupMessages(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        try {
            List<ChatMessage> messages = chatService.getGroupMessages(limit, offset);
            return Result.success(messages);
        } catch (Exception e) {
            logger.error("获取群聊消息失败", e);
            return Result.error("获取群聊消息失败: " + e.getMessage());
        }
    }

    @GetMapping("/contacts")
    public Result getContacts(@RequestParam String username) {
        try {
            List<Map<String, Object>> contacts = chatService.getLatestMessages(username, 50);
            return Result.success(contacts);
        } catch (Exception e) {
            logger.error("获取联系人列表失败", e);
            return Result.error("获取联系人列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/unread-count")
    public Result getUnreadCount(@RequestParam String username) {
        try {
            Integer count = chatService.getTotalUnreadCount(username);
            return Result.success(count);
        } catch (Exception e) {
            logger.error("获取未读消息数失败", e);
            return Result.error("获取未读消息数失败: " + e.getMessage());
        }
    }

    @GetMapping("/unread-contacts")
    public Result getUnreadContacts(@RequestParam String username) {
        try {
            List<Map<String, Object>> contacts = chatService.getUnreadContacts(username);
            return Result.success(contacts);
        } catch (Exception e) {
            logger.error("获取未读联系人列表失败", e);
            return Result.error("获取未读联系人列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/mark-read")
    public Result markAsRead(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String sender = request.get("sender");
            if (username == null || sender == null) {
                return Result.error("参数不完整");
            }
            chatService.markAsRead(username, sender);
            return Result.success();
        } catch (Exception e) {
            logger.error("标记已读失败", e);
            return Result.error("标记已读失败: " + e.getMessage());
        }
    }

    @GetMapping("/students")
    public Result getAllStudents() {
        try {
            List<Map<String, Object>> students = chatService.getAllStudents();
            return Result.success(students);
        } catch (Exception e) {
            logger.error("获取学生列表失败", e);
            return Result.error("获取学生列表失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/clear-one")
    public Result clearChatHistory(@RequestParam String username, @RequestParam String targetUser) {
        try {
            int count = chatService.clearChatHistory(username, targetUser);
            return Result.success(count);
        } catch (Exception e) {
            logger.error("清除聊天记录失败", e);
            return Result.error("清除聊天记录失败: " + e.getMessage());
        }
    }

    @GetMapping("/hand-raise")
    public Result getHandRaiseMessages() {
        try {
            List<ChatMessage> messages = chatService.getHandRaiseMessages();
            return Result.success(messages);
        } catch (Exception e) {
            logger.error("获取举手消息失败", e);
            return Result.error("获取举手消息失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/clear")
    public Result clearMessages(@RequestParam String type) {
        try {
            if (!"all".equals(type) && !"group".equals(type)) {
                return Result.error("无效的类型");
            }
            chatService.clearChatMessages(type);
            return Result.success("聊天记录已清空");
        } catch (Exception e) {
            logger.error("清空聊天记录失败", e);
            return Result.error("清空聊天记录失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-image")
    public Result uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return Result.error("请选择要上传的图片");
            }

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return Result.error("只能上传图片文件");
            }

            if (file.getSize() > 20 * 1024 * 1024) {
                return Result.error("图片大小不能超过 20MB");
            }

            String dateDir = new SimpleDateFormat("yyyyMMdd").format(new Date());
            File dir = new File(chatUploadDir, dateDir);
            if (!dir.exists() && !dir.mkdirs()) {
                return Result.error("无法创建上传目录");
            }

            String original = file.getOriginalFilename();
            String ext = "";
            if (original != null && original.contains(".")) {
                ext = original.substring(original.lastIndexOf(".") + 1).toLowerCase();
            }

            boolean compressible = isCompressible(ext, contentType);
            String saveExt = compressible ? "jpg" : (ext.isEmpty() ? "jpg" : ext);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "." + saveExt;
            File dest = new File(dir, fileName);

            if (compressible) {
                boolean ok = compressImage(file, dest, 1920, 0.85f);
                if (!ok) {

                    file.transferTo(dest);
                }
            } else {
                file.transferTo(dest);
            }

            String url = "/api/chat/image/" + dateDir + "/" + fileName;

            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("name", fileName);
            return Result.success(data);
        } catch (Exception e) {
            logger.error("聊天图片上传失败", e);
            return Result.error("上传失败: " + e.getMessage());
        }
    }

    private boolean isCompressible(String ext, String contentType) {
        if (ext != null) {
            if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                    || "bmp".equals(ext) || "webp".equals(ext)) {
                return true;
            }
            if ("gif".equals(ext)) {
                return false;
            }
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            return ct.contains("jpeg") || ct.contains("jpg") || ct.contains("png")
                    || ct.contains("bmp") || ct.contains("webp");
        }
        return true;
    }

    private boolean compressImage(MultipartFile file, File dest, int maxEdge, float quality) {
        BufferedImage src = null;
        try (java.io.InputStream is = file.getInputStream()) {
            src = ImageIO.read(is);
        } catch (IOException ignore) {
            return false;
        }
        if (src == null) {
            return false;
        }
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            return false;
        }

        int dstW = srcW;
        int dstH = srcH;
        if (srcW > maxEdge || srcH > maxEdge) {
            if (srcW >= srcH) {
                dstW = maxEdge;
                dstH = (int) Math.round((double) srcH * maxEdge / srcW);
            } else {
                dstH = maxEdge;
                dstW = (int) Math.round((double) srcW * maxEdge / srcH);
            }
        }

        BufferedImage out;
        if (dstW == srcW && dstH == srcH) {

            out = new BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, srcW, srcH);
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
        } else {
            out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, dstW, dstH);
                g.drawImage(src, 0, 0, dstW, dstH, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        try (OutputStream os = Files.newOutputStream(dest.toPath());
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
        } catch (IOException e) {
            logger.error("聊天图片压缩失败", e);
            return false;
        } finally {
            writer.dispose();
        }
        return true;
    }

    @GetMapping("/image/{dateDir}/{fileName}")
    public void getImage(@PathVariable String dateDir, @PathVariable String fileName,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        File file = new File(chatUploadDir, dateDir + File.separator + fileName);
        if (!file.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) ext = fileName.substring(dot + 1).toLowerCase();
        String contentType = "image/jpeg";
        if ("png".equals(ext)) contentType = "image/png";
        else if ("gif".equals(ext)) contentType = "image/gif";
        else if ("webp".equals(ext)) contentType = "image/webp";
        response.setContentType(contentType);
        response.setHeader("Cache-Control", "max-age=86400");
        Files.copy(file.toPath(), response.getOutputStream());
    }

    @PostMapping("/avatar/upload")
    public Result uploadAvatar(@RequestParam("file") MultipartFile file, @RequestParam("username") String username) {
        try {
            if (file == null || file.isEmpty()) {
                return Result.error("请选择要上传的图片");
            }
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return Result.error("只能上传图片文件");
            }
            if (file.getSize() > 20 * 1024 * 1024) {
                return Result.error("图片大小不能超过 20MB");
            }

            File dir = new File(chatUploadDir, "avatars");
            if (!dir.exists() && !dir.mkdirs()) {
                return Result.error("无法创建上传目录");
            }

            String fileName = username + ".jpg";
            File dest = new File(dir, fileName);

            boolean ok = compressImage(file, dest, 256, 0.9f);
            if (!ok) {
                file.transferTo(dest);
            }

            String url = "/api/chat/avatar/" + username;
            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            return Result.success(data);
        } catch (Exception e) {
            logger.error("头像上传失败", e);
            return Result.error("上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/avatar/{username}")
    public void getAvatar(@PathVariable String username, HttpServletResponse response) throws IOException {
        File file = new File(chatUploadDir, "avatars" + File.separator + username + ".jpg");
        if (!file.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType("image/jpeg");
        response.setHeader("Cache-Control", "max-age=86400");
        Files.copy(file.toPath(), response.getOutputStream());
    }
}
