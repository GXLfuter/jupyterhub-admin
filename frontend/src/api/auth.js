import api from './axios'

export const authApi = {
  login(username, password) {
    return api.post('/auth/login', { username, password })
  },
  validate() {
    return api.get('/auth/validate')
  },
  logout() {
    return api.post('/auth/logout')
  }
}
