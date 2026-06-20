import api from './axios'

export const containerApi = {
  listContainers() {
    return api.get('/containers')
  },
  listOnlineContainers() {
    return api.get('/containers/online')
  },
  getContainer(username) {
    return api.get(`/containers/${username}`)
  },
  // 删除容器（保留数据）
  deleteContainer(username) {
    return api.delete(`/containers/${username}`)
  },
  // 清理数据（只删数据，不删容器）
  cleanupStudentData(username) {
    return api.delete(`/containers/${username}/data`)
  },
  // 同时删除容器和数据
  deleteContainerAndData(username) {
    return api.delete(`/containers/${username}/all`)
  },
  getStudentList() {
    return api.get('/containers/students')
  },
  // 清理所有学生数据
  cleanupAllStudentData() {
    return api.delete('/containers/cleanup/data')
  },
  // 批量删除容器
  batchDeleteContainers(usernames) {
    return api.delete('/containers/batch', { data: { usernames } })
  },
  // 批量清理数据
  batchCleanupStudentData(usernames) {
    return api.delete('/containers/batch/data', { data: { usernames } })
  }
}
