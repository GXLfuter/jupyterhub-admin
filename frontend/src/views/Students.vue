<template>
  <div class="files-page">
    <h2>在线学生列表</h2>
    
    <!-- 操作栏：搜索 + 批量删除 + 刷新 -->
    <div class="toolbar">
      <el-input
        v-model="searchText"
        placeholder="搜索学生名字..."
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
          type="danger"
          size="small"
          :disabled="selectedStudents.length === 0"
          @click="handleBatchDelete"
        >
          批量删除 ({{ selectedStudents.length }})
        </el-button>
        <el-button type="primary" @click="refreshStudents" style="margin-left: 8px;">
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
      <el-table-column prop="username" label="用户名" width="150" fixed="left" />
      <el-table-column prop="containerName" label="容器名称" width="180" />
      <el-table-column prop="status" label="容器状态" width="120">
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
      <el-table-column prop="image" label="镜像" min-width="200" show-overflow-tooltip />
      <el-table-column prop="created" label="创建时间" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            type="danger"
            size="small"
            @click="handleDelete(row)"
          >
            删除容器
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
        :total="filteredStudents.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>

    <div class="summary">
      <el-statistic title="在线学生" :value="onlineCount" />
      <el-statistic title="总学生数" :value="students.length" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { containerApi } from '../api/container'

const students = ref([])
const loading = ref(false)
const searchText = ref('')

const selectedStudents = ref([])

// 分页相关
const currentPage = ref(1)
const pageSize = ref(10)

const filteredStudents = computed(() => {
  if (!searchText.value) return students.value
  const keyword = searchText.value.toLowerCase()
  return students.value.filter(s => s.username.toLowerCase().includes(keyword))
})

// 分页后的数据
const paginatedData = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredStudents.value.slice(start, end)
})

const onlineCount = computed(() => {
  return students.value.filter(s => s.online).length
})

const onSearch = () => {
  currentPage.value = 1
}

const refreshStudents = async () => {
  loading.value = true
  try {
    const response = await containerApi.listOnlineContainers()
    if (response.code === 200) {
        students.value = response.data.map(c => ({
          username: c.username,
          containerName: c.name,
          status: c.status,
          online: c.online,
          created: c.created,
          image: c.image,
          id: c.id,
          size: c.size,
          uptime: c.uptime,
          ports: c.ports,
          imageSize: c.imageSize,
          mountCount: c.mountCount,
          cpuUsage: c.cpuUsage,
          memoryUsage: c.memoryUsage,
          directorySize: c.directorySize
        }))
      currentPage.value = 1 // 刷新后回到第一页
    }
  } catch (e) {
    ElMessage.error('获取在线学生失败')
  } finally {
    loading.value = false
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除 ${row.username} 的容器吗？`,
      '确认删除',
      { type: 'warning' }
    )

    const response = await containerApi.deleteContainer(row.username)
    if (response.code === 200) {
      ElMessage.success('容器已删除')
      refreshStudents()
    } else {
      ElMessage.error(response.msg || '删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const handleSelectionChange = (val) => {
  selectedStudents.value = val
}

const handleBatchDelete = async () => {
  if (selectedStudents.value.length === 0) {
    ElMessage.warning('请选择要删除的学生')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${selectedStudents.value.length} 个学生容器吗？`,
      '确认批量删除',
      { type: 'warning' }
    )

    const usernames = selectedStudents.value.map(s => s.username)
    const response = await containerApi.batchDeleteContainers(usernames)
    if (response.code === 200) {
      ElMessage.success(response.msg || '删除成功')
      refreshStudents()
      selectedStudents.value = []
    } else {
      ElMessage.error(response.msg || '批量删除失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量删除失败')
    }
  }
}

const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
}

const handleCurrentChange = (page) => {
  currentPage.value = page
}

onMounted(() => {
  refreshStudents()
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
  margin-top: 20px;
  display: flex;
  gap: 50px;
  justify-content: center;
}
</style>
