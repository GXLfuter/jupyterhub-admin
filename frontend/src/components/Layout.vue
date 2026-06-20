<template>
  <el-container class="layout-container">
    <el-aside width="200px" class="sidebar">
      <div class="logo">{{ isStudent ? 'JupyterHub学生端' : 'JupyterHub管理' }}</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
      >
        <template v-for="item in menuItems" :key="item.path">
          <el-menu-item :index="item.path">
            <span>{{ item.label }}</span>
          </el-menu-item>
        </template>
      </el-menu>
      <div class="sidebar-footer">V2.0</div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <h2>{{ pageTitle }}</h2>
        </div>
        <div class="header-right">
          <span class="username">{{ userStore.realName ? userStore.username + '-' + userStore.realName : userStore.username }}</span>
          <el-button type="danger" size="small" @click="handleLogout">
            退出
          </el-button>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeMenu = computed(() => route.path)

// 是否为学生端（用户名以 student 开头）
const isStudent = computed(() => {
  const username = userStore.username || ''
  return /^student\d*$/i.test(username)
})

// 菜单项（按角色过滤）
const menuItems = computed(() => {
  const allMenus = [
    { path: '/containers', label: '容器管理', roles: ['ADMIN'] },
    { path: '/students', label: '在线学生', roles: ['ADMIN'] },
    { path: '/student-chat', label: '学生聊天', roles: ['ADMIN', 'STUDENT'] },
    { path: '/files', label: '文件管理', roles: ['ADMIN'] },
    { path: '/gpu', label: '显卡分配', roles: ['ADMIN'] },
    { path: '/port-forward', label: '端口转发', roles: ['ADMIN', 'STUDENT'] },
    { path: '/image-snapshot', label: '镜像快照', roles: ['ADMIN'] },
    { path: '/tasks', label: '定时任务', roles: ['ADMIN'] },
    { path: '/monitor', label: '资源监控', roles: ['ADMIN'] }
  ]
  const role = (userStore.role || 'ADMIN').toUpperCase()
  return allMenus.filter(item => item.roles.includes(role))
})

const pageTitle = computed(() => {
  const titles = {
    '/containers': '学生容器管理',
    '/students': '在线学生',
    '/files': '文件管理',
    '/gpu': '显卡分配',
    '/port-forward': '端口转发',
    '/image-snapshot': '镜像快照',
    '/student-chat': '学生聊天',
    '/tasks': '定时任务',
    '/monitor': '资源监控',
    '/terminal': 'SSH终端'
  }
  return titles[route.path] || 'JupyterHub管理'
})

const handleLogout = async () => {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })

    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  } catch {
    // 用户取消
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.sidebar {
  background-color: #304156;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: white;
  font-size: 16px;
  font-weight: bold;
  border-bottom: 1px solid #3d4a5c;
}

.sidebar :deep(.el-menu) {
  flex: 1;
}

.sidebar-footer {
  padding: 12px 0;
  text-align: center;
  color: #909399;
  font-size: 13px;
  border-top: 1px solid #3d4a5c;
  background-color: #2c3a4f;
}

.header {
  background: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid #e6e6e6;
}

.header h2 {
  margin: 0;
  font-size: 18px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 15px;
}

.username {
  color: #666;
}

.main-content {
  background: #f0f2f5;
  padding: 20px;
}
</style>
