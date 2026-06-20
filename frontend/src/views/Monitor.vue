<template>
  <div class="monitor-view">
    <!-- 顶部状态卡片 -->
    <el-row :gutter="20" class="top-cards">
      <el-col :span="6">
        <el-card class="stat-card cpu-card" shadow="hover">
          <div class="stat-header">
            <div class="stat-icon">
              <el-icon :size="32"><Cpu /></el-icon>
            </div>
            <div class="stat-title">CPU 使用率</div>
          </div>
          <div class="stat-value">
            <span v-if="stats.cpuUsage">{{ stats.cpuUsage }}%</span>
            <span v-else class="loading-text">--</span>
          </div>
          <div class="stat-sub">{{ stats.cpuCores || '1' }} 核心 · 负载: {{ stats.loadAverage || 'N/A' }}</div>
          <el-progress 
            :percentage="parseFloat(stats.cpuUsage) || 0" 
            :color="getProgressColor(parseFloat(stats.cpuUsage))"
            :stroke-width="10"
          />
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card memory-card" shadow="hover">
          <div class="stat-header">
            <div class="stat-icon">
              <el-icon :size="32"><Memo /></el-icon>
            </div>
            <div class="stat-title">内存使用</div>
          </div>
          <div class="stat-value">
            <span v-if="stats.memoryUsage">{{ stats.memoryUsage }}%</span>
            <span v-else class="loading-text">--</span>
          </div>
          <div class="stat-sub">{{ stats.memoryUsed || 'N/A' }} / {{ stats.memoryTotal || 'N/A' }}</div>
          <el-progress 
            :percentage="parseFloat(stats.memoryUsage) || 0" 
            :color="getProgressColor(parseFloat(stats.memoryUsage))"
            :stroke-width="10"
          />
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card disk-card" shadow="hover">
          <div class="stat-header">
            <div class="stat-icon">
              <el-icon :size="32"><Folder /></el-icon>
            </div>
            <div class="stat-title">磁盘使用</div>
          </div>
          <div class="stat-value">
            <span v-if="stats.diskUsage">{{ stats.diskUsage }}%</span>
            <span v-else class="loading-text">--</span>
          </div>
          <div class="stat-sub">{{ stats.diskUsed || 'N/A' }} / {{ stats.diskTotal || 'N/A' }}</div>
          <el-progress 
            :percentage="parseFloat(stats.diskUsage) || 0" 
            :color="getProgressColor(parseFloat(stats.diskUsage))"
            :stroke-width="10"
          />
        </el-card>
      </el-col>
      
      <el-col :span="6">
        <el-card class="stat-card net-card" shadow="hover">
          <div class="stat-header">
            <div class="stat-icon">
              <el-icon :size="32"><Connection /></el-icon>
            </div>
            <div class="stat-title">网络流量</div>
          </div>
          <div class="stat-value" style="font-size: 20px; margin-bottom: 8px;">
            <span class="net-in">↓ {{ stats.netIn || '0.0' }} KB/s</span>
          </div>
          <div class="stat-value" style="font-size: 20px;">
            <span class="net-out">↑ {{ stats.netOut || '0.0' }} KB/s</span>
          </div>
          <div class="stat-sub">接口: {{ stats.netInterface || 'eth0' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 中间图表区域 -->
    <el-row :gutter="20" class="charts-row">
      <el-col :span="16">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span>资源使用趋势</span>
              <el-radio-group v-model="chartTimeRange" size="small" @change="onTimeRangeChange">
                <el-radio-button label="10">10分钟</el-radio-button>
                <el-radio-button label="30">30分钟</el-radio-button>
                <el-radio-button label="60">1小时</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          <div ref="resourceChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      
      <el-col :span="8">
        <el-card class="info-card" shadow="hover">
          <template #header>
            <span>系统状态</span>
          </template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="操作系统">
              <el-tag type="info" size="small">{{ stats.osInfo || 'Linux' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="系统运行时间">
              <el-tag type="success" size="small">{{ stats.uptime || 'N/A' }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="进程总数">
              <el-tag type="warning" size="small">{{ stats.processCount || 0 }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="运行中进程">
              <el-tag type="danger" size="small">{{ stats.runningProcesses || 0 }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="在线容器">
              <el-tag type="success" size="small">{{ stats.onlineContainers || 0 }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="总容器">
              <el-tag type="info" size="small">{{ stats.totalContainers || 0 }}</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <!-- 网络和进程区域 -->
    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <span>网络流量趋势</span>
          </template>
          <div ref="networkChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
      
      <el-col :span="12">
        <el-card class="chart-card" shadow="hover">
          <template #header>
            <span>系统资源分布</span>
          </template>
          <div ref="pieChartRef" class="chart-container"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 底部控制区 -->
    <div class="control-bar">
      <div class="refresh-controls">
        <el-switch
          v-model="autoRefresh"
          active-text="自动刷新"
          inactive-text="停止刷新"
        />
        <el-select v-model="refreshInterval" style="width: 120px; margin-left: 12px;" size="small">
          <el-option label="1秒" :value="1000" />
          <el-option label="2秒" :value="2000" />
          <el-option label="5秒" :value="5000" />
        </el-select>
      </div>
      <el-button type="primary" :icon="Refresh" @click="refreshStats">手动刷新</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick, shallowRef } from 'vue'
import { Cpu, Memo, Folder, Refresh, Connection } from '@element-plus/icons-vue'
import { monitorApi } from '../api/monitor'
import * as echarts from 'echarts'

const stats = shallowRef({})
const autoRefresh = ref(true)
const refreshInterval = ref(2000)
const chartTimeRange = ref('10')
const refreshTimer = ref(null)

const cpuHistory = ref([])
const memoryHistory = ref([])
const netInHistory = ref([])
const netOutHistory = ref([])
const timeLabels = ref([])

const resourceChartRef = ref(null)
const networkChartRef = ref(null)
const pieChartRef = ref(null)
let resourceChart = null
let networkChart = null
let pieChart = null

const getProgressColor = (value) => {
  if (value >= 90) return '#F56C6C'
  if (value >= 70) return '#E6A23C'
  return '#67C23A'
}

const loadStats = async () => {
  try {
    const response = await monitorApi.getStats()
    if (response.code === 200) {
      stats.value = response.data
      updateChartData(response.data)
    }
  } catch (e) {
    console.error('加载统计失败', e)
  }
}

const updateChartData = (data) => {
  const now = new Date()
  const timeStr = now.getHours().toString().padStart(2, '0') + ':' + now.getMinutes().toString().padStart(2, '0') + ':' + now.getSeconds().toString().padStart(2, '0')
  
  const maxPoints = parseInt(chartTimeRange.value) * 30 / 10
  
  timeLabels.value.push(timeStr)
  cpuHistory.value.push(parseFloat(data.cpuUsage) || 0)
  memoryHistory.value.push(parseFloat(data.memoryUsage) || 0)
  netInHistory.value.push(parseFloat(data.netIn) || 0)
  netOutHistory.value.push(parseFloat(data.netOut) || 0)
  
  if (timeLabels.value.length > maxPoints) {
    timeLabels.value.shift()
    cpuHistory.value.shift()
    memoryHistory.value.shift()
    netInHistory.value.shift()
    netOutHistory.value.shift()
  }
  
  updateCharts()
}

const initCharts = () => {
  try {
    if (resourceChartRef.value) {
      resourceChart = echarts.init(resourceChartRef.value)
      resourceChart.setOption(getResourceChartOption())
    }
    if (networkChartRef.value) {
      networkChart = echarts.init(networkChartRef.value)
      networkChart.setOption(getNetworkChartOption())
    }
    if (pieChartRef.value) {
      pieChart = echarts.init(pieChartRef.value)
      pieChart.setOption(getPieChartOption())
    }
  } catch (e) {
    console.error('初始化图表失败', e)
  }
}

const getResourceChartOption = () => {
  const data1 = cpuHistory.value.length > 0 ? cpuHistory.value : [0]
  const data2 = memoryHistory.value.length > 0 ? memoryHistory.value : [0]
  const tlabels = timeLabels.value.length > 0 ? timeLabels.value : ['--']
  
  const gradient1 = new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: 'rgba(102,126,234,0.3)' },
    { offset: 1, color: 'rgba(102,126,234,0.05)' }
  ])
  
  const gradient2 = new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: 'rgba(240,147,251,0.3)' },
    { offset: 1, color: 'rgba(240,147,251,0.05)' }
  ])
  
  return {
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#ddd', borderWidth: 1, textStyle: { color: '#333' } },
    legend: { data: ['CPU', '内存'], top: 0 },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: tlabels },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [
      { name: 'CPU', type: 'line', smooth: true, data: data1, itemStyle: { color: '#667eea' }, areaStyle: { color: gradient1 } },
      { name: '内存', type: 'line', smooth: true, data: data2, itemStyle: { color: '#f093fb' }, areaStyle: { color: gradient2 } }
    ]
  }
}

const getNetworkChartOption = () => {
  const data1 = netInHistory.value.length > 0 ? netInHistory.value : [0]
  const data2 = netOutHistory.value.length > 0 ? netOutHistory.value : [0]
  const tlabels = timeLabels.value.length > 0 ? timeLabels.value : ['--']
  
  const gradient1 = new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: 'rgba(103,194,58,0.3)' },
    { offset: 1, color: 'rgba(103,194,58,0.05)' }
  ])
  
  const gradient2 = new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: 'rgba(64,158,255,0.3)' },
    { offset: 1, color: 'rgba(64,158,255,0.05)' }
  ])
  
  return {
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#ddd', borderWidth: 1, textStyle: { color: '#333' } },
    legend: { data: ['下载', '上传'], top: 0 },
    grid: { left: '3%', right: '4%', bottom: '3%', top: '15%', containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: tlabels },
    yAxis: { type: 'value', axisLabel: { formatter: '{value} KB/s' } },
    series: [
      { name: '下载', type: 'line', smooth: true, data: data1, itemStyle: { color: '#67C23A' }, areaStyle: { color: gradient1 } },
      { name: '上传', type: 'line', smooth: true, data: data2, itemStyle: { color: '#409EFF' }, areaStyle: { color: gradient2 } }
    ]
  }
}

const getPieChartOption = () => {
  const cpuVal = parseFloat(stats.value.cpuUsage) || 0
  const memVal = parseFloat(stats.value.memoryUsage) || 0
  const diskVal = parseFloat(stats.value.diskUsage) || 0
  const freeVal = Math.max(0, 100 - cpuVal - memVal)
  
  return {
    tooltip: { trigger: 'item', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#ddd', borderWidth: 1, textStyle: { color: '#333' } },
    legend: { orient: 'vertical', right: 10, top: 'center' },
    series: [{
      name: '资源分布', type: 'pie', radius: ['40%', '70%'],
      itemStyle: { borderRadius: 10, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}: {c}%' },
      emphasis: { label: { show: true, fontSize: 16, fontWeight: 'bold' } },
      data: [
        { value: cpuVal, name: 'CPU', itemStyle: { color: '#667eea' } },
        { value: memVal, name: '内存', itemStyle: { color: '#f093fb' } },
        { value: diskVal, name: '磁盘', itemStyle: { color: '#4facfe' } },
        { value: freeVal, name: '空闲', itemStyle: { color: '#E0E3E9' } }
      ]
    }]
  }
}

const updateCharts = () => {
  if (resourceChart) {
    resourceChart.setOption({ xAxis: { data: timeLabels.value }, series: [{ data: cpuHistory.value }, { data: memoryHistory.value }] })
  }
  if (networkChart) {
    networkChart.setOption({ xAxis: { data: timeLabels.value }, series: [{ data: netInHistory.value }, { data: netOutHistory.value }] })
  }
  if (pieChart) {
    const cpuVal = parseFloat(stats.value.cpuUsage) || 0
    const memVal = parseFloat(stats.value.memoryUsage) || 0
    const diskVal = parseFloat(stats.value.diskUsage) || 0
    const freeVal = Math.max(0, 100 - cpuVal - memVal)
    pieChart.setOption({ series: [{ data: [
      { value: cpuVal, name: 'CPU', itemStyle: { color: '#667eea' } },
      { value: memVal, name: '内存', itemStyle: { color: '#f093fb' } },
      { value: diskVal, name: '磁盘', itemStyle: { color: '#4facfe' } },
      { value: freeVal, name: '空闲', itemStyle: { color: '#E0E3E9' } }
    ] }] })
  }
}

const refreshStats = () => {
  loadStats()
}

const onTimeRangeChange = () => {
  cpuHistory.value = []
  memoryHistory.value = []
  netInHistory.value = []
  netOutHistory.value = []
  timeLabels.value = []
}

const startAutoRefresh = () => {
  stopAutoRefresh()
  if (autoRefresh.value) {
    refreshTimer.value = setInterval(loadStats, refreshInterval.value)
  }
}

const stopAutoRefresh = () => {
  if (refreshTimer.value) {
    clearInterval(refreshTimer.value)
    refreshTimer.value = null
  }
}

watch(autoRefresh, startAutoRefresh)
watch(refreshInterval, startAutoRefresh)

const handleResize = () => {
  resourceChart && resourceChart.resize()
  networkChart && networkChart.resize()
  pieChart && pieChart.resize()
}

onMounted(() => {
  loadStats()
  nextTick(() => {
    initCharts()
  })
  setTimeout(startAutoRefresh, 1000)
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  stopAutoRefresh()
  window.removeEventListener('resize', handleResize)
  resourceChart && resourceChart.dispose()
  networkChart && networkChart.dispose()
  pieChart && pieChart.dispose()
})
</script>

<style scoped>
.monitor-view {
  padding: 20px;
  height: 100%;
  overflow-y: auto;
}

.top-cards {
  margin-bottom: 20px;
}

.stat-card {
  border: none;
  transition: transform 0.3s;
}

.stat-card:hover {
  transform: translateY(-4px);
}

.stat-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
}

.cpu-card .stat-icon {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.memory-card .stat-icon {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
  color: white;
}

.disk-card .stat-icon {
  background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%);
  color: white;
}

.net-card .stat-icon {
  background: linear-gradient(135deg, #67C23A 0%, #85ce61 100%);
  color: white;
}

.stat-title {
  font-size: 14px;
  color: #666;
  font-weight: 500;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  margin: 8px 0;
}

.loading-text {
  color: #999;
  animation: blink 1s infinite;
}

@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0.4; }
}

.net-in { color: #67C23A; }
.net-out { color: #409EFF; }

.stat-sub {
  color: #999;
  font-size: 12px;
  margin-bottom: 12px;
}

.charts-row { margin-top: 20px; }

.chart-card { height: 380px; }
.info-card { height: 380px; }

.chart-container {
  width: 100%;
  height: 300px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.control-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 24px;
  padding: 16px 20px;
  background: #f5f7fa;
  border-radius: 8px;
}

.refresh-controls {
  display: flex;
  align-items: center;
}
</style>
