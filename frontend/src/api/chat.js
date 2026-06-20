import api from './axios'

export const chatApi = {
  // 获取聊天设置
  getSettings() {
    return api.get('/chat/settings')
  },

  // 更新聊天设置
  updateSetting(key, value) {
    return api.post('/chat/settings', { key, value })
  },

  // 发送消息
  sendMessage(sender, content, receiver, messageType, attachments, isGroup) {
    return api.post('/chat/send', {
      sender,
      content,
      receiver,
      messageType,
      attachments,
      isGroup
    })
  },

  // 获取私信消息
  getPrivateMessages(currentUser, otherUser, limit = 50, offset = 0) {
    return api.get('/chat/private-messages', {
      params: { currentUser, otherUser, limit, offset }
    })
  },

  // 获取群聊消息
  getGroupMessages(limit = 50, offset = 0) {
    return api.get('/chat/group-messages', {
      params: { limit, offset }
    })
  },

  // 获取联系人列表
  getContacts(username) {
    return api.get('/chat/contacts', {
      params: { username }
    })
  },

  // 获取未读消息数
  getUnreadCount(username) {
    return api.get('/chat/unread-count', {
      params: { username }
    })
  },

  // 获取未读联系人列表
  getUnreadContacts(username) {
    return api.get('/chat/unread-contacts', {
      params: { username }
    })
  },

  // 标记消息已读
  markAsRead(username, sender) {
    return api.post('/chat/mark-read', { username, sender })
  },

  // 清除指定聊天记录
  clearChatHistory(username, targetUser) {
    return api.delete(`/chat/clear-one?username=${encodeURIComponent(username)}&targetUser=${encodeURIComponent(targetUser)}`)
  },

  // 获取所有学生列表（管理员用）
  getAllStudents() {
    return api.get('/chat/students')
  },

  // 获取举手消息（管理员用）
  getHandRaiseMessages() {
    return api.get('/chat/hand-raise')
  },

  // 清空聊天记录
  clearMessages(type) {
    return api.delete('/chat/clear', {
      params: { type }
    })
  }
}
