import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '../api/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const role = ref(localStorage.getItem('role') || '')
  const realName = ref(localStorage.getItem('realName') || '')

  const login = async (user, pwd, realNameVal = '') => {
    try {
      const response = await authApi.login(user, pwd)
      if (response.code === 200) {
        const data = response.data
        const tokenVal = typeof data === 'string' ? data : (data.token || '')
        const usernameVal = typeof data === 'string' ? user : (data.username || user)
        const roleVal = typeof data === 'string' ? 'ADMIN' : (data.role || 'ADMIN')

        token.value = tokenVal
        username.value = usernameVal
        role.value = roleVal
        realName.value = realNameVal

        localStorage.setItem('token', tokenVal)
        localStorage.setItem('username', usernameVal)
        localStorage.setItem('role', roleVal)
        localStorage.setItem('realName', realNameVal)
        return true
      } else {
        return false
      }
    } catch (e) {
      console.error('Login failed:', e)
      return false
    }
  }

  const logout = async () => {
    try {
      await authApi.logout()
    } catch (e) {
      console.error('Logout failed:', e)
    }
    token.value = ''
    username.value = ''
    role.value = ''
    realName.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    localStorage.removeItem('realName')
  }

  const validateToken = async () => {
    if (!token.value) return false
    try {
      await authApi.validate()
      return true
    } catch (e) {
      token.value = ''
      username.value = ''
      role.value = ''
      realName.value = ''
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('role')
      localStorage.removeItem('realName')
      return false
    }
  }

  return {
    token,
    username,
    role,
    realName,
    login,
    logout,
    validateToken
  }
})
