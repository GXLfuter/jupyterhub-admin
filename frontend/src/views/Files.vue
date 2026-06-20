<template>
  <div class="files-page">
    <h2>教师上传作业文件夹</h2>
    
    <!-- 操作栏：上传 + 搜索 + 排序 + 批量删除 -->
    <div class="toolbar">
      <el-upload
        :action="uploadUrl"
        :headers="headers"
        :on-success="onUploadSuccess"
        :on-error="onUploadError"
        :multiple="true"
        :show-file-list="false"
        :limit="100"
      >
        <el-button type="primary">上传文件</el-button>
      </el-upload>
      <el-input
        v-model="searchText"
        placeholder="搜索文件名..."
        clearable
        style="width: 250px; margin-left: 16px;"
        @input="onSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      
      <!-- 排序下拉框 -->
      <div class="sort-area">
        <el-select
          v-model="sortField"
          placeholder="排序字段"
          size="small"
          style="width: 120px; margin-left: 16px;"
        >
          <el-option label="修改时间" value="time" />
          <el-option label="文件大小" value="size" />
          <el-option label="文件类型" value="type" />
        </el-select>
        <el-select
          v-model="sortOrder"
          placeholder="排序方式"
          size="small"
          style="width: 100px; margin-left: 8px;"
        >
          <el-option label="升序" value="asc" />
          <el-option label="降序" value="desc" />
        </el-select>
      </div>
      
      <div style="margin-left: auto;">
        <el-button
          type="danger"
          size="small"
          :disabled="selectedFiles.length === 0"
          @click="handleBatchDelete"
        >
          批量删除 ({{ selectedFiles.length }})
        </el-button>
      </div>
    </div>

    <!-- 文件列表 -->
    <el-table
      :data="pagedFiles"
      v-loading="loading"
      border
      :row-key="(row) => row.path"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" />
      <el-table-column prop="name" label="文件名" />
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column prop="sizeFormatted" label="大小" width="100" />
      <el-table-column label="修改时间" width="180">
        <template #default="{ row }">
          {{ formatDate(row.modifyTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="scope">
          <el-button type="danger" size="small" @click="deleteFile(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-area">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="sortedFiles.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import axios from 'axios'

const fileList = ref([])
const loading = ref(false)
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const uploadUrl = '/api/files/upload'
const headers = { Authorization: 'Bearer ' + localStorage.getItem('token') }

// 选中的文件列表
const selectedFiles = ref([])

// 排序配置
const sortField = ref('time') // time, size, type
const sortOrder = ref('desc') // asc, desc

// 模糊搜索
const filteredFiles = computed(() => {
  if (!searchText.value) return fileList.value
  const keyword = searchText.value.toLowerCase()
  return fileList.value.filter(f => f.name.toLowerCase().includes(keyword))
})

// 排序后的文件列表
const sortedFiles = computed(() => {
  const files = [...filteredFiles.value]
  
  files.sort((a, b) => {
    let comparison = 0
    
    switch (sortField.value) {
      case 'time':
        // 解析时间进行比较
        const timeA = parseModifyTime(a.modifyTime)
        const timeB = parseModifyTime(b.modifyTime)
        comparison = timeA - timeB
        break
      case 'size':
        comparison = a.size - b.size
        break
      case 'type':
        comparison = a.type.localeCompare(b.type)
        break
      default:
        comparison = 0
    }
    
    return sortOrder.value === 'asc' ? comparison : -comparison
  })
  
  return files
})

// 解析修改时间为时间戳
const parseModifyTime = (timeStr) => {
  if (!timeStr) return 0
  
  // 尝试解析格式：May 29 10:15:46
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
  const parts = timeStr.split(/\s+/)
  
  if (parts.length >= 3) {
    const monthIdx = months.indexOf(parts[0])
    if (monthIdx !== -1) {
      const year = new Date().getFullYear()
      const month = monthIdx
      const day = parseInt(parts[1])
      const timeParts = parts[2].split(':')
      const hours = parseInt(timeParts[0]) || 0
      const minutes = parseInt(timeParts[1]) || 0
      const seconds = parseInt(timeParts[2]) || 0
      return new Date(year, month, day, hours, minutes, seconds).getTime()
    }
  }
  
  return new Date(timeStr).getTime() || 0
}

// 当前页数据
const pagedFiles = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return sortedFiles.value.slice(start, end)
})

const onSearch = () => {
  currentPage.value = 1
}

// 日期格式化：May 29 10:15:46 -> 2026-05-29 10:15:46
const formatDate = (dateStr) => {
  if (!dateStr) return ''
  
  // 如果是已经格式化好的日期，直接返回
  if (/^\d{4}-\d{2}-\d{2}/.test(dateStr)) return dateStr
  
  // 获取当前年份
  const currentYear = new Date().getFullYear()
  
  // 尝试解析 May 29 这种格式
  const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
  const parts = dateStr.split(/\s+/)
  
  if (parts.length >= 3) {
    // May 29 10:15:46 格式
    const monthIdx = months.indexOf(parts[0])
    if (monthIdx !== -1) {
      const month = String(monthIdx + 1).padStart(2, '0')
      const day = parts[1].padStart(2, '0')
      const time = parts[2]
      return `${currentYear}-${month}-${day} ${time}`
    }
  } else if (parts.length === 2) {
    // May 29 格式（只有月份和日期）
    const monthIdx = months.indexOf(parts[0])
    if (monthIdx !== -1) {
      const month = String(monthIdx + 1).padStart(2, '0')
      const day = parts[1].padStart(2, '0')
      return `${currentYear}-${month}-${day}`
    }
  }
  
  return dateStr
}

// 加载文件列表
const loadFiles = () => {
  loading.value = true
  axios.get('/api/files/list', { headers })
    .then(res => {
      if (res.data.code === 200) {
        fileList.value = res.data.data
      }
    })
    .catch(() => ElMessage.error('加载失败'))
    .finally(() => loading.value = false)
}

// 上传成功
const onUploadSuccess = (response, file, fileList) => {
  if (response.code === 200) {
    ElMessage.success(file.name + ' 上传成功')
    // 刷新列表
    loadFiles()
  } else {
    ElMessage.error(file.name + ' 上传失败: ' + response.msg)
  }
}

// 上传失败
const onUploadError = (error, file) => {
  ElMessage.error(file.name + ' 上传失败')
}

// 删除单个文件
const deleteFile = (row) => {
  ElMessageBox.confirm('确定删除 ' + row.name + '?', '提示', { type: 'warning' })
    .then(() => {
      axios.post('/api/files/delete', { path: row.path }, { headers })
        .then(res => {
          if (res.data.code === 200) {
            ElMessage.success('删除成功')
            loadFiles()
          }
        })
    })
    .catch(() => {})
}

// 选择文件变化
const handleSelectionChange = (val) => {
  selectedFiles.value = val
}

// 批量删除
const handleBatchDelete = () => {
  if (selectedFiles.value.length === 0) {
    ElMessage.warning('请选择要删除的文件')
    return
  }

  ElMessageBox.confirm(
    `确定要删除选中的 ${selectedFiles.value.length} 个文件吗？`,
    '确认批量删除',
    { type: 'warning' }
  )
    .then(() => {
      const paths = selectedFiles.value.map(f => f.path)
      axios.post('/api/files/delete/batch', { paths }, { headers })
        .then(res => {
          if (res.data.code === 200) {
            ElMessage.success(res.data.msg || '删除成功')
            loadFiles()
            selectedFiles.value = []
          }
        })
    })
    .catch(() => {})
}

onMounted(loadFiles)
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
.sort-area {
  display: flex;
  align-items: center;
}
.pagination-area {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
