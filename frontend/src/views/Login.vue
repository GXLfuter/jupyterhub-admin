<template>
  <div class="login-container">
    <div class="bg-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
      <div class="circle circle-3"></div>
      <div class="circle circle-4"></div>
      <div class="circle circle-5"></div>
    </div>

    <div class="login-box">
      <div class="logo-section">
        <div class="logo">
          <span class="logo-text">JH</span>
        </div>
        <h1 class="title">{{ isStudent ? 'JupyterHub 学生端' : 'JupyterHub 管理平台' }}</h1>
        <p class="subtitle">{{ isStudent ? '欢迎回到课堂学习' : '高效管理您的 JupyterHub 集群' }}</p>
      </div>
      
      <el-form ref="loginFormRef" :model="loginForm" :rules="rules" class="login-form" autocomplete="off">
        <el-form-item prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            prefix-icon="User"
            size="large"
            clearable
            class="input-item"
            autocomplete="off"
            @input="checkStudentUsername(loginForm.username)"
            @blur="checkStudentUsername(loginForm.username)"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            prefix-icon="Lock"
            size="large"
            show-password
            class="input-item"
            autocomplete="new-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item v-if="showRealName" prop="realName">
          <el-input
            v-model="loginForm.realName"
            placeholder="请输入真实姓名"
            prefix-icon="User"
            size="large"
            class="input-item"
            autocomplete="off"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            class="login-btn"
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()

const loginFormRef = ref(null)
const loading = ref(false)

const loginForm = reactive({
  username: '',
  password: '',
  realName: ''
})

const showRealName = ref(false)
const isStudent = ref(false)

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }]
}

const checkStudentUsername = (username) => {
  const match = username.match(/^student(\d+)$/)
  if (match) {
    showRealName.value = true
    isStudent.value = true
  } else {
    showRealName.value = false
    isStudent.value = false
  }
}

const handleLogin = async () => {
  if (!loginFormRef.value) return

  await loginFormRef.value.validate(async (valid) => {
    if (valid) {
      loading.value = true
      try {
        const success = await userStore.login(loginForm.username, loginForm.password, loginForm.realName)
        if (success) {
          ElMessage.success('登录成功')
          loginForm.password = ''
          loginForm.realName = ''
          router.push('/')
        } else {
          ElMessage.error('用户名或密码错误')
        }
      } catch (e) {
        ElMessage.error('登录失败：' + e.message)
      } finally {
        loading.value = false
      }
    }
  })
}

onMounted(() => {
  loginForm.username = ''
  loginForm.password = ''
  document.querySelectorAll('input').forEach(input => {
    input.autocomplete = 'off'
  })
})
</script>

<style scoped>
.login-container {
  width: 100%;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #0f0c29 0%, #302b63 50%, #24243e 100%);
  position: relative;
  overflow: hidden;
}

.bg-decoration {
  position: absolute;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  opacity: 0.3;
  animation: float 8s ease-in-out infinite;
}

.circle-1 {
  width: 300px;
  height: 300px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  top: -150px;
  left: -150px;
  animation-delay: 0s;
}

.circle-2 {
  width: 200px;
  height: 200px;
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
  top: 20%;
  right: -100px;
  animation-delay: 2s;
}

.circle-3 {
  width: 150px;
  height: 150px;
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
  bottom: 20%;
  left: 10%;
  animation-delay: 4s;
}

.circle-4 {
  width: 100px;
  height: 100px;
  background: linear-gradient(135deg, #43e97b 0%, #38f9d7 100%);
  bottom: -50px;
  right: 20%;
  animation-delay: 6s;
}

.circle-5 {
  width: 180px;
  height: 180px;
  background: linear-gradient(135deg, #fa709a 0%, #fee140 100%);
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  animation-delay: 3s;
  opacity: 0.15;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0) scale(1);
  }
  50% {
    transform: translateY(-20px) scale(1.05);
  }
}

.login-box {
  width: 420px;
  padding: 45px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  position: relative;
  z-index: 10;
}

.logo-section {
  text-align: center;
  margin-bottom: 35px;
}

.logo {
  width: 80px;
  height: 80px;
  margin: 0 auto 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  box-shadow: 0 10px 30px rgba(102, 126, 234, 0.4);
}

.logo-text {
  font-size: 32px;
  color: white;
  font-weight: 700;
}

.title {
  margin-bottom: 8px;
  color: #1a1a2e;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 1px;
}

.subtitle {
  color: #888;
  font-size: 14px;
  margin: 0;
}

.login-form {
  margin-top: 10px;
}

.input-item {
  border-radius: 12px;
  transition: all 0.3s ease;
}

.input-item:focus {
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.login-btn {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 500;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  transition: all 0.3s ease;
}

.login-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 25px rgba(102, 126, 234, 0.4);
}

.login-btn:active {
  transform: translateY(0);
}
</style>
