import api from './axios'

export const monitorApi = {
  getStats() {
    return api.get('/monitor/stats')
  }
}
