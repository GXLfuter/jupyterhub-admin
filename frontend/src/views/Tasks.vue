<template>
  <div class="tasks-view">
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>清理任务</span>
          </template>

          <el-form :model="cleanupForm" label-width="100px">
            <el-form-item label="选择学生">
              <el-checkbox
                v-model="selectAll"
                @change="handleSelectAll"
              >
                全选
              </el-checkbox>
              <el-checkbox
                v-model="cleanupForm.all"
                @change="handleAllChange"
              >
                全部学生
              </el-checkbox>
            </el-form-item>

            <el-form-item label="指定学生" v-if="!cleanupForm.all">
              <el-select
                v-model="cleanupForm.usernames"
                multiple
                placeholder="请选择学生"
                style="width: 100%"
              >
                <el-option
                  v-for="student in studentList"
                  :key="student"
                  :label="student"
                  :value="student"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="清理时间">
              <el-time-select
                v-model="cleanupForm.cron"
                placeholder="选择时间"
                start="00:00"
                end="23:59"
                interval="30"
                style="width: 100%"
              />
            </el-form-item>

            <el-form-item>
              <el-button type="primary" @click="handleCleanupNow" :loading="cleaning">
                立即清理
              </el-button>
              <el-button type="success" @click="handleScheduleTask">
                创建定时任务
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card>
          <template #header>
            <span>定时任务列表</span>
          </template>

          <el-table :data="scheduledTasks" stripe>
            <el-table-column prop="name" label="任务名称" />
            <el-table-column label="执行时间" width="100">
              <template #default="{ row }">
                {{ row.hour }}:{{ String(row.minute).padStart(2, '0') }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button
                  type="text"
                  size="small"
                  @click="toggleTask(row)"
                >
                  {{ row.enabled ? '禁用' : '启用' }}
                </el-button>
                <el-button
                  type="text"
                  size="small"
                  @click="deleteTask(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 20px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>执行历史</span>
          <el-button type="danger" size="small" @click="handleClearHistory">清除历史</el-button>
        </div>
      </template>

      <el-table :data="taskHistory" stripe>
        <el-table-column prop="username" label="学生" width="150" />
        <el-table-column prop="result" label="结果" />
        <el-table-column prop="trigger" label="触发方式" width="150" />
        <el-table-column prop="executeTime" label="执行时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '../api/task'
import { containerApi } from '../api/container'

const studentList = ref([])
const scheduledTasks = ref([])
const taskHistory = ref([])
const cleaning = ref(false)

const cleanupForm = reactive({
  all: false,
  usernames: [],
  cron: ''
})

const selectAll = ref(false)

const loadStudentList = async () => {
  try {
    const response = await containerApi.getStudentList()
    if (response.code === 200) {
      studentList.value = response.data
    }
  } catch (e) {
    console.error('获取学生列表失败', e)
  }
}

const loadScheduledTasks = async () => {
  try {
    const response = await taskApi.getScheduledTasks()
    if (response.code === 200) {
      scheduledTasks.value = response.data
    }
  } catch (e) {
    console.error('获取定时任务失败', e)
  }
}

const loadTaskHistory = async () => {
  try {
    const response = await taskApi.getTaskHistory()
    if (response.code === 200) {
      taskHistory.value = response.data
    }
  } catch (e) {
    console.error('获取历史记录失败', e)
  }
}

const handleSelectAll = (checked) => {
  if (checked) {
    cleanupForm.usernames = [...studentList.value]
  } else {
    cleanupForm.usernames = []
  }
}

const handleAllChange = (checked) => {
  if (checked) {
    selectAll.value = true
    cleanupForm.usernames = [...studentList.value]
  } else {
    selectAll.value = false
  }
}

const handleCleanupNow = async () => {
  if (!cleanupForm.all && cleanupForm.usernames.length === 0) {
    ElMessage.warning('请选择要清理的学生')
    return
  }

  try {
    await ElMessageBox.confirm(
      '确定要清理选中的学生容器和数据吗？',
      '确认清理',
      { type: 'warning' }
    )

    cleaning.value = true
    const response = await taskApi.executeCleanup({
      all: cleanupForm.all,
      usernames: cleanupForm.usernames,
      deleteDataDir: true
    })

    if (response.code === 200) {
      ElMessage.success('清理完成')
      loadTaskHistory()
    } else {
      ElMessage.error(response.msg || '清理失败')
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清理失败')
    }
  } finally {
    cleaning.value = false
  }
}

const handleScheduleTask = async () => {
  if (!cleanupForm.cron) {
    ElMessage.warning('请选择执行时间')
    return
  }

  try {
    const response = await taskApi.createScheduledTask(
      '定时清理任务',
      cleanupForm.all ? [] : cleanupForm.usernames,
      cleanupForm.cron
    )

    if (response.code === 200) {
      ElMessage.success('定时任务已创建')
      loadScheduledTasks()
    } else {
      ElMessage.error(response.msg || '创建失败')
    }
  } catch (e) {
    ElMessage.error('创建定时任务失败')
  }
}

const toggleTask = async (task) => {
  try {
    const response = await taskApi.toggleScheduledTask(task.id, !task.enabled)
    if (response.code === 200) {
      ElMessage.success('状态已更新')
      loadScheduledTasks()
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const deleteTask = async (task) => {
  try {
    await ElMessageBox.confirm('确定要删除该定时任务吗？', '确认删除', { type: 'warning' })

    const response = await taskApi.deleteScheduledTask(task.id)
    if (response.code === 200) {
      ElMessage.success('已删除')
      loadScheduledTasks()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const handleClearHistory = async () => {
  try {
    await ElMessageBox.confirm('确定要清除所有执行历史吗？', '确认清除', { type: 'warning' })

    const response = await taskApi.clearTaskHistory()
    if (response.code === 200) {
      ElMessage.success('已清除')
      loadTaskHistory()
    }
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('清除失败')
    }
  }
}

onMounted(() => {
  loadStudentList()
  loadScheduledTasks()
  loadTaskHistory()
})
</script>

<style scoped>
.tasks-view {
  height: 100%;
}
</style>
