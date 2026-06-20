import api from './axios'

export const taskApi = {
  executeCleanup(request) {
    return api.post('/tasks/cleanup', request)
  },
  createScheduledTask(name, usernames, cron) {
    return api.post('/tasks/schedule', { name, usernames, cron })
  },
  getScheduledTasks() {
    return api.get('/tasks/scheduled')
  },
  deleteScheduledTask(taskId) {
    return api.delete(`/tasks/scheduled/${taskId}`)
  },
  toggleScheduledTask(taskId, enabled) {
    return api.put(`/tasks/scheduled/${taskId}?enabled=${enabled}`)
  },
  getTaskHistory() {
    return api.get('/tasks/history')
  },
  clearTaskHistory() {
    return api.delete('/tasks/history')
  }
}
