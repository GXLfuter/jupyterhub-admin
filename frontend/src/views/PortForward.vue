<template>
  <div class="port-forward-view">
    <el-row :gutter="20" class="top-section">
      <el-col :span="isStudent ? 24 : 16">
        <el-card shadow="hover" class="config-card">
          <template #header>
            <div class="card-header">
              <div class="header-left">
                <div class="header-title">
                  <el-icon :size="24" class="title-icon"><Switch /></el-icon>
                  <span>添加端口转发</span>
                </div>
                <el-tag v-if="isStudent" type="success" size="small">当前用户: {{ userStore.username }}</el-tag>
              </div>
            </div>
          </template>

          <div class="config-desc">
            <p>将宿主机端口映射到学生容器内端口，便于外部访问容器内服务（如模型推理服务）。</p>
          </div>

          <el-form :model="form" :inline="true" class="forward-form">
            <el-form-item label="学生" required>
              <el-input
                v-if="isStudent"
                v-model="form.student"
                disabled
                style="width: 220px;"
              />
              <el-select v-else v-model="form.student" placeholder="选择或输入学生" style="width: 200px;" clearable filterable>
                <el-option label="admin" value="admin" />
                <el-option v-for="i in 60" :key="i" :label="'student' + i" :value="'student' + i" />
              </el-select>
            </el-form-item>

            <el-form-item label="容器端口" required>
              <el-input
                v-model.number="form.containerPort"
                placeholder="如 1025"
                style="width: 140px;"
                type="number"
                :min="1"
                :max="65535"
              />
            </el-form-item>

            <el-form-item>
              <el-tag type="info" size="small" style="margin-top: 6px; padding: 6px 12px;">→</el-tag>
            </el-form-item>

            <el-form-item label="宿主机端口" required>
              <el-input
                v-model.number="form.hostPort"
                placeholder="如 10250"
                style="width: 140px;"
                type="number"
                :min="1"
                :max="65535"
              />
            </el-form-item>

            <el-form-item>
              <el-button
                type="primary"
                :icon="Plus"
                @click="handleAddForward"
                :loading="loading"
                size="large"
              >
                添加转发
              </el-button>
            </el-form-item>
          </el-form>

          <div class="info-box">
            <el-alert
              title="访问地址：192.168.10.101:{宿主机端口} 会转发到容器内对应端口"
              type="info"
              :closable="false"
              show-icon
            />
          </div>
        </el-card>
      </el-col>

      <el-col v-if="isAdmin" :span="8">
        <el-card shadow="hover" class="monitor-card">
          <template #header>
            <div class="card-header">
              <span>监控管理</span>
              <el-tag :type="monitorRunning ? 'success' : 'danger'" size="small">
                {{ monitorRunning ? '运行中' : '已停止' }}
              </el-tag>
            </div>
          </template>

          <div class="monitor-actions">
            <el-button
              v-if="!monitorRunning"
              type="success"
              size="small"
              @click="handleStartMonitor"
              :loading="monitorLoading"
            >
              启动监控
            </el-button>
            <el-button
              v-else
              type="danger"
              size="small"
              @click="handleStopMonitor"
              :loading="monitorLoading"
            >
              停止监控
            </el-button>
          </div>

          <div class="monitor-desc">
            <p>监控会每10秒检查一次转发状态，自动清理失效的转发规则。</p>
          </div>

          <el-button
            type="primary"
            size="small"
            :icon="Refresh"
            @click="loadStatus"
            :loading="statusLoading"
            style="margin-top: 12px; width: 100%;"
          >
            刷新状态
          </el-button>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="isStudent ? 24 : 16">
        <el-card shadow="hover" class="status-card">
          <template #header>
            <div class="card-header">
              <div class="header-left">
                <div class="header-title">
                  <el-icon :size="24" class="title-icon"><Monitor /></el-icon>
                  <span>当前转发列表</span>
                </div>
                <el-tag type="info" size="small">共 {{ forwards.length }} 条</el-tag>
              </div>
              <div class="header-right">
                <el-switch
                  v-model="autoRefresh"
                  active-text="自动刷新"
                  inactive-text="手动"
                  size="small"
                />
              </div>
            </div>
          </template>

          <div v-if="forwards.length > 0">
            <el-table :data="forwards" border>
              <el-table-column prop="student" label="学生" width="120">
                <template #default="{ row }">
                  <el-tag type="primary" size="small">{{ row.student }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="端口映射" min-width="280">
                <template #default="{ row }">
                  <div class="port-map">
                    <span class="host-port">192.168.10.101:{{ row.hostPort }}</span>
                    <el-icon :size="16" class="arrow-icon"><ArrowRight /></el-icon>
                    <span class="container-port">{{ row.student }}:{{ row.containerPort }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag
                    :type="row.status === 'running' ? 'success' : row.status === 'failed' ? 'danger' : 'warning'"
                    size="small"
                  >
                    {{ row.status === 'running' ? '运行中' : row.status === 'failed' ? '已失效' : '未知' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="pid" label="进程PID" width="100" />
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button
                    type="danger"
                    size="small"
                    :icon="Delete"
                    @click="handleDeleteForward(row)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div v-else class="empty-state">
            <el-icon :size="64" style="color: #ccc;"><Switch /></el-icon>
            <p>暂无端口转发规则</p>
            <p class="empty-tip">请在上方添加端口转发规则</p>
          </div>
        </el-card>
      </el-col>

      <el-col v-if="isAdmin" :span="8">
        <el-card shadow="hover" class="logs-card">
          <template #header>
            <div class="card-header">
              <span>监控日志</span>
              <el-button
                type="primary"
                size="small"
                :icon="Refresh"
                @click="loadLogs"
                :loading="logsLoading"
              >
                刷新
              </el-button>
            </div>
          </template>

          <div class="logs-container" ref="logsContainer">
            <div v-for="(log, index) in logs" :key="index" class="log-item">
              {{ log }}
            </div>
            <div v-if="logs.length === 0" class="empty-logs">
              暂无日志
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import {
  Switch, Plus, Monitor, ArrowRight, Delete,
  Refresh
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { portForwardApi } from '../api/portForward'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const isAdmin = computed(() => (userStore.role || 'ADMIN').toUpperCase() === 'ADMIN')
const isStudent = computed(() => (userStore.role || 'ADMIN').toUpperCase() === 'STUDENT')

const form = ref({
  student: '',
  containerPort: null,
  hostPort: null
})

const forwards = ref([])
const logs = ref([])
const monitorRunning = ref(false)
const loading = ref(false)
const statusLoading = ref(false)
const monitorLoading = ref(false)
const logsLoading = ref(false)
const autoRefresh = ref(true)
let refreshTimer = ref(null)
const logsContainer = ref(null)

const loadStatus = async () => {
  statusLoading.value = true
  try {
    const response = await portForwardApi.getStatus()
    if (response.code === 200) {
      forwards.value = response.data
    }
  } catch (e) {
    console.error('加载转发状态失败', e)
  } finally {
    statusLoading.value = false
  }
}

const loadMonitorStatus = async () => {
  try {
    const response = await portForwardApi.getMonitorStatus()
    if (response.code === 200) {
      monitorRunning.value = response.data.running
    }
  } catch (e) {
    console.error('加载监控状态失败', e)
  }
}

const loadLogs = async () => {
  logsLoading.value = true
  try {
    const response = await portForwardApi.getLogs()
    if (response.code === 200) {
      logs.value = response.data.logs || []
      nextTick(() => {
        if (logsContainer.value) {
          logsContainer.value.scrollTop = logsContainer.value.scrollHeight
        }
      })
    }
  } catch (e) {
    console.error('加载日志失败', e)
  } finally {
    logsLoading.value = false
  }
}

const handleAddForward = async () => {
  if (!form.value.student) {
    ElMessage.warning('请选择学生')
    return
  }
  if (!form.value.containerPort) {
    ElMessage.warning('请输入容器端口')
    return
  }
  if (!form.value.hostPort) {
    ElMessage.warning('请输入宿主机端口')
    return
  }

  try {
    loading.value = true
    const response = await portForwardApi.addForward(
      form.value.student,
      form.value.containerPort,
      form.value.hostPort
    )
    if (response.code === 200) {
      ElMessage.success(response.data.message || '添加成功')
      form.value.student = isStudent.value ? userStore.username : ''
      form.value.containerPort = null
      form.value.hostPort = null
      await loadStatus()
    } else {
      ElMessage.error(response.message || '添加失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    loading.value = false
  }
}

const handleStopForward = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要停止端口转发 192.168.10.101:${row.hostPort} 吗？`,
      '确认停止',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const response = await portForwardApi.stopForward(row.student, parseInt(row.hostPort))
    if (response.code === 200) {
      ElMessage.success(response.data.message || '停止成功')
      await loadStatus()
    } else {
      ElMessage.error(response.message || '停止失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleDeleteForward = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除端口转发规则 192.168.10.101:${row.hostPort} 吗？`,
      '确认删除',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'error'
      }
    )

    const response = await portForwardApi.deleteForward(row.student, parseInt(row.hostPort))
    if (response.code === 200) {
      ElMessage.success(response.data.message || '删除成功')
      await loadStatus()
    } else {
      ElMessage.error(response.message || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleStartMonitor = async () => {
  try {
    monitorLoading.value = true
    const response = await portForwardApi.startMonitor()
    if (response.code === 200) {
      ElMessage.success(response.data.message || '监控已启动')
      monitorRunning.value = true
    } else {
      ElMessage.error(response.message || '启动失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    monitorLoading.value = false
  }
}

const handleStopMonitor = async () => {
  try {
    monitorLoading.value = true
    const response = await portForwardApi.stopMonitor()
    if (response.code === 200) {
      ElMessage.success(response.data.message || '监控已停止')
      monitorRunning.value = false
    } else {
      ElMessage.error(response.message || '停止失败')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  } finally {
    monitorLoading.value = false
  }
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  if (autoRefresh.value) {
    refreshTimer.value = setInterval(() => {
      loadStatus()
      loadLogs()
    }, 3000)
  }
}

const stopAutoRefresh = () => {
  if (refreshTimer.value) {
    clearInterval(refreshTimer.value)
    refreshTimer.value = null
  }
}

watch(autoRefresh, startAutoRefresh)

onMounted(() => {
  // 学生模式：预填当前学生名字，不允许修改
  if (isStudent.value) {
    form.value.student = userStore.username
  }
  loadStatus()
  loadMonitorStatus()
  loadLogs()
  setTimeout(() => {
    startAutoRefresh()
  }, 100)
})

onUnmounted(() => {
  stopAutoRefresh()
})
</script>

<style scoped>
.port-forward-view {
  padding: 20px 0;
  min-height: 100%;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ec 100%);
}

.top-section {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 700;
  color: #1f2d3d;
}

.title-icon {
  color: #409eff;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ==== 配置卡片 ==== */
.config-card {
  height: 100%;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.config-card :deep(.el-card__header) {
  border-bottom: 2px solid #f0f0f0;
  padding: 16px 20px;
}

.config-card :deep(.el-card__body) {
  padding: 20px;
}

.config-desc {
  margin-bottom: 20px;
  color: #666;
  font-size: 14px;
  line-height: 1.6;
}

.forward-form {
  margin-bottom: 20px;
}

.info-box {
  margin-top: 12px;
}

/* ==== 监控卡片 ==== */
.monitor-card {
  height: 100%;
  border-radius: 12px;
}

.monitor-actions {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.monitor-desc {
  color: #666;
  font-size: 13px;
  line-height: 1.6;
}

.monitor-desc p {
  margin: 0;
}

/* ==== 状态卡片 ==== */
.status-card {
  height: 100%;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
}

.status-card :deep(.el-card__header) {
  border-bottom: 2px solid #f0f0f0;
  padding: 16px 20px;
}

.status-card :deep(.el-card__body) {
  padding: 20px;
}

.port-map {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.host-port {
  font-weight: 600;
  color: #409eff;
  font-size: 14px;
}

.container-port {
  color: #67c23a;
  font-size: 14px;
}

.arrow-icon {
  color: #909399;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 0;
  color: #ccc;
}

.empty-state p {
  margin-top: 16px;
  font-size: 16px;
  color: #909399;
}

.empty-tip {
  font-size: 13px !important;
  color: #c0c4cc !important;
  margin-top: 8px !important;
}

/* ==== 日志卡片 ==== */
.logs-card {
  height: 100%;
  border-radius: 12px;
}

.logs-container {
  max-height: 350px;
  overflow-y: auto;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
}

.log-item {
  padding: 4px 0;
  border-bottom: 1px solid #f0f0f0;
}

.log-item:last-child {
  border-bottom: none;
}

.empty-logs {
  text-align: center;
  color: #909399;
  padding: 40px 0;
}
</style>
