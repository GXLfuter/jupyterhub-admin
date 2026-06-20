<template>
  <div class="student-chat-view">
    <el-row :gutter="20">
      <!-- 左侧：联系人列表 -->
      <el-col :span="isAdmin ? 6 : 8">
        <el-card shadow="hover" class="contacts-card">
          <template #header>
            <div class="card-header">
              <span>消息列表</span>
              <el-badge v-if="totalUnreadCount > 0" :value="totalUnreadCount" class="badge" />
            </div>
          </template>

          <div class="user-info-section">
            <div class="user-avatar-wrapper">
              <img
                v-if="userAvatarUrl"
                :src="userAvatarUrl"
                class="user-avatar"
                @error="handleAvatarError"
              />
              <div v-else class="user-avatar-placeholder">
                <el-icon :size="32"><User /></el-icon>
              </div>
              <label class="avatar-upload-btn">
                <el-icon><Camera /></el-icon>
                <input
                  type="file"
                  accept="image/*"
                  class="avatar-upload-input"
                  @change="handleAvatarUpload"
                />
              </label>
            </div>
            <div class="user-info">
              <div class="user-name">{{ displayUsername }}</div>
              <div class="user-role">{{ isAdmin ? '管理员' : '学生' }}</div>
            </div>
          </div>

          <div class="contacts-list">
            <div
              class="contact-item group-item"
              :class="{ active: activeTab === 'group' }"
              @click="selectGroupChat"
              @contextmenu.prevent="showContextMenu($event, '群聊')"
            >
              <div class="contact-avatar group-avatar">
                <el-icon :size="32"><ChatDotRound /></el-icon>
              </div>
              <div class="contact-info">
                <div class="contact-name">
                  群聊
                  <el-badge v-if="groupUnreadCount > 0" :value="groupUnreadCount" class="badge-small" />
                </div>
                <div class="contact-last-msg">
                  {{ groupLastMessage }}
                </div>
              </div>
              <div class="contact-time">
                {{ groupLastTime }}
              </div>
            </div>
            <div
              v-for="contact in privateContacts"
              :key="contact.otherUser"
              class="contact-item"
              :class="{ active: selectedContact === contact.otherUser && activeTab === 'private' }"
              @click="selectContact(contact)"
              @contextmenu.prevent="showContextMenu($event, contact.otherUser)"
            >
              <div class="contact-avatar">
                <img
                  :src="getAvatarUrl(contact.otherUser)"
                  class="avatar-img"
                  @error="handleMessageAvatarError($event)"
                />
                <div v-if="!getAvatarUrl(contact.otherUser)" class="avatar-icon">
                  <el-icon :size="24"><User /></el-icon>
                </div>
              </div>
              <div class="contact-info">
                <div class="contact-name">
                  {{ contact.otherUser }}
                  <el-badge v-if="contact.unreadCount > 0" :value="contact.unreadCount" class="badge-small" />
                </div>
                <div class="contact-last-msg">
                  {{ formatLastMessage(contact.lastMessage) }}
                </div>
              </div>
              <div class="contact-time">
                {{ formatTime(contact.lastTime) }}
              </div>
            </div>
            <div v-if="privateContacts.length === 0" class="empty-contacts">
              暂无私信消息
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 中间：聊天区域 -->
      <el-col :span="isAdmin ? 12 : 16">
        <el-card shadow="hover" class="chat-card">
          <template #header>
            <div class="card-header">
              <div class="header-left">
                <span>{{ currentChatTitle }}</span>
                <el-tag v-if="activeTab === 'group'" type="success" size="small">群聊</el-tag>
              </div>
              <div class="header-right" v-if="isAdmin && activeTab === 'private'">
                <el-badge :value="currentUnreadCount" :hidden="currentUnreadCount === 0">
                  <el-button size="small" type="danger" @click="clearChat">清空对话</el-button>
                </el-badge>
              </div>
            </div>
          </template>

          <div v-if="activeTab === 'group' && !groupChatEnabled" class="chat-disabled">
            <el-icon :size="48"><Lock /></el-icon>
            <p>群聊功能已关闭</p>
          </div>

          <div v-else-if="!selectedContact && activeTab === 'private'" class="chat-empty">
            <el-icon :size="64"><ChatLineRound /></el-icon>
            <p>请选择联系人开始聊天</p>
          </div>

          <div v-else class="chat-container">
            <div class="messages-area" ref="messagesAreaRef" @scroll="handleScroll">
              <div v-if="loadingMessages" class="loading-messages">
                <el-icon class="is-loading"><Loading /></el-icon>
                加载中...
              </div>
              <div v-else>
                <div
                  v-for="msg in messages"
                  :key="msg.id || msg.clientMsgId || Math.random()"
                  class="message-item"
                  :class="{ own: isOwnMessage(msg) }"
                >
                  <div class="message-avatar" v-if="!isOwnMessage(msg)">
                    <img
                      :src="getAvatarUrl(msg.sender)"
                      class="avatar-img"
                      @error="handleMessageAvatarError($event)"
                    />
                    <div v-if="!getAvatarUrl(msg.sender)" class="avatar-icon">
                      <el-icon :size="20"><User /></el-icon>
                    </div>
                  </div>
                  <div class="message-avatar own-avatar" v-else>
                    <img
                      :src="userAvatarUrl"
                      class="avatar-img"
                      @error="handleMessageAvatarError($event)"
                    />
                    <div v-if="!userAvatarUrl" class="avatar-icon">
                      <el-icon :size="20"><User /></el-icon>
                    </div>
                  </div>
                  <div class="message-bubble">
                    <div class="message-header">
                      <span class="message-sender">{{ msg.sender }}</span>
                      <el-tag v-if="msg.messageType === 'HAND_RAISE'" type="warning" size="small">
                        <el-icon><Link /></el-icon>
                        举手
                      </el-tag>
                      <span class="message-time">{{ formatTime(msg.createdAt) }}</span>
                    </div>
                    <div class="message-content" :class="{ 'hand-raise': msg.messageType === 'HAND_RAISE' }">
                      <pre>{{ msg.content }}</pre>
                    </div>
                    <div v-if="msg.attachments" class="message-attachments">
                      <el-image
                        v-for="(att, i) in parseAttachments(msg.attachments)"
                        :key="i"
                        :src="att"
                        :preview-src-list="parseAttachments(msg.attachments)"
                        :initial-index="i"
                        fit="cover"
                        class="attachment-image"
                        :preview-teleported="true"
                      />
                    </div>
                  </div>
                </div>
                <div v-if="messages.length === 0 && !loadingMessages" class="no-messages">
                  暂无消息记录
                </div>
              </div>
              <div v-if="!isAtBottom && newMessageCount > 0" class="new-messages-btn" @click="scrollToBottom">
                <el-icon><ArrowDownBold /></el-icon>
                <span>{{ newMessageCount }}条新消息</span>
              </div>
            </div>

            <div class="input-area">
              <div class="input-toolbar">
                <el-button
                  v-if="!isAdmin"
                  type="warning"
                  size="small"
                  @click="showHandRaiseDialog"
                >
                  <el-icon><Link /></el-icon>
                  举手提问
                </el-button>
              </div>
              <div class="input-row">
                <el-input
                  v-model="messageInput"
                  type="textarea"
                  :rows="3"
                  :placeholder="inputPlaceholder"
                  resize="none"
                  @keydown.enter.ctrl="handleSendMessage"
                />
                <div class="input-actions">
                  <el-button type="primary" @click="handleSendMessage" :loading="sending">
                    发送
                  </el-button>
                </div>
              </div>
              <div class="input-hint">按 Ctrl + Enter 发送</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：管理功能（仅管理员） -->
      <el-col v-if="isAdmin" :span="6">
        <el-card shadow="hover" class="students-card">
          <template #header>
            <div class="card-header">
              <span>管理面板</span>
            </div>
          </template>



          <!-- 管理功能 -->
          <div class="admin-controls">
            <el-divider>管理功能</el-divider>
            <div class="control-buttons">
              <el-button
                type="danger"
                size="small"
                @click="handleToggleGroupChat"
              >
                {{ groupChatEnabled ? '关闭' : '开启' }}群聊
              </el-button>
              <el-button
                type="danger"
                size="small"
                @click="handleTogglePrivateChat"
              >
                {{ privateChatEnabled ? '关闭' : '开启' }}私信
              </el-button>
            </div>
            <el-divider>课堂互动</el-divider>
            <div class="control-buttons">
              <el-button
                type="success"
                size="small"
                @click="handleRandomPick"
              >
                <el-icon><User /></el-icon>
                随机抽人
              </el-button>
            </div>
          </div>

          <!-- 举手消息 -->
          <div class="hand-raise-section" v-if="handRaiseMessages.length > 0">
            <el-divider>
              <el-icon><Link /></el-icon>
              待处理举手
            </el-divider>
            <div class="hand-raise-list">
              <div
                v-for="msg in handRaiseMessages"
                :key="msg.id"
                class="hand-raise-item"
                @click="viewHandRaise(msg)"
              >
                <div class="hand-raise-header">
                  <span class="hand-raise-user">{{ msg.sender }}</span>
                  <span class="hand-raise-time">{{ formatTime(msg.createdAt) }}</span>
                </div>
                <div class="hand-raise-content">{{ msg.content }}</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 举手提问对话框 -->
    <el-dialog v-model="handRaiseDialogVisible" title="举手提问" width="600px">
      <el-form :model="handRaiseForm" label-width="80px">
        <el-form-item label="问题描述">
          <el-input
            v-model="handRaiseForm.content"
            type="textarea"
            :rows="6"
            placeholder="请详细描述您遇到的问题..."
          />
        </el-form-item>
        <el-form-item label="附件图片">
          <el-upload
            v-model:file-list="handRaiseForm.fileList"
            :action="uploadUrl"
            :headers="uploadHeaders"
            :before-upload="beforeImageUpload"
            :on-success="handleUploadSuccess"
            :on-error="handleUploadError"
            :on-remove="handleUploadRemove"
            :limit="5"
            :max-count="5"
            :on-exceed="handleExceed"
            list-type="picture-card"
            accept="image/*"
            name="file"
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
          <div class="upload-tip">最多上传5张图片，支持 jpg/png/gif，单张不超过20MB</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handRaiseDialogVisible = false">取消</el-button>
        <el-button type="warning" @click="submitHandRaise" :loading="sending">
          <el-icon><Link /></el-icon>
          提交举手
        </el-button>
      </template>
    </el-dialog>

    <!-- 清空对话对话框 -->
    <el-dialog v-model="clearDialogVisible" title="清空对话" width="400px">
      <p>确定要清空与 {{ selectedContact }} 的所有对话记录吗？</p>
      <p style="color: #f56c6c;">此操作不可恢复！</p>
      <template #footer>
        <el-button @click="clearDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmClearChat">确定清空</el-button>
      </template>
    </el-dialog>

    <!-- 右键菜单 -->
    <ul
      v-show="contextMenu.visible"
      class="context-menu"
      :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
    >
      <li @click="handleClearOne(contextMenu.targetUser)">清除当前聊天记录</li>
    </ul>

    <!-- 清空所有聊天记录对话框 -->
    <el-dialog v-model="clearAllDialogVisible" title="清空聊天记录" width="400px">
      <el-form label-width="100px">
        <el-form-item label="清空范围">
          <el-radio-group v-model="clearType">
            <el-radio label="group">仅群聊记录</el-radio>
            <el-radio label="all">所有聊天记录</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <p style="color: #f56c6c;">此操作不可恢复！</p>
      <template #footer>
        <el-button @click="clearAllDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmClearAll">确定清空</el-button>
      </template>
    </el-dialog>

    <!-- 查看举手详情对话框 -->
    <el-dialog v-model="viewHandRaiseDialogVisible" title="举手详情" width="700px">
      <div v-if="currentHandRaise" class="hand-raise-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="学生">{{ currentHandRaise.sender }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ formatTime(currentHandRaise.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="hand-raise-text">
          <h4>问题描述：</h4>
          <pre>{{ currentHandRaise.content }}</pre>
        </div>
        <div v-if="currentHandRaise.attachments" class="hand-raise-attachments">
          <h4>截图：</h4>
          <p>{{ currentHandRaise.attachments }}</p>
        </div>
        <div class="hand-raise-reply">
          <h4>回复：</h4>
          <el-input
            v-model="replyContent"
            type="textarea"
            :rows="4"
            placeholder="输入回复内容..."
          />
          <el-button type="primary" @click="submitReply" style="margin-top: 10px;">
            发送回复
          </el-button>
        </div>
      </div>
    </el-dialog>

    <!-- 随机抽人对话框 -->
    <el-dialog v-model="randomPickDialogVisible" title="随机抽人" width="400px" center>
      <!-- 初始状态：显示开始按钮 -->
      <div v-if="!randomPickResult && !randomRolling" class="random-pick-initial">
        <div class="random-pick-animation">
          <el-icon class="random-icon" :size="60"><User /></el-icon>
        </div>
        <div class="random-pick-name">
          <h2>准备开始</h2>
        </div>
        <div class="random-pick-tip">
          <p>点击下方按钮随机抽取学生</p>
        </div>
        <el-button type="success" size="large" @click="startRandomPick" style="margin-top: 20px;">
          开始抽取
        </el-button>
      </div>

      <!-- 抽取中：快速滚动显示 -->
      <div v-else-if="randomRolling" class="random-pick-result">
        <div class="random-pick-animation rolling">
          <el-icon class="random-icon" :size="60"><User /></el-icon>
        </div>
        <div class="random-pick-name">
          <h2 class="rolling-name">{{ randomPickResult }}</h2>
        </div>
        <div class="random-pick-tip">
          <p>抽取中...</p>
        </div>
      </div>

      <!-- 结果：显示最终结果 -->
      <div v-else class="random-pick-result">
        <div class="random-pick-animation">
          <el-icon class="random-icon" :size="60"><User /></el-icon>
        </div>
        <div class="random-pick-name">
          <h2>{{ randomPickResult }}</h2>
        </div>
        <div class="random-pick-tip">
          <p>请 {{ randomPickResult }} 同学回答问题</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeRandomPickDialog">关闭</el-button>
        <el-button v-if="randomPickResult && !randomRolling" type="success" @click="startRandomPick">再抽一次</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, ChatDotRound, ChatLineRound, Lock, Link, Picture, Loading, Bell, ArrowDownBold, Plus } from '@element-plus/icons-vue'
import { chatApi } from '../api/chat'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()

// 用户信息
const currentUser = computed(() => userStore.username)
const currentRealName = computed(() => userStore.realName)
const isAdmin = computed(() => (userStore.role || 'ADMIN').toUpperCase() === 'ADMIN')

const displayUsername = computed(() => {
  if (currentRealName.value) {
    return `${currentUser.value}-${currentRealName.value}`
  }
  return currentUser.value
})

// 状态变量
const activeTab = ref('private')
const selectedContact = ref('')
const messages = ref([])
const contacts = ref([])
const students = ref([])
const handRaiseMessages = ref([])
const messagesAreaRef = ref(null)
const loadingMessages = ref(false)
const sending = ref(false)
const messageInput = ref('')
const totalUnreadCount = ref(0)
const currentUnreadCount = ref(0)

// 头像相关
const userAvatarUrl = ref('')
const avatarUploading = ref(false)

// 设置
const groupChatEnabled = ref(true)
const privateChatEnabled = ref(true)
const settings = ref({})

// 对话框
const handRaiseDialogVisible = ref(false)
const clearDialogVisible = ref(false)
const clearAllDialogVisible = ref(false)
const clearType = ref('group')
const handRaiseForm = ref({
  content: '',
  attachments: '',
  fileList: []
})

// 附件上传相关（通过 Vite 代理 /api 转发到后端 9090）
const uploadUrl = '/api/chat/upload-image'
const uploadHeaders = computed(() => {
  return { 'Authorization': 'Bearer ' + (localStorage.getItem('token') || '') }
})

// 查看举手详情
const viewHandRaiseDialogVisible = ref(false)
const currentHandRaise = ref(null)
const replyContent = ref('')

// 随机抽人
const randomPickDialogVisible = ref(false)
const randomPickResult = ref('')
const randomRolling = ref(false)
let randomPickInterval = null

// 自动刷新定时器
let refreshTimer = null
let messagePollTimer = null
let lastMessageId = 0
let lastScrollTop = 0
const isAtBottom = ref(true)

// WebSocket连接
let ws = null
let wsReconnectTimer = null

// WebSocket连接
const connectWebSocket = () => {
  if (ws && ws.readyState === WebSocket.OPEN) return
  
  const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/api/ws/chat?username=${currentUser.value}`
  
  try {
    ws = new WebSocket(wsUrl)
    
    ws.onopen = () => {
      console.log('WebSocket connected')
    }
    
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        
        // 处理清空事件
        if (data.type === 'CLEAR') {
          if (data.clearType === 'group' || data.clearType === 'all' || data.targetUser === '群聊') {
            messages.value = []
            newMessageCount.value = 0
          }
          if (data.clearType === 'one' && data.targetUser === selectedContact.value) {
            messages.value = []
            newMessageCount.value = 0
          }
          if (data.clearType === 'all' && activeTab.value === 'private') {
            messages.value = []
            newMessageCount.value = 0
          }
          loadContacts()
          loadUnreadCount()
        }
        
        // 处理新消息
        if (data.id && data.content !== undefined) {
          const exists = messages.value.some(m => m.id === data.id)
          if (!exists) {
            messages.value.push(data)
            if (isAtBottom.value) {
              requestAnimationFrame(() => {
                nextTick(() => {
                  scrollToBottom()
                })
              })
              // 用户在底部查看，自动标记已读
              if (data.isGroup) {
                chatApi.markAsRead(currentUser.value, '群聊').then(() => {
                  loadContacts()
                  loadUnreadCount()
                })
              } else {
                chatApi.markAsRead(currentUser.value, data.sender).then(() => {
                  loadContacts()
                  loadUnreadCount()
                })
              }
            } else {
              newMessageCount.value++
            }
          }
        }
      } catch (e) {
        console.error('WebSocket消息解析失败', e)
      }
    }
    
    ws.onclose = () => {
      console.log('WebSocket disconnected, reconnecting...')
      wsReconnectTimer = setTimeout(connectWebSocket, 3000)
    }
    
    ws.onerror = (e) => {
      console.error('WebSocket error', e)
      ws.close()
    }
  } catch (e) {
    console.error('WebSocket连接失败', e)
    wsReconnectTimer = setTimeout(connectWebSocket, 3000)
  }
}

const disconnectWebSocket = () => {
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer)
    wsReconnectTimer = null
  }
  if (ws) {
    ws.close()
    ws = null
  }
}

// 增量刷新消息（每2秒拉取一次）
const pollMessages = async () => {
  try {
    let res
    if (activeTab.value === 'group') {
      res = await chatApi.getGroupMessages(100, 0)
    } else if (selectedContact.value) {
      res = await chatApi.getPrivateMessages(currentUser.value, selectedContact.value, 100, 0)
    } else {
      return
    }

    if (res.code === 200 && res.data) {
      const latestMsgs = res.data
      const currentIds = new Set(messages.value.map(m => m.id))
      let newCount = 0

      for (const msg of latestMsgs) {
        if (!currentIds.has(msg.id)) {
          messages.value.push(msg)
          newCount++
        }
      }

      if (newCount > 0) {
        // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
        if (isAtBottom.value) {
          requestAnimationFrame(() => {
            nextTick(() => {
              scrollToBottom()
            })
          })
          if (activeTab.value === 'group') {
            await chatApi.markAsRead(currentUser.value, '群聊')
          } else if (selectedContact.value) {
            await chatApi.markAsRead(currentUser.value, selectedContact.value)
          }
        } else {
          newMessageCount.value += newCount
        }
      }
    }
  } catch (e) {
    // 静默失败，避免影响用户体验
  }
}

// 新消息数量（从底部向上看的未显示消息）
let newMessageCount = ref(0)
let lastSeenMessageId = 0

// 滚动事件处理
const handleScroll = () => {
  if (!messagesAreaRef.value) return
  const el = messagesAreaRef.value
  const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight

  if (distanceFromBottom < 50) {
    isAtBottom.value = true
    newMessageCount.value = 0
  } else {
    isAtBottom.value = false
  }
}

// 计算属性
const currentChatTitle = computed(() => {
  if (activeTab.value === 'group') return '大群聊天'
  return selectedContact.value || '选择联系人'
})

// 判断消息是否为自己发送
const isOwnMessage = (msg) => {
  return msg.sender === currentUser.value || (displayUsername.value && msg.sender.startsWith && msg.sender.startsWith(currentUser.value + '-'))
}

const inputPlaceholder = computed(() => {
  if (activeTab.value === 'group') return '输入群聊消息...'
  return '输入私信内容...'
})

const studentsWithUnread = computed(() => {
  return students.value.filter(s => s.unreadCount > 0)
})

const privateContacts = computed(() => {
  return contacts.value.filter(c => c.otherUser !== '群聊')
})

const groupContact = computed(() => {
  return contacts.value.find(c => c.otherUser === '群聊')
})

const groupUnreadCount = computed(() => {
  return groupContact.value?.unreadCount || 0
})

const groupLastMessage = computed(() => {
  if (!groupContact.value?.lastMessage) return '暂无消息'
  const msg = groupContact.value.lastMessage
  if (msg.messageType === 'HAND_RAISE') return '[举手提问]'
  return msg.content.substring(0, 20) + (msg.content.length > 20 ? '...' : '')
})

const groupLastTime = computed(() => {
  return groupContact.value ? formatTime(groupContact.value.lastTime) : ''
})

// 方法
const loadSettings = async () => {
  try {
    const res = await chatApi.getSettings()
    if (res.code === 200) {
      settings.value = res.data
      groupChatEnabled.value = res.data.group_chat_enabled === 'true'
      privateChatEnabled.value = res.data.private_chat_enabled === 'true'
    }
  } catch (e) {
    console.error('加载设置失败', e)
  }
}

const loadContacts = async () => {
  try {
    const res = await chatApi.getContacts(currentUser.value)
    if (res.code === 200) {
      contacts.value = res.data || []
    }
  } catch (e) {
    console.error('加载联系人失败', e)
  }
}

const loadUnreadCount = async () => {
  try {
    const res = await chatApi.getUnreadCount(currentUser.value)
    if (res.code === 200) {
      totalUnreadCount.value = res.data || 0
    }
  } catch (e) {
    console.error('加载未读数失败', e)
  }
}

const loadStudents = async () => {
  if (!isAdmin.value) return
  try {
    const res = await chatApi.getAllStudents()
    if (res.code === 200) {
      students.value = res.data || []
    }
  } catch (e) {
    console.error('加载学生列表失败', e)
  }
}

const loadHandRaiseMessages = async () => {
  if (!isAdmin.value) return
  try {
    const res = await chatApi.getHandRaiseMessages()
    if (res.code === 200) {
      handRaiseMessages.value = res.data || []
    }
  } catch (e) {
    console.error('加载举手消息失败', e)
  }
}

const selectContact = async (contact) => {
  selectedContact.value = contact.otherUser
  activeTab.value = 'private'
  await loadMessages()
  newMessageCount.value = 0
  isAtBottom.value = true
  nextTick(() => {
    scrollToBottom()
  })

  if (contact.unreadCount > 0) {
    await chatApi.markAsRead(currentUser.value, contact.otherUser)
    contact.unreadCount = 0
    await loadUnreadCount()
  }
}

const selectStudent = async (student) => {
  selectedContact.value = student.username
  activeTab.value = 'private'
  await loadMessages()
  newMessageCount.value = 0
  isAtBottom.value = true
  nextTick(() => {
    scrollToBottom()
  })

  if (student.unreadCount > 0) {
    await chatApi.markAsRead(currentUser.value, student.username)
    student.unreadCount = 0
    await loadStudents()
    await loadUnreadCount()
  }
}

const selectGroupChat = async () => {
  selectedContact.value = ''
  activeTab.value = 'group'
  await loadMessages()
  newMessageCount.value = 0
  isAtBottom.value = true
  requestAnimationFrame(() => {
    nextTick(() => {
      scrollToBottom()
    })
  })

  await chatApi.markAsRead(currentUser.value, '群聊')
  await loadContacts()
  await loadUnreadCount()
}

const loadMessages = async () => {
  loadingMessages.value = true
  try {
    let res
    if (activeTab.value === 'group') {
      res = await chatApi.getGroupMessages(100, 0)
    } else {
      res = await chatApi.getPrivateMessages(currentUser.value, selectedContact.value, 100, 0)
    }
    if (res.code === 200) {
      messages.value = (res.data || []).slice().sort((a, b) => (a.id || 0) - (b.id || 0))
      newMessageCount.value = 0
      isAtBottom.value = true
      requestAnimationFrame(() => {
        nextTick(() => {
          scrollToBottom()
        })
      })
    }
  } catch (e) {
    console.error('加载消息失败', e)
  } finally {
    loadingMessages.value = false
  }
}

const scrollToBottom = () => {
  if (messagesAreaRef.value) {
    messagesAreaRef.value.scrollTop = messagesAreaRef.value.scrollHeight
  }
}

const handleSendMessage = async () => {
  const content = messageInput.value.trim()
  if (!content) return

  sending.value = true
  try {
    const res = await chatApi.sendMessage(
      displayUsername.value,
      content,
      activeTab.value === 'private' ? selectedContact.value : null,
      'TEXT',
      null,
      activeTab.value === 'group'
    )
    if (res.code === 200) {
      messageInput.value = ''

      await loadMessages()
      newMessageCount.value = 0
      isAtBottom.value = true
      nextTick(() => {
        scrollToBottom()
      })

      await loadContacts()
      await loadUnreadCount()
    } else {
      ElMessage.error(res.msg || '发送失败')
    }
  } catch (e) {
    console.error('发送消息失败', e)
    ElMessage.error('发送失败')
  } finally {
    sending.value = false
  }
}

const showHandRaiseDialog = () => {
  handRaiseForm.value = { content: '', attachments: '', fileList: [] }
  handRaiseDialogVisible.value = true
}

// 上传前的校验
const beforeImageUpload = (file) => {
  const isImage = file.type.startsWith('image/')
  const isLt20M = file.size / 1024 / 1024 < 20
  if (!isImage) {
    ElMessage.error('只能上传图片文件')
    return false
  }
  if (!isLt20M) {
    ElMessage.error('图片大小不能超过 20MB')
    return false
  }
  return true
}

// 上传成功回调
const handleUploadSuccess = (response, file) => {
  // el-upload 用原生 axios，response 是后端返回的原始 {code, msg, data}
  if (response && response.code === 200 && response.data) {
    const url = response.data.url || response.data
    if (handRaiseForm.value.attachments) {
      handRaiseForm.value.attachments += ',' + url
    } else {
      handRaiseForm.value.attachments = url
    }
    ElMessage.success('上传成功')
  } else {
    ElMessage.error((response && response.msg) || '上传失败')
  }
}

// 上传失败回调
const handleUploadError = () => {
  ElMessage.error('上传失败')
}

// 移除附件
const handleUploadRemove = (file) => {
  const url = file.response?.data?.url || file.response?.data || file.url
  if (url && handRaiseForm.value.attachments) {
    const arr = handRaiseForm.value.attachments.split(',').filter(u => u !== url)
    handRaiseForm.value.attachments = arr.join(',')
  }
}

// 超出数量限制
const handleExceed = () => {
  ElMessage.warning('最多只能上传5张图片')
}

// 头像上传
const handleAvatarUpload = async (event) => {
  const file = event.target.files[0]
  if (!file) return

  const isImage = file.type.startsWith('image/')
  if (!isImage) {
    ElMessage.error('只能上传图片文件')
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 20MB')
    return
  }

  avatarUploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('username', currentUser.value)

    const res = await fetch('/api/chat/avatar/upload', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + (localStorage.getItem('token') || '')
      },
      body: formData
    })

    const result = await res.json()
    if (result.code === 200 && result.data && result.data.url) {
      userAvatarUrl.value = result.data.url + '?t=' + Date.now()
      ElMessage.success('头像上传成功')
    } else {
      ElMessage.error(result.msg || '上传失败')
    }
  } catch (e) {
    ElMessage.error('上传失败')
  } finally {
    avatarUploading.value = false
    event.target.value = ''
  }
}

// 头像加载失败处理
const handleAvatarError = () => {
  userAvatarUrl.value = ''
}

// 获取用户头像
const loadAvatar = () => {
  const avatarUrl = '/api/chat/avatar/' + currentUser.value
  const img = new Image()
  img.onload = () => {
    userAvatarUrl.value = avatarUrl + '?t=' + Date.now()
  }
  img.onerror = () => {
    userAvatarUrl.value = ''
  }
  img.src = avatarUrl
}

// 获取指定用户的头像URL
const getAvatarUrl = (username) => {
  if (!username) return ''
  const baseName = username.split('-')[0]
  return '/api/chat/avatar/' + baseName + '?t=' + Date.now()
}

// 消息头像加载失败处理
const handleMessageAvatarError = (event) => {
  event.target.style.display = 'none'
}

const submitHandRaise = async () => {
  if (!handRaiseForm.value.content.trim()) {
    ElMessage.warning('请输入问题描述')
    return
  }

  sending.value = true
  try {
    const res = await chatApi.sendMessage(
      displayUsername.value,
      handRaiseForm.value.content,
      'admin',
      'HAND_RAISE',
      handRaiseForm.value.attachments || null,
      false
    )
    if (res.code === 200) {
      ElMessage.success('举手提问已提交')
      handRaiseDialogVisible.value = false
      await loadContacts()
      selectedContact.value = 'admin'
      activeTab.value = 'private'
      await loadMessages()
      newMessageCount.value = 0
      isAtBottom.value = true
      nextTick(() => {
        scrollToBottom()
      })
    } else {
      ElMessage.error(res.msg || '提交失败')
    }
  } catch (e) {
    ElMessage.error('提交失败')
  } finally {
    sending.value = false
  }
}

const submitReply = async () => {
  if (!replyContent.value.trim()) {
    ElMessage.warning('请输入回复内容')
    return
  }

  try {
    const res = await chatApi.sendMessage(
      displayUsername.value,
      replyContent.value,
      currentHandRaise.value.sender,
      'TEXT',
      null,
      false
    )
    if (res.code === 200) {
      ElMessage.success('回复已发送')
      replyContent.value = ''
      viewHandRaiseDialogVisible.value = false
      await loadStudents()
    } else {
      ElMessage.error(res.msg || '发送失败')
    }
  } catch (e) {
    ElMessage.error('发送失败')
  }
}

const viewHandRaise = async (msg) => {
  currentHandRaise.value = msg
  viewHandRaiseDialogVisible.value = true

  // 标记已读（举手消息发送给admin）
  if (msg.sender !== currentUser.value) {
    await chatApi.markAsRead(currentUser.value, msg.sender)
    await loadHandRaiseMessages()
    await loadUnreadCount()
  }
}

// 随机抽人功能
const handleRandomPick = () => {
  // 重置状态
  randomPickResult.value = ''
  randomRolling.value = false
  if (randomPickInterval) {
    clearInterval(randomPickInterval)
    randomPickInterval = null
  }
  randomPickDialogVisible.value = true
}

// 开始抽取动画（3秒倒计时后显示结果）
const startRandomPick = () => {
  if (randomRolling.value) return

  // 清除之前的定时器
  if (randomPickInterval) {
    clearInterval(randomPickInterval)
  }

  randomRolling.value = true
  randomPickResult.value = 'student1'

  // 每80ms快速切换显示名字
  let tickCount = 0
  const totalTicks = 38 // 约3秒 (80ms * 38 ≈ 3000ms)

  randomPickInterval = setInterval(() => {
    tickCount++
    const randomNum = Math.floor(Math.random() * 60) + 1
    randomPickResult.value = `student${randomNum}`

    if (tickCount >= totalTicks) {
      // 停止动画，显示最终结果
      clearInterval(randomPickInterval)
      randomPickInterval = null
      randomRolling.value = false
    }
  }, 80)
}

// 关闭随机抽人对话框
const closeRandomPickDialog = () => {
  if (randomPickInterval) {
    clearInterval(randomPickInterval)
    randomPickInterval = null
  }
  randomRolling.value = false
  randomPickDialogVisible.value = false
}

const handleToggleGroupChat = async () => {
  const newValue = !groupChatEnabled.value
  try {
    await chatApi.updateSetting('group_chat_enabled', String(newValue))
    groupChatEnabled.value = newValue
    ElMessage.success(`群聊已${newValue ? '开启' : '关闭'}`)
  } catch (e) {
    ElMessage.error('设置失败')
  }
}

const handleTogglePrivateChat = async () => {
  const newValue = !privateChatEnabled.value
  try {
    await chatApi.updateSetting('private_chat_enabled', String(newValue))
    privateChatEnabled.value = newValue
    ElMessage.success(`私信功能已${newValue ? '开启' : '关闭'}`)
  } catch (e) {
    ElMessage.error('设置失败')
  }
}

const clearChat = () => {
  if (!selectedContact.value) return
  clearDialogVisible.value = true
}

const confirmClearChat = async () => {
  if (!selectedContact.value) return
  const target = selectedContact.value
  clearDialogVisible.value = false
  try {
    await ElMessageBox.confirm(
      `确定要清空与"${target}"的对话吗？此操作不可恢复！`,
      '清空确认',
      { type: 'warning', confirmButtonText: '确定清空', cancelButtonText: '取消' }
    )
    const res = await chatApi.clearChatHistory(currentUser.value, target)
    if (res.code === 200) {
      ElMessage.success('对话已清空')
      // 标记为已读
      await chatApi.markAsRead(currentUser.value, target)
      // 重新加载消息和联系人
      await loadMessages()
      await loadContacts()
      await loadUnreadCount()
    } else {
      ElMessage.error(res.msg || '清空失败')
    }
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      console.error('清空对话失败', e)
      ElMessage.error('清空失败')
    }
  }
}

// 右键菜单
const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  targetUser: ''
})

const showContextMenu = (event, targetUser) => {
  // 只有admin可以清除群聊消息
  if (!isAdmin.value && targetUser === '群聊') {
    ElMessage.warning('只有管理员可以清除群聊记录')
    return
  }
  contextMenu.visible = true
  contextMenu.x = event.clientX
  contextMenu.y = event.clientY
  contextMenu.targetUser = targetUser
}

const hideContextMenu = () => {
  contextMenu.visible = false
}

const handleClearOne = async (targetUser) => {
  hideContextMenu()
  try {
    await ElMessageBox.confirm(
      `确定要清除与"${targetUser}"的聊天记录吗？此操作不可恢复！`,
      '清除确认',
      { type: 'warning', confirmButtonText: '确定清除', cancelButtonText: '取消' }
    )
    const res = await chatApi.clearChatHistory(currentUser.value, targetUser)
    if (res.code === 200) {
      ElMessage.success('聊天记录已清除')
      // 清除后标记为已读
      await chatApi.markAsRead(currentUser.value, targetUser)
      await loadContacts()
      await loadUnreadCount()
      if (selectedContact.value === targetUser || (activeTab.value === 'group' && targetUser === '群聊')) {
        messages.value = []
        newMessageCount.value = 0
      }
      if (isAdmin.value) {
        await loadStudents()
      }
    } else {
      ElMessage.error(res.msg || '清除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      console.error('清除聊天记录失败', e)
      ElMessage.error('清除失败')
    }
  }
}

const showClearDialog = () => {
  clearType.value = 'group'
  clearAllDialogVisible.value = true
}

const confirmClearAll = async () => {
  try {
    const res = await chatApi.clearMessages(clearType.value)
    if (res.code === 200) {
      ElMessage.success('聊天记录已清空')
      clearAllDialogVisible.value = false
      await loadContacts()
      await loadStudents()
      await loadMessages()
    } else {
      ElMessage.error(res.msg || '清空失败')
    }
  } catch (e) {
    ElMessage.error('清空失败')
  }
}

// 工具函数
const formatTime = (time) => {
  if (!time) return ''
  const date = new Date(time)
  const now = new Date()
  const diff = now - date

  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' +
         date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const formatLastMessage = (msg) => {
  if (!msg) return '暂无消息'
  if (msg.messageType === 'HAND_RAISE') return '[举手提问]'
  return msg.content.substring(0, 20) + (msg.content.length > 20 ? '...' : '')
}

const parseAttachments = (attachments) => {
  if (!attachments) return []
  return attachments.split(',').filter(a => a.trim())
}

// 刷新数据（仅刷新联系人列表和未读计数，消息通过WebSocket实时获取）
const refreshData = async () => {
  await Promise.all([
    loadContacts(),
    loadUnreadCount(),
    loadStudents(),
    loadHandRaiseMessages()
  ])
}

// 定时刷新（仅用于更新联系人列表和未读计数，每15秒一次）
const startAutoRefresh = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = setInterval(refreshData, 15000)
}

onMounted(async () => {
  await loadSettings()
  await refreshData()
  await selectGroupChat()
  startAutoRefresh()
  loadAvatar()
  // 每2秒轮询新消息
  messagePollTimer = setInterval(pollMessages, 2000)
  // 全局点击关闭右键菜单
  document.addEventListener('click', hideContextMenu)
  // 连接WebSocket接收清空事件
  connectWebSocket()
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (messagePollTimer) clearInterval(messagePollTimer)
  if (randomPickInterval) clearInterval(randomPickInterval)
  disconnectWebSocket()
  document.removeEventListener('click', hideContextMenu)
})
</script>

<style scoped>
.student-chat-view {
  padding: 0;
}

.student-chat-view :deep(.el-card) {
  border-radius: 12px;
  overflow: hidden;
}

.student-chat-view :deep(.el-card__header) {
  padding: 12px 16px;
  background: #f5f7fa;
}

.contacts-card {
  height: calc(100vh - 160px);
  display: flex;
  flex-direction: column;
}

.contacts-card :deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 0;
}

.user-info-section {
  display: flex;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.user-avatar-wrapper {
  position: relative;
  margin-right: 12px;
}

.user-avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid rgba(255, 255, 255, 0.5);
}

.user-avatar-placeholder {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 3px solid rgba(255, 255, 255, 0.5);
}

.avatar-upload-btn {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #409EFF;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border: 2px solid white;
  transition: all 0.3s;
}

.avatar-upload-btn:hover {
  background: #66b1ff;
  transform: scale(1.1);
}

.avatar-upload-input {
  display: none;
}

.user-info {
  flex: 1;
}

.user-name {
  font-size: 16px;
  font-weight: 600;
}

.user-role {
  font-size: 12px;
  opacity: 0.8;
}

.contacts-list {
  padding: 12px;
  border-bottom: 1px solid #ebeef5;
}

.contacts-list {
  flex: 1;
  overflow-y: auto;
}

.contact-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.2s;
  border-bottom: 1px solid #f0f0f0;
}

.contact-item:hover {
  background: #f5f7fa;
}

.contact-item.active {
  background: #ecf5ff;
  border-left: 3px solid #409eff;
}

.contact-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #409eff;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  margin-right: 12px;
  overflow: hidden;
  position: relative;
  flex-shrink: 0;
}

.contact-avatar .avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.contact-avatar .avatar-icon {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.group-avatar {
  background: #67c23a;
}

.group-item {
  border-bottom: 2px solid #ebeef5;
}

.contact-info {
  flex: 1;
  overflow: hidden;
}

.contact-name {
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 8px;
}

.contact-last-msg {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 4px;
}

.contact-time {
  font-size: 11px;
  color: #bbb;
  margin-left: 8px;
}

.empty-contacts {
  text-align: center;
  padding: 40px 20px;
  color: #999;
}

.group-chat-btn {
  padding: 20px;
  text-align: center;
}

.chat-card {
  height: calc(100vh - 160px);
  display: flex;
  flex-direction: column;
}

.chat-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.chat-empty,
.chat-disabled {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #999;
}

.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f9f9f9;
  position: relative;
}

.new-messages-btn {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: #409eff;
  color: white;
  padding: 8px 16px;
  border-radius: 20px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
  z-index: 100;
  transition: all 0.2s;
}

.new-messages-btn:hover {
  background: #337ecc;
  transform: translateX(-50%) scale(1.05);
}

.context-menu {
  position: fixed;
  background: white;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  list-style: none;
  padding: 4px 0;
  margin: 0;
  z-index: 9999;
  min-width: 160px;
}

.context-menu li {
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
  color: #303133;
  transition: background 0.2s;
}

.context-menu li:hover {
  background: #f5f7fa;
  color: #409eff;
}

.loading-messages {
  text-align: center;
  padding: 20px;
  color: #999;
}

.no-messages {
  text-align: center;
  padding: 40px;
  color: #999;
}

.message-item {
  display: flex;
  margin-bottom: 16px;
}

.message-item.own {
  flex-direction: row-reverse;
}

/* 仿照微信：自己消息 - 绿色背景 + 黑字 */
.message-item.own .message-bubble {
  background: #95EC69;
  color: #000000;
}

.message-item.own .message-sender {
  color: #555;
}

.message-item.own .message-time {
  color: #888;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #909399;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  margin-right: 10px;
  flex-shrink: 0;
  overflow: hidden;
  position: relative;
}

.message-avatar .avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.message-avatar .avatar-icon {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.message-item.own .message-avatar {
  margin-right: 0;
  margin-left: 10px;
  background: #07C160;
}

.message-bubble {
  max-width: 70%;
  background: white;
  border-radius: 12px;
  padding: 10px 14px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.message-sender {
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
}

.message-time {
  font-size: 11px;
  color: #bbb;
  margin-left: auto;
}

.message-content {
  font-size: 14px;
  line-height: 1.5;
  word-break: break-word;
}

.message-content pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-content.hand-raise {
  background: #fffbe6;
  color: #d48806;
  padding: 10px;
  border-radius: 8px;
  border: 1px solid #ffe58f;
}

/* 自己发送的普通消息：黑字 */
.message-item.own .message-content,
.message-item.own .message-content pre {
  color: #303133;
}

/* 自己发送的举手消息：保持橙色与浅黄背景对比 */
.message-item.own .message-content.hand-raise,
.message-item.own .message-content.hand-raise pre {
  color: #d48806;
}

.message-attachments {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.attachment-image {
  width: 90px;
  height: 90px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid rgba(0, 0, 0, 0.08);
  object-fit: cover;
}

.input-area {
  border-top: 1px solid #eee;
  padding: 12px;
  background: white;
}

.input-toolbar {
  margin-bottom: 8px;
}

.input-row {
  display: flex;
  gap: 12px;
}

.input-row :deep(.el-textarea) {
  flex: 1;
}

.input-row :deep(.el-textarea__inner) {
  border-radius: 8px;
}

.input-actions {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.input-hint {
  font-size: 11px;
  color: #bbb;
  margin-top: 6px;
}

.students-card {
  height: calc(100vh - 160px);
  display: flex;
  flex-direction: column;
}

.students-card :deep(.el-card__body) {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.students-list {
  max-height: 300px;
  overflow-y: auto;
}

.student-item {
  display: flex;
  align-items: center;
  padding: 10px;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.2s;
}

.student-item:hover {
  background: #f5f7fa;
}

.student-item.has-unread {
  background: #fff0f0;
}

.student-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #67c23a;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  margin-right: 10px;
}

.student-info {
  flex: 1;
  overflow: hidden;
}

.student-name {
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}

.student-last-msg {
  font-size: 11px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 6px;
  line-height: 1.4;
}

.admin-controls {
  margin-top: 12px;
  padding: 0 8px;
}

.admin-controls .el-divider {
  margin: 12px 0;
}

.control-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: stretch;
}

.control-buttons .el-button {
  width: 100%;
  margin-left: 0 !important;
  border-radius: 8px;
  font-weight: 500;
  letter-spacing: 1px;
  transition: all 0.25s ease;
  box-shadow: 0 2px 6px rgba(245, 108, 108, 0.15);
}

.control-buttons .el-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(245, 108, 108, 0.25);
}

.hand-raise-section {
  margin-top: 12px;
}

.hand-raise-list {
  max-height: 200px;
  overflow-y: auto;
}

.hand-raise-item {
  padding: 10px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.hand-raise-item:hover {
  background: #fff7cc;
}

.hand-raise-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}

.hand-raise-user {
  font-weight: 500;
  color: #e6a23c;
}

.hand-raise-time {
  font-size: 11px;
  color: #999;
}

.hand-raise-content {
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.hand-raise-detail {
  padding: 10px 0;
}

.hand-raise-text {
  margin-top: 16px;
}

.hand-raise-text h4 {
  margin: 0 0 8px 0;
  color: #409eff;
}

.hand-raise-text pre {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 8px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.hand-raise-attachments {
  margin-top: 16px;
}

.hand-raise-attachments h4 {
  margin: 0 0 8px 0;
  color: #e6a23c;
}

.hand-raise-reply {
  margin-top: 16px;
}

.hand-raise-reply h4 {
  margin: 0 0 8px 0;
  color: #67c23a;
}

/* 随机抽人样式 */
.random-pick-initial {
  text-align: center;
  padding: 20px;
}

.random-pick-initial .random-pick-animation {
  margin-bottom: 20px;
}

.random-pick-result {
  text-align: center;
  padding: 20px;
}

.random-pick-animation {
  margin-bottom: 20px;
}

.random-pick-animation.rolling {
  animation: shake 0.1s ease-in-out infinite;
}

.random-icon {
  color: #67c23a;
  animation: pulse 1s ease-in-out infinite;
}

.random-pick-animation.rolling .random-icon {
  color: #409eff;
  animation: pulse-fast 0.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% {
    transform: scale(1);
  }
  50% {
    transform: scale(1.1);
  }
}

@keyframes pulse-fast {
  0%, 100% {
    transform: scale(0.9);
  }
  50% {
    transform: scale(1.15);
  }
}

@keyframes shake {
  0%, 100% {
    transform: translateX(0);
  }
  25% {
    transform: translateX(-3px);
  }
  75% {
    transform: translateX(3px);
  }
}

.random-pick-name h2 {
  color: #67c23a;
  font-size: 32px;
  margin: 0 0 10px 0;
  transition: all 0.1s ease;
}

.random-pick-name .rolling-name {
  color: #409eff;
  font-size: 36px;
  letter-spacing: 2px;
}

.random-pick-tip p {
  color: #909399;
  font-size: 14px;
  margin: 0;
}

.badge {
  margin-left: 8px;
}

.badge-small {
  transform: scale(0.8);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
