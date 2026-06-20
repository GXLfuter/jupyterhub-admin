import api from './axios'

export const gpuApi = {
  getStatus() {
    return api.get('/gpu/status')
  },
  getConfig() {
    return api.get('/gpu/config')
  },
  setConfig(value) {
    return api.post('/gpu/config', { value })
  }
}
