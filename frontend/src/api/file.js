import api from './axios'

export const fileApi = {
  listFiles(path) {
    const params = path ? `?path=${encodeURIComponent(path)}` : ''
    return api.get('/files/list' + params)
  },
  uploadFile(formData) {
    return api.post('/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  deleteFile(filepath) {
    return api.delete('/files?filepath=' + encodeURIComponent(filepath))
  }
}
