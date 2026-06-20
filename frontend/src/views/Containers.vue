<template>
  <div class="files-page">
    <h2>学生容器列表</h2>
    
    <!-- 操作栏：搜索 + 批量删除 + 批量清理 + 刷新 -->
    <div class="toolbar">
      <el-input
        v-model="searchText"
        placeholder="搜索学生姓名/编号"
        clearable
        style="width: 250px; margin-left: 16px;"
        @input="onSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      
      <div style="margin-left: auto;">
        <el-button
          type="warning"
          size="small"
          :disabled="selectedContainers.length === 0"
          @click="handleBatchCleanupData"
        >
          批量清理数据 ({{ selectedContainers.length }})
        </el-button>
        <el-button
          type="danger"
          size="small"
          :disabled="selectedContainers.length === 0"
          @click="handleBatchDeleteContainer"
        >
          批量删除容器 ({{ selectedContainers.length }})
        </el-button>
        <el-button type="primary" size="small" @click="refreshContainers" style="margin-left: 8px;">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

    <el-table 
      :data="paginatedData" 
      stripe 
      v-loading="loading"
      :row-key="(row) => row.username"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" />
      <el-table-column prop="name" label="容器名称" width="180" />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="status" label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'info'" size="small">
            {{ row.online ? '运行中' : '已停止' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="size" label="容器大小" width="180">
        <template #default="{ row }">
          <span v-if="row.size">{{ row.size }}</span>
          <span v-else style="color: #999;">N/A</span>
        </template>
      </el-table-column>
      <el-table-column prop="uptime" label="运行时间" width="140">
        <template #default="{ row }">
          <span v-if="row.uptime && row.uptime !== 'N/A'">{{ row.uptime }}</span>
          <span v-else style="color: #999;">N/A</span>
        </template>
      </el-table-column>
      <el-table-column prop="directorySize" label="磁盘大小" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.directorySize">{{ row.directorySize }}</span>
          <span v-else style="color: #999;">N/A</span>
        </template>
      </el-table-column>
      <el-table-column prop="image" label="镜像" align="center" header-align="center" width="140" show-overflow-tooltip />
      <el-table-column prop="created" label="创建时间" width="200">
        <template #default="{ row }">
          {{ formatCreatedTime(row.created) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button
            type="danger"
            size="small"
            @click="handleDeleteContainer(row)"
            :loading="row.deleting"
          >
            删除容器
          </el-button>
          <el-button
            type="warning"
            size="small"
            @click="handleCleanupData(row)"
            :loading="row.cleaning"
          >
            清理数据
          </el-button>
          <el-button
            type="danger"
            size="small"
            plain
            @click="handleDeleteAll(row)"
          >
            删除全部
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页组件 -->
    <div class="pagination-area">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 40, 60, 100]"
        :total="filteredContainers.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>

    <div class="summary">
      <span>在线容器：{{ onlineCount }} 个</span>
      <span style="margin-left: 20px;">总容器数：{{ containers.length }} 个</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { containerApi } from '../api/container'
import { Refresh, Search } from '@element-plus/icons-vue'

const containers = ref([])
const loading = ref(false)
const searchText = ref('')

const selectedContainers = ref([])

// 分页相关
const currentPage = ref(1)
const pageSize = ref(10)

const filteredContainers = computed(() => {
  if (!searchText.value) return containers.value
  const keyword = searchText.value.toLowerCase()
  return containers.value.filter(c => c.username.toLowerCase().includes(keyword))
})

// 分页后的数据
const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredContainers.value.slice(start, end)
})

const onlineCount = computed(() => {
  return containers.value.filter(c => c.online).length
})

const onSearch = () => {
  currentPage.value = 1
}

// 格式化创建时间
const formatCreatedTime = (timeStr) => {
  if (!timeStr) return 'N/A'
  // 去掉 +0800 CST，只显示日期时间
  return timeStr.replace(/ \+0800 CST$/, '')
}

const refreshContainers = async () => {
  loading.value = true
  try {
    const response = await containerApi.listContainers()
    if (response.code === 200) {
      containers.value = response.data.map(c => ({ ...c, deleting: false, cleaning: false }))
      currentPage.value = 1
    }
  } catch (e) {
    ElMessage.error('获取容器列表失败')
  } finally {
    loading.value = false
  }
}

const handleSelectionChange = (val) => {
  selectedContainers.value = val
}

const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
}

const handleCurrentChange = (page) => {
  currentPage.value = page
}

// 删除容器（保留数据）
const handleDeleteContainer = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${row.username} 的容器吗？数据目录将保留。`,
      '确认删除容器',
      { type: 'warning' }
    )

    row.deleting = true
    const response = await containerApi.deleteContainer(row.username)
    if (response.code === 200) {
      ElMessage.success('容器已删除，数据保留')
      refreshContainers()
    } else {
      ElMessage.error(response.msg || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  } finally {
    row.deleting = false
  }
}

const handleCleanupData = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要清理 ${row.username} 的数据目录吗？容器将保留。`,
      '确认清理数据',
      { type: 'warning' }
    );

    row.cleaning = true;
    const response = await containerApi.cleanupStudentData(row.username);
    if (response.code === 200) {
      ElMessage.success('数据已清理，容器保留');
      refreshContainers();
    } else {
      ElMessage.error(response.msg || '清理失败');
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清理失败');
    }
  } finally {
    row.cleaning = false;
  }
};

// 删除全部（容器+数据）
const handleDeleteAll = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${row.username} 的容器和数据吗？此操作不可逆！`,
      '危险操作',
      { type: 'error', confirmButtonText: '确认删除' }
    )

    const response = await containerApi.deleteContainerAndData(row.username)
    if (response.code === 200) {
      ElMessage.success('容器和数据已删除')
      refreshContainers()
    } else {
      ElMessage.error(response.msg || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 批量删除容器
const handleBatchDeleteContainer = async () => {
  if (selectedContainers.value.length === 0) {
    ElMessage.warning('请选择要删除的容器')
    return
  }

  const usernames = selectedContainers.value.map(c => c.username)
  
  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedContainers.value.length} 个容器吗？数据目录将保留。`,
      '确认批量删除容器',
      { type: 'warning' }
    )

    const response = await containerApi.batchDeleteContainers(usernames)
    if (response.code === 200) {
      ElMessage.success(response.msg || '删除成功')
      refreshContainers()
      selectedContainers.value = []
    } else {
      ElMessage.error(response.msg || '批量删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量删除失败')
    }
  }
}

// 批量清理数据
const handleBatchCleanupData = async () => {
  if (selectedContainers.value.length === 0) {
    ElMessage.warning('请选择要清理数据的容器')
    return
  }

  const usernames = selectedContainers.value.map(c => c.username)
  
  try {
    await ElMessageBox.confirm(
      `确定要清理选中的 ${selectedContainers.value.length} 个学生的数据吗？容器将保留。`,
      '确认批量清理数据',
      { type: 'warning' }
    )

    const response = await containerApi.batchCleanupStudentData(usernames)
    if (response.code === 200) {
      ElMessage.success(response.msg || '清理成功')
      selectedContainers.value = []
    } else {
      ElMessage.error(response.msg || '批量清理失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量清理失败')
    }
  }
}

onMounted(() => {
  refreshContainers()
})
</script>

<style scoped>
.files-page {
  padding: 20px;
}
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
  margin-top: 20px;
}
.pagination-area {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
.summary {
  margin-top: 15px;
  color: #666;
  font-size: 14px;
}
</style>