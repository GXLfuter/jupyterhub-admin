import api from './axios'

export const portForwardApi = {
  getStatus() {
    return api.get('/port-forward/status')
  },
  addForward(student, containerPort, hostPort) {
    return api.post('/port-forward/add', { student, containerPort, hostPort })
  },
  stopForward(student, hostPort) {
    return api.post('/port-forward/stop', { student, hostPort })
  },
  deleteForward(student, hostPort) {
    return api.post('/port-forward/delete', { student, hostPort })
  },
  startMonitor() {
    return api.post('/port-forward/start-monitor')
  },
  stopMonitor() {
    return api.post('/port-forward/stop-monitor')
  },
  getMonitorStatus() {
    return api.get('/port-forward/monitor-status')
  },
  getLogs() {
    return api.get('/port-forward/logs')
  }
}
