<template>
  <div class="image-snapshot-view">
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-icon :size="20"><Monitor /></el-icon>
            <span>镜像快照管理</span>
            <el-tag v-if="showCacheData" type="info" size="small" style="margin-left: 8px;">缓存</el-tag>
            <!-- Docker 运行状态指示器 -->
            <el-tag
              v-if="dockerHealth.running"
              type="success"
              size="small"
              effect="light"
              style="margin-left: 12px;"
            >
              Docker 正常运行
            </el-tag>
            <el-tag
              v-else-if="dockerHealth.cooldown"
              type="warning"
              size="small"
              effect="light"
              style="margin-left: 12px;"
            >
              Docker 刚刚重启（{{ dockerHealth.cooldownRemainingSec }} 秒后开始恢复）
            </el-tag>
            <el-tag
              v-else
              type="danger"
              size="small"
              effect="light"
              style="margin-left: 12px;"
            >
              Docker 未运行 - 正在尝试启动
            </el-tag>
          </div>
          <div class="header-right">
            <el-switch v-model="autoRefresh" active-text="自动刷新" inactive-text="手动" size="small" />
            <el-button type="primary" size="small" :icon="Refresh" @click="loadData(true)">刷新</el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchText"
          placeholder="搜索镜像名称"
          clearable
          :prefix-icon="Search"
          style="width: 300px;"
        />
        <el-button
          type="success"
          size="small"
          @click="handleBatchRestore"
          :disabled="selectedRows.length === 0"
        >
          批量恢复 ({{ selectedRows.length }})
        </el-button>
        <el-button
          type="danger"
          size="small"
          @click="handleBatchDeleteBackup"
          :disabled="selectedBackupRows.length === 0"
        >
          删除备份 ({{ selectedBackupRows.length }})
        </el-button>
        <el-button
          type="warning"
          size="small"
          @click="handleBatchDeleteImage"
          :disabled="selectedNonBackupRows.length === 0"
        >
          删除镜像 ({{ selectedNonBackupRows.length }})
        </el-button>
        <el-button
          type="info"
          size="small"
          @click="handleCleanDangling"
        >
          清理悬挂
        </el-button>
      </div>

      <el-table
        ref="tableRef"
        :data="paginatedData"
        border
        stripe
        :row-key="(row) => row.fullName"
        @selection-change="handleSelectionChange"
        style="margin-top: 12px;"
      >
        <el-table-column type="selection" width="55" :selectable="(row) => row.status !== 'processing' && row.status !== 'restoring'" />
        <el-table-column prop="fullName" label="镜像名称" min-width="280" show-overflow-tooltip />
        <el-table-column prop="size" label="大小" width="120">
          <template #default="{ row }">
            <span v-if="row.size && row.size !== 'N/A'">{{ row.size }}</span>
            <span v-else style="color: #999;">N/A</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="备份" width="100">
          <template #default="{ row }">
            <el-tag :type="row.hasBackup ? 'success' : 'info'" size="small">
              {{ row.hasBackup ? '已备份' : '未备份' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="backupPath" label="备份路径" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.backupPath" style="font-family: monospace; font-size: 12px;">{{ row.backupPath }}</span>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="snapshotTime" label="快照时间" width="180">
          <template #default="{ row }">
            <span v-if="row.snapshotTime">{{ row.snapshotTime }}</span>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'restoring'"
              type="warning"
              size="small"
              :loading="true"
              disabled
            >
              恢复中
            </el-button>
            <template v-else-if="row.status === 'missing'">
              <el-button
                type="success"
                size="small"
                @click="handleRestore(row)"
              >
                恢复
              </el-button>
              <span class="countdown">将自动恢复</span>
            </template>
            <template v-else-if="row.status === 'waiting_docker'">
              <el-button
                type="info"
                size="small"
                disabled
              >
                等待Docker
              </el-button>
              <span class="countdown">{{ dockerHealth.message }}</span>
            </template>
            <template v-else-if="row.status === 'restore_failed'">
              <el-button
                type="danger"
                size="small"
                @click="handleRestore(row)"
              >
                手动恢复
              </el-button>
              <span class="countdown">自动恢复失败3次</span>
            </template>
            <el-button
              v-else-if="row.status === 'processing'"
              type="warning"
              size="small"
              :loading="true"
              disabled
            >
              正在备份
            </el-button>
            <template v-else-if="!row.hasBackup">
              <el-button
                type="primary"
                size="small"
                @click="handleBackup(row)"
              >
                备份
              </el-button>
              <el-button
                type="danger"
                size="small"
                @click="handleDeleteImage(row)"
                style="margin-left: 8px;"
              >
                删除镜像
              </el-button>
            </template>
            <template v-else>
              <el-tag type="success" size="small" style="margin-right: 8px;">已保护</el-tag>
              <el-button type="danger" size="small" @click="handleDeleteBackup(row)">删除备份</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-area">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 40, 60, 100]"
          :total="filteredImages.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>

      <div class="summary">
        <span>总镜像数：{{ images.length }} 个</span>
        <span style="margin-left: 20px;">缺失镜像：{{ missingCount }} 个</span>
        <span style="margin-left: 20px;">已备份：{{ backedUpCount }} 个</span>
      </div>
    </el-card>

    <el-card shadow="hover" style="margin-top: 20px;">
      <template #header>
        <div class="card-header">
          <span>操作日志</span>
          <div style="display: flex; gap: 8px;">
            <el-button
              type="danger"
              size="small"
              @click="handleClearLogs"
              :disabled="logs.length === 0"
            >
              清空
            </el-button>
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
        </div>
      </template>
      <div class="logs-container">
        <div v-for="(log, index) in logs" :key="index" class="log-item">{{ log }}</div>
        <div v-if="logs.length === 0" style="text-align: center; color: #909399; padding: 20px;">
          暂无日志
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, onUnmounted, watch, nextTick, onActivated } from 'vue'
import { Monitor, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { imageSnapshotApi } from '../api/imageSnapshot'

const images = ref([])
const logs = ref([])
const logsLoading = ref(false)
const autoRefresh = ref(true)
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const selectedRows = ref([])
const showCacheData = ref(false)
const tableRef = ref(null)
const cacheTimestamp = ref(0)
// Docker 运行状态：running / down / recovering (冷却期)
const dockerHealth = reactive({
  running: true,
  cooldown: false,
  cooldownRemainingSec: 0,
  status: 'unknown',
  message: '检查中...'
})
let refreshTimer = null
let refreshDelayTimer = null
let resumeTimer = null
const CACHE_KEY = 'image_snapshot_cache'
const CACHE_EXPIRE = 5 * 60 * 1000 // 缓存5分钟过期

const filteredImages = computed(() => {
  let result = images.value
  if (searchText.value) {
    result = result.filter(img =>
      img.fullName.toLowerCase().includes(searchText.value.toLowerCase()) ||
      (img.repository && img.repository.toLowerCase().includes(searchText.value.toLowerCase()))
    )
  }
  // 前端再次排序：已备份的优先
  return [...result].sort((a, b) => {
    if (a.hasBackup && !b.hasBackup) return -1
    if (!a.hasBackup && b.hasBackup) return 1
    return a.fullName.localeCompare(b.fullName)
  })
})

const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredImages.value.slice(start, end)
})

const missingCount = computed(() => images.value.filter(img => img.status === 'missing').length)
const backedUpCount = computed(() => images.value.filter(img => img.hasBackup).length)
const selectedBackupRows = computed(() => selectedRows.value.filter(row => row.hasBackup))
const selectedNonBackupRows = computed(() => selectedRows.value.filter(row => !row.hasBackup))

const getStatusType = (status) => {
  switch (status) {
    case 'normal': return 'success'
    case 'missing': return 'danger'
    case 'restoring': return 'warning'
    case 'processing': return 'warning'
    case 'waiting_docker': return 'info'
    case 'restore_failed': return 'danger'
    default: return 'info'
  }
}

const getStatusText = (status) => {
  switch (status) {
    case 'normal': return '正常'
    case 'missing': return '缺失'
    case 'restoring': return '恢复中'
    case 'processing': return '正在备份'
    case 'waiting_docker': return '等待Docker就绪'
    case 'restore_failed': return '恢复失败-请手动'
    default: return '未知'
  }
}

const handleSelectionChange = (rows) => {
  selectedRows.value = rows
  // 有选中项时暂停自动刷新
  if (rows.length > 0) {
    pauseAutoRefresh()
  } else {
    resumeAutoRefresh()
  }
}

const pauseAutoRefresh = () => {
  // 清除15秒自动刷新定时器
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
  // 清除5秒延迟刷新定时器
  if (refreshDelayTimer) {
    clearTimeout(refreshDelayTimer)
    refreshDelayTimer = null
  }
  // 清除恢复刷新定时器
  if (resumeTimer) {
    clearTimeout(resumeTimer)
    resumeTimer = null
  }
}

const resumeAutoRefresh = () => {
  if (!autoRefresh.value) return
  // 5秒后恢复自动刷新
  if (resumeTimer) clearTimeout(resumeTimer)
  resumeTimer = setTimeout(() => {
    resumeTimer = null
    if (autoRefresh.value && selectedRows.value.length === 0) {
      startAutoRefresh()
    }
  }, 5000)
}

const loadFromCache = () => {
  try {
    const cached = sessionStorage.getItem(CACHE_KEY)
    if (cached) {
      const data = JSON.parse(cached)
      if (data.images && data.logs) {
        images.value = data.images
        logs.value = data.logs
        cacheTimestamp.value = data.timestamp || 0
        showCacheData.value = true
        scrollLogsToBottom()
        return true
      }
    }
  } catch (e) {
    console.error('读取缓存失败', e)
  }
  return false
}

const saveToCache = () => {
  try {
    const data = {
      images: images.value,
      logs: logs.value,
      timestamp: Date.now()
    }
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(data))
  } catch (e) {
    console.error('保存缓存失败', e)
  }
}

const loadDockerHealth = async () => {
  try {
    const res = await imageSnapshotApi.getDockerHealth()
    if (res.code === 200) {
      Object.assign(dockerHealth, {
        running: res.data.running,
        cooldown: res.data.cooldown,
        cooldownRemainingSec: res.data.cooldownRemainingSec,
        status: res.data.status,
        message: res.data.message
      })
    }
  } catch (e) {
    dockerHealth.running = false
    dockerHealth.message = 'Docker 状态检查失败'
  }
}

const loadData = async (forceRefresh = false) => {
  // 1. 先加载 Docker 状态（快速接口）
  await loadDockerHealth()

  // 如果不是强制刷新，且缓存有效，直接使用缓存
  if (!forceRefresh && cacheTimestamp.value > 0) {
    const elapsed = Date.now() - cacheTimestamp.value
    if (elapsed < CACHE_EXPIRE) {
      const hasCache = loadFromCache()
      if (hasCache) {
        // 5秒后延迟刷新
        scheduleDelayedRefresh()
        return
      }
    }
  }

  try {
    const [statusRes, logsRes] = await Promise.all([
      imageSnapshotApi.getStatus(),
      imageSnapshotApi.getLogs()
    ])
    if (statusRes.code === 200) {
      // ======== 处理中状态保护：后端返回 normal 但本地知道正在备份时，保留 processing ========
      // 注意：对于 restoring 状态，信任后端的 normal 状态（后端已确认镜像在 Docker 中）
      const processingLocal = new Set()
      for (const img of images.value) {
        if (img.status === 'processing') processingLocal.add(img.fullName)
      }

      const newImages = statusRes.data
      for (const img of newImages) {
        // 本地标记为 processing 但后端说 normal + hasBackup=false → 保留 processing（备份还在跑）
        if (processingLocal.has(img.fullName) && img.status === 'normal' && !img.hasBackup) {
          img.status = 'processing'
        }
        // restoring 状态：信任后端返回的 normal（后端已确认镜像实际存在于 Docker 中）
        // 不保留本地的 restoring，避免恢复成功后前端仍显示"恢复中"
      }
      images.value = newImages
    }
    if (logsRes.code === 200) {
      logs.value = logsRes.data.logs || []
      scrollLogsToBottom()
    }
    // 保存缓存
    saveToCache()
    cacheTimestamp.value = Date.now()
    showCacheData.value = false
  } catch (e) {
    console.error('加载数据失败', e)
    // 加载失败时尝试使用缓存
    if (images.value.length === 0) {
      loadFromCache()
    }
  }
}

const scheduleDelayedRefresh = () => {
  if (refreshDelayTimer) clearTimeout(refreshDelayTimer)
  refreshDelayTimer = setTimeout(() => {
    loadData(true)
  }, 5000)
}



const loadLogs = async () => {
  logsLoading.value = true
  try {
    const res = await imageSnapshotApi.getLogs()
    if (res.code === 200) {
      logs.value = res.data.logs || []
      scrollLogsToBottom()
    }
  } catch (e) {
    console.error('加载日志失败', e)
  } finally {
    logsLoading.value = false
  }
}

const handleClearLogs = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要清空所有操作日志吗？此操作不可恢复。',
      '确认清空日志',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'danger'
      }
    )

    const res = await imageSnapshotApi.clearLogs()
    if (res.code === 200) {
      logs.value = []
      ElMessage.success('日志已清空')
      // 清除缓存
      saveToCache()
    } else {
      ElMessage.error(res.message || '清空失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const scrollLogsToBottom = () => {
  nextTick(() => {
    const container = document.querySelector('.logs-container')
    if (container) {
      container.scrollTop = container.scrollHeight
    }
  })
}

const handleRestore = async (row) => {
  // 立即标记为恢复中，避免后端状态延迟导致的错误提示
  const index = images.value.findIndex(img => img.fullName === row.fullName)
  if (index !== -1) {
    images.value[index].status = 'restoring'
  }

  try {
    const res = await imageSnapshotApi.restoreImage(row.fullName)
    if (res.code === 200) {
      if (res.data && res.data.restoring) {
        ElMessage.info(res.data.message || '镜像正在恢复中，请稍候')
      } else {
        ElMessage.success('恢复成功')
      }
      // 1.5秒后从后端刷新最新状态
      setTimeout(() => {
        loadData(true)
      }, 1500)
    } else {
      ElMessage.error(res.message || '恢复失败')
      await loadData(true)
    }
  } catch (e) {
    ElMessage.error('操作失败')
    await loadData(true)
  }
}

const handleBackup = async (row) => {
  try {
    const index = images.value.findIndex(img => img.fullName === row.fullName)
    if (index !== -1) {
      images.value[index].status = 'processing'
    }

    const res = await imageSnapshotApi.createSnapshot(row.repository, row.tag)
    if (res.code === 200) {
      ElMessage.success(res.message || '备份任务已提交，请等待后台执行')
    } else {
      // 后端拒绝（幂等性检查失败）：撤销本地 processing 标记
      if (index !== -1) {
        images.value[index].status = 'normal'
      }
      ElMessage.error(res.message || '备份提交失败')
    }
  } catch (e) {
    // 异常时也撤销本地 processing 标记
    const index = images.value.findIndex(img => img.fullName === row.fullName)
    if (index !== -1) {
      images.value[index].status = 'normal'
    }
    ElMessage.error('操作失败')
  }
}

const handleDeleteImage = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除镜像 "${row.fullName}" 吗？此操作不可恢复。`,
      '确认删除镜像',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'danger'
      }
    )

    const res = await imageSnapshotApi.deleteImage(row.fullName)
    if (res.code === 200) {
      ElMessage.success('删除成功')
      await loadData(true)
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleCleanDangling = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要清理所有 <none> 悬挂镜像吗？此操作不可恢复。',
      '清理悬挂镜像',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const res = await imageSnapshotApi.cleanDangling()
    if (res.code === 200) {
      ElMessage.success(res.message || '清理成功')
      await loadData(true)
    } else {
      ElMessage.error(res.message || '清理失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleDeleteBackup = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除镜像 "${row.fullName}" 的备份文件吗？`,
      '确认删除备份',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    const res = await imageSnapshotApi.deleteBackup(row.fullName)
    if (res.code === 200) {
      ElMessage.success('删除成功')
      await loadData(true)
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleBatchDeleteImage = async () => {
  if (selectedNonBackupRows.value.length === 0) {
    ElMessage.warning('请先选择要删除的镜像')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除 ${selectedNonBackupRows.value.length} 个镜像吗？此操作不可恢复。`,
      '确认批量删除镜像',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'danger'
      }
    )

    let successCount = 0
    for (const row of selectedNonBackupRows.value) {
      try {
        const res = await imageSnapshotApi.deleteImage(row.fullName)
        if (res.code === 200) {
          successCount++
        }
      } catch (e) {
        console.error(`删除镜像失败: ${row.fullName}`, e)
      }
    }

    ElMessage.success(`批量删除完成，成功 ${successCount}/${selectedNonBackupRows.value.length}`)
    selectedRows.value = []
    await loadData(true)
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleBatchDeleteBackup = async () => {
  if (selectedBackupRows.value.length === 0) {
    ElMessage.warning('请先选择要删除备份的镜像')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除 ${selectedBackupRows.value.length} 个镜像的备份文件吗？此操作不可恢复。`,
      '确认批量删除备份',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'danger'
      }
    )

    let successCount = 0
    for (const row of selectedBackupRows.value) {
      try {
        const res = await imageSnapshotApi.deleteBackup(row.fullName)
        if (res.code === 200) {
          successCount++
        }
      } catch (e) {
        console.error(`删除备份失败: ${row.fullName}`, e)
      }
    }

    ElMessage.success(`批量删除完成，成功 ${successCount}/${selectedBackupRows.value.length}`)
    selectedRows.value = []
    await loadData(true)
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('操作失败')
    }
  }
}

const handleBatchRestore = async () => {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先选择要恢复的镜像')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要批量恢复 ${selectedRows.value.length} 个镜像吗？`,
      '确认批量恢复',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    let successCount = 0
    let restoringCount = 0
    for (const row of selectedRows.value) {
      try {
        // 先本地标记为恢复中
        const idx = images.value.findIndex(img => img.fullName === row.fullName)
        if (idx !== -1) {
          images.value[idx].status = 'restoring'
        }

        const res = await imageSnapshotApi.restoreImage(row.fullName)
        if (res.code === 200) {
          if (res.data && res.data.restoring) {
            restoringCount++
          } else {
            successCount++
          }
        }
      } catch (e) {
        console.error(`恢复 ${row.fullName} 失败`, e)
      }
    }

    let msg = `批量恢复完成，成功 ${successCount}/${selectedRows.value.length}`
    if (restoringCount > 0) {
      msg += `（${restoringCount} 个正在恢复中）`
    }
    ElMessage.success(msg)
    selectedRows.value = []
    setTimeout(() => {
      loadData(true)
    }, 2000)
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量恢复失败')
    }
  }
}

const startAutoRefresh = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (resumeTimer) {
    clearTimeout(resumeTimer)
    resumeTimer = null
  }
  if (autoRefresh.value && selectedRows.value.length === 0) {
    // 混合刷新模式：每5秒检查一次 Docker 健康状态，每15秒做一次完整数据刷新
    // 避免频繁切换页面时触发太多后端恢复操作
    let tickCount = 0
    refreshTimer = setInterval(async () => {
      tickCount++
      // 先做轻量 Docker 状态检查（快速，不触发恢复）
      await loadDockerHealth()
      // 每第3次 (15s) 做完整数据刷新
      if (tickCount % 3 === 0) {
        loadData(true)
      }
    }, 5000)
  }
}

watch(autoRefresh, startAutoRefresh)
watch(searchText, () => {
  currentPage.value = 1
})

onMounted(() => {
  loadFromCache()
  loadData(true)
  startAutoRefresh()
})

onActivated(() => {
  // 页面激活时：先显示缓存 + 轻量 Docker 健康检查，避免频繁切换页面时触发大量后端操作
  loadFromCache()
  loadDockerHealth()
  // 如果缓存超过 15 秒才做一次完整刷新，避免用户在页面之间切换时反复触发恢复
  const elapsed = Date.now() - cacheTimestamp.value
  if (cacheTimestamp.value === 0 || elapsed > 15000) {
    loadData(true)
  }
  startAutoRefresh()
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (refreshDelayTimer) clearTimeout(refreshDelayTimer)
  if (resumeTimer) clearTimeout(resumeTimer)
  // 页面卸载时保存缓存
  saveToCache()
})
</script>

<style scoped>
.image-snapshot-view {
  padding: 12px 0;
}

.image-snapshot-view :deep(.el-card) {
  border-radius: 8px;
}

.image-snapshot-view :deep(.el-card__header) {
  padding: 10px 16px;
}

.image-snapshot-view :deep(.el-card__body) {
  padding: 12px 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.pagination-area {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.summary {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}

.logs-container {
  height: 220px;
  overflow-y: auto;
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #606266;
  padding: 8px;
  background: #fafafa;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
  line-height: 1.5;
}

.log-item {
  padding: 3px 0;
  border-bottom: 1px solid #f0f0f0;
}

.log-item:last-child {
  border-bottom: none;
}

.image-snapshot-view :deep(.el-table) {
  font-size: 13px;
}

.image-snapshot-view :deep(.el-table th.el-table__cell) {
  padding: 6px 0;
  background: #f5f7fa;
}

.image-snapshot-view :deep(.el-table td.el-table__cell) {
  padding: 6px 0;
}

.countdown {
  color: #e6a23c;
  font-size: 12px;
  margin-left: 8px;
}
</style>