<template>
  <div class="gpu-view">
    <el-row :gutter="20" class="config-row">
      <el-col :span="22" :offset="1">
        <div class="config-panel">
          <div class="panel-header">
            <div class="header-left">
              <div class="header-title">
                <el-icon :size="22" class="title-icon"><VideoCamera /></el-icon>
                <span>GPU 分配策略</span>
              </div>
              <el-tag :type="currentConfig === -1 ? 'danger' : 'success'" size="small" class="config-tag">
                当前配置: {{ currentConfig === -1 ? '未知' : modeNames[currentConfig] }}
              </el-tag>
            </div>
            <div class="header-right">
              <el-button type="primary" size="small" :icon="Refresh" @click="loadAll" :loading="statusLoading">
                刷新
              </el-button>
            </div>
          </div>

          <div class="strategy-cards">
            <div
              v-for="mode in modes"
              :key="mode.value"
              :class="['strategy-card', { active: currentConfig === mode.value }]"
              @click="handleConfigChange(mode.value)"
            >
              <div class="card-icon" :style="{ background: mode.gradient }">
                <el-icon :size="28"><component :is="mode.icon" /></el-icon>
              </div>
              <div class="card-content">
                <div class="card-title">{{ mode.title }}</div>
                <div class="card-desc">{{ getModeDescription(mode.value) }}</div>
              </div>
              <div class="card-badge" v-if="currentConfig === mode.value">
                <el-icon><Check /></el-icon>
              </div>
            </div>
          </div>

          <el-alert
            title="修改配置将自动重启 JupyterHub 服务，学生端会短暂中断"
            type="warning"
            :closable="false"
            show-icon
            class="alert-box"
          />
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="stats-row" v-if="gpuStatus.available && gpuCount > 0">
      <el-col :span="22" :offset="1">
        <div class="stats-panel">
          <div class="panel-header">
            <div class="header-left">
                  <div class="header-title">
                    <el-icon :size="22" class="title-icon"><DataAnalysis /></el-icon>
                    <span>显卡状态监控</span>
                  </div>
                  <el-tag type="success" size="small">共 {{ gpuCount }} 张 GPU · 驱动 {{ gpuStatus.gpus[0]?.driverVersion || 'N/A' }}</el-tag>
                </div>
                <div class="header-right">
                  <span class="refresh-time">{{ formatRefreshTime() }}</span>
                  <el-switch
                    v-model="autoRefresh"
                    active-text="自动刷新"
                    inactive-text="手动"
                    size="small"
                  />
                </div>
          </div>

          <div class="gpu-cards">
            <div v-for="gpu in gpuStatus.gpus" :key="gpu.index" class="gpu-card">
              <div class="gpu-card-header">
                <div class="gpu-index-badge">GPU {{ gpu.index }}</div>
                <el-tag
                  :type="parseInt(gpu.temperature) > 80 ? 'danger' : parseInt(gpu.temperature) > 60 ? 'warning' : 'info'"
                  size="small"
                >
                  {{ gpu.temperature }}
                </el-tag>
              </div>
              <div class="gpu-name">{{ gpu.name }}</div>

              <div class="gpu-metrics">
                <div class="metric-row">
                  <span class="metric-label">显存</span>
                  <div class="metric-bar-wrap">
                    <div class="metric-bar">
                      <div
                        class="metric-bar-fill"
                        :style="getMemoryBarStyle(gpu.memoryPercent)"
                      ></div>
                    </div>
                    <span class="metric-value">{{ gpu.memoryUsed }} / {{ gpu.memoryTotal }}</span>
                  </div>
                </div>

                <div class="metric-row">
                  <span class="metric-label">算力</span>
                  <div class="metric-bar-wrap">
                    <div class="metric-bar">
                      <div
                        class="metric-bar-fill util-bar"
                        :style="getUtilBarStyle(gpu.utilization)"
                      ></div>
                    </div>
                    <span class="metric-value">{{ gpu.utilization }}</span>
                  </div>
                </div>

                <div class="metric-row">
                  <span class="metric-label">功耗</span>
                  <div class="metric-bar-wrap">
                    <div class="metric-bar">
                      <div
                        class="metric-bar-fill"
                        :style="getPowerBarStyle(gpu.powerPercent)"
                      ></div>
                    </div>
                    <span class="metric-value">{{ gpu.powerDraw }} / {{ gpu.powerLimit }}</span>
                  </div>
                </div>
              </div>

              <div class="gpu-footer">
                <el-tag type="success" size="small">
                  空闲 {{ gpu.memoryFree }}
                </el-tag>
              </div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="empty-row" v-else>
      <el-col :span="22" :offset="1">
        <div class="empty-panel">
          <el-icon :size="64" style="color: #c0c4cc; margin-bottom: 16px;"><Monitor /></el-icon>
          <p>{{ gpuStatus.message || '未检测到 GPU 设备' }}</p>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import {
  VideoCamera, DataAnalysis, Refresh, Check, Monitor,
  Cpu, Connection, User, SwitchButton
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { gpuApi } from '../api/gpu'

const gpuStatus = ref({ available: false, gpus: [] })
const currentConfig = ref(-1)
const loading = ref(false)
const statusLoading = ref(false)
const autoRefresh = ref(true)
const lastRefreshTime = ref(null)
let refreshTimer = ref(null)

const modeNames = {
  0: '模式一：每人一张卡',
  1: '模式二：每两人两张卡',
  2: '模式三：student1 用所有卡',
  3: '模式四：全部禁用 GPU'
}

const modes = ref([
  {
    value: 0,
    title: '每人一张卡',
    icon: 'Cpu',
    gradient: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
  },
  {
    value: 1,
    title: '每两人两张卡',
    icon: 'Connection',
    gradient: 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)'
  },
  {
    value: 2,
    title: 'student1 用所有卡',
    icon: 'User',
    gradient: 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)'
  },
  {
    value: 3,
    title: '全部禁用 GPU',
    icon: 'SwitchButton',
    gradient: 'linear-gradient(135deg, #434343 0%, #000000 100%)'
  }
])

const gpuCount = computed(() => {
  if (gpuStatus.value && gpuStatus.value.gpus && gpuStatus.value.gpus.length > 0) {
    return gpuStatus.value.gpus.length
  }
  return 0
})

const getModeDescription = (mode) => {
  const count = gpuCount.value
  // 如果还没有检测到卡数，显示通用描述
  if (count === 0) {
    if (mode === 0) return '每位学生分配一张 GPU 卡（加载中...）'
    if (mode === 1) return 'student1 独占前两卡，其他学生 CPU 环境'
    if (mode === 2) return 'student1 独占全部 GPU 卡，其他学生 CPU 环境'
    if (mode === 3) return '所有学生统一使用 CPU 环境'
    return ''
  }

  if (mode === 0) {
    // 每人一张卡：根据实际卡数分配（最多支持 student1~4）
    const maxStudents = Math.min(count, 4)
    const alloc = []
    for (let i = 0; i < maxStudents; i++) {
      alloc.push(`student${i + 1}→${i}号卡`)
    }
    // student5+ 都是 CPU
    return alloc.join('，') + '，student5+→CPU 环境'
  }
  if (mode === 1) {
    // 每两人两张卡：student1→[0,1]，student2→[2,3]，其他学生 CPU
    if (count >= 2) {
      if (count === 2) {
        return 'student1→0,1 两卡，student2→CPU 环境'
      }
      // 4 张或更多卡
      if (count === 4) {
        return 'student1→0,1 两卡，student2→2,3 两卡，student3、4→CPU 环境'
      }
      // 3 张卡
      if (count === 3) {
        return 'student1→0,1 两卡，student2→2 号卡，student3、4→CPU 环境'
      }
      return `student1→0,1 两卡，student2→2,3 两卡，其余学生 CPU 环境`
    }
    if (count === 1) {
      return 'student1→0 号卡，student2~4→CPU 环境'
    }
    return 'student1→前两卡，student2→后两卡，其他学生 CPU 环境'
  }
  if (mode === 2) {
    // student1 用所有卡
    if (count === 1) {
      return 'student1 独占 1 张卡，其他学生 CPU 环境'
    }
    return `student1 独占全部 ${count} 张卡，其他学生 CPU 环境`
  }
  if (mode === 3) {
    return '所有学生统一使用 CPU 环境'
  }
  return ''
}

// 根据使用率返回颜色：低(蓝紫) → 中(粉橙) → 高(红)
const getBarGradient = (percent, type) => {
  const p = parseFloat(percent) || 0
  // 显存：蓝紫系
  if (type === 'memory') {
    if (p >= 80) return 'linear-gradient(90deg, #f5576c 0%, #fa709a 100%)'
    if (p >= 50) return 'linear-gradient(90deg, #f093fb 0%, #f5576c 100%)'
    return 'linear-gradient(90deg, #667eea 0%, #764ba2 100%)'
  }
  // 算力：粉红系
  if (type === 'util') {
    if (p >= 80) return 'linear-gradient(90deg, #fa709a 0%, #f5576c 100%)'
    if (p >= 50) return 'linear-gradient(90deg, #fbc2eb 0%, #f093fb 100%)'
    return 'linear-gradient(90deg, #a8edea 0%, #4facfe 100%)'
  }
  // 功耗：青色系
  if (type === 'power') {
    if (p >= 80) return 'linear-gradient(90deg, #fa709a 0%, #f5576c 100%)'
    if (p >= 50) return 'linear-gradient(90deg, #fbc2eb 0%, #f093fb 100%)'
    return 'linear-gradient(90deg, #4facfe 0%, #00f2fe 100%)'
  }
  return '#409eff'
}

const getMemoryBarStyle = (percent) => {
  const p = parseFloat(percent) || 0
  return {
    width: p + '%',
    background: getBarGradient(p, 'memory'),
    opacity: p === 0 ? 0 : 1
  }
}

const getUtilBarStyle = (percent) => {
  const p = parseFloat(percent) || 0
  return {
    width: p + '%',
    background: getBarGradient(p, 'util'),
    opacity: p === 0 ? 0 : 1
  }
}

const getPowerBarStyle = (percent) => {
  const p = parseFloat(percent) || 0
  return {
    width: p + '%',
    background: getBarGradient(p, 'power'),
    opacity: p === 0 ? 0 : 1
  }
}

const formatRefreshTime = () => {
  if (!lastRefreshTime.value) return ''
  const now = new Date()
  const diff = Math.floor((now - lastRefreshTime.value) / 1000)
  if (diff === 0) return '刚刚刷新'
  if (diff < 60) return `${diff}秒前刷新`
  return `${Math.floor(diff / 60)}分钟前刷新`
}

const loadGpuStatus = async () => {
  statusLoading.value = true
  try {
    const response = await gpuApi.getStatus()
    if (response.code === 200) {
      gpuStatus.value = response.data
      lastRefreshTime.value = new Date()
    }
  } catch (e) {
    console.error('加载GPU状态失败', e)
  } finally {
    statusLoading.value = false
  }
}

const loadGpuConfig = async () => {
  try {
    const response = await gpuApi.getConfig()
    if (response.code === 200) {
      currentConfig.value = response.data.value
    }
  } catch (e) {
    console.error('加载GPU配置失败', e)
  }
}

const loadAll = async () => {
  await loadGpuStatus()
  await loadGpuConfig()
}

const handleConfigChange = async (value) => {
  if (loading.value) return
  if (currentConfig.value === value) {
    ElMessage.info('当前已是该配置，无需修改')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要切换到「${modeNames[value]}」吗？修改后将自动重启 JupyterHub 服务，会短暂中断所有学生的使用。`,
      '确认修改 GPU 配置',
      {
        confirmButtonText: '确定修改',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    loading.value = true
    const response = await gpuApi.setConfig(value)
    if (response.code === 200) {
      currentConfig.value = value
      ElMessage.success(response.data.message || '配置已更新成功，服务正在重启')
    } else {
      ElMessage.error(response.message || '配置更新失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败，请重试')
    }
  } finally {
    loading.value = false
  }
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  if (autoRefresh.value) {
    refreshTimer.value = setInterval(() => {
      loadGpuStatus()
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
  loadAll()
  setTimeout(() => {
    startAutoRefresh()
  }, 100)
})

onUnmounted(() => {
  stopAutoRefresh()
})
</script>

<style scoped>
.gpu-view {
  padding: 20px 0;
  min-height: 100%;
  background: #f5f7fa;
}

/* ==== 公共样式 ==== */
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 2px solid #f0f2f5;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.refresh-time {
  font-size: 12px;
  color: #909399;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: 700;
  color: #1f2d3d;
}

.title-icon {
  color: #409eff;
}

.config-tag {
  font-size: 13px;
}

/* ==== 配置面板 ==== */
.config-row {
  margin-bottom: 20px;
}

.config-panel {
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.04);
}

.strategy-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.strategy-card {
  position: relative;
  background: #fafbfc;
  border: 2px solid #ebeef5;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  gap: 14px;
  align-items: center;
}

.strategy-card:hover {
  border-color: #409eff;
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(64, 158, 255, 0.15);
}

.strategy-card.active {
  border-color: #409eff;
  background: linear-gradient(135deg, #ecf5ff 0%, #fafbfc 100%);
  box-shadow: 0 8px 24px rgba(64, 158, 255, 0.2);
}

.card-icon {
  width: 54px;
  height: 54px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.card-content {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2d3d;
  margin-bottom: 4px;
  white-space: nowrap;
}

.card-desc {
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
}

.card-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #67c23a;
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}

.alert-box {
  margin-top: 8px;
}

/* ==== 统计面板 ==== */
.stats-row {
  margin-bottom: 20px;
}

.stats-panel {
  background: #fff;
  border-radius: 16px;
  padding: 28px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.04);
}

.gpu-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.gpu-card {
  background: linear-gradient(145deg, #ffffff 0%, #f8fafc 100%);
  border: 1px solid #ebeef5;
  border-radius: 14px;
  padding: 18px;
  transition: all 0.3s ease;
}

.gpu-card:hover {
  border-color: #409eff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.gpu-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.gpu-index-badge {
  font-size: 13px;
  font-weight: 700;
  color: #409eff;
  background: #ecf5ff;
  padding: 4px 12px;
  border-radius: 8px;
}

.gpu-name {
  font-size: 15px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px dashed #ebeef5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gpu-metrics {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}

.metric-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.metric-label {
  font-size: 12px;
  color: #909399;
  width: 32px;
  flex-shrink: 0;
}

.metric-bar-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.metric-bar {
  flex: 1;
  height: 8px;
  background: #ebeef5;
  border-radius: 4px;
  overflow: hidden;
  min-width: 80px;
}

.metric-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.6s ease, background 0.4s ease, opacity 0.3s ease;
  min-width: 0;
}

.util-bar {
  /* 颜色由内联 style 根据实际使用率动态设置 */
}

.metric-value {
  font-size: 11px;
  color: #606266;
  font-weight: 500;
  white-space: nowrap;
  min-width: 90px;
  text-align: right;
}

.gpu-footer {
  margin-top: 4px;
}

/* ==== 空状态 ==== */
.empty-row {
  margin-bottom: 20px;
}

.empty-panel {
  background: #fff;
  border-radius: 16px;
  padding: 60px 20px;
  text-align: center;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.04);
}

.empty-panel p {
  color: #909399;
  font-size: 14px;
  margin: 0;
}

/* 响应式调整 */
@media (max-width: 1400px) {
  .strategy-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .strategy-cards {
    grid-template-columns: 1fr;
  }

  .panel-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .header-title {
    font-size: 18px;
  }
}
</style>
