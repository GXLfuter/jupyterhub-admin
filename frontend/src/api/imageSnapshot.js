import api from './axios'

export const imageSnapshotApi = {
  getStatus() {
    return api.get('/image-snapshot/status')
  },
  restoreImage(fullName) {
    return api.post('/image-snapshot/restore', { fullName })
  },
  createSnapshot(repository, tag) {
    return api.post('/image-snapshot/create', { repository, tag })
  },
  getLogs() {
    return api.get('/image-snapshot/logs')
  },
  autoRestore() {
    return api.post('/image-snapshot/auto-restore')
  },
  deleteBackup(fullName) {
    return api.delete('/image-snapshot/delete-backup', { data: { fullName } })
  },
  clearLogs() {
    return api.delete('/image-snapshot/clear-logs')
  },
  deleteImage(fullName) {
    return api.delete('/image-snapshot/delete-image', { data: { fullName } })
  },
  cleanDangling() {
    return api.delete('/image-snapshot/clean-dangling')
  },
  getDockerHealth() {
    return api.get('/image-snapshot/docker-health')
  }
}