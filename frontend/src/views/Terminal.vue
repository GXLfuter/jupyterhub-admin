<template>
  <div class="terminal-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>SSH终端 - root@{{ serverHost || '加载中...' }}</span>
          <el-button
            :type="connected ? 'danger' : 'success'"
            size="small"
            @click="toggleConnection"
          >
            {{ connected ? '断开连接' : '连接' }}
          </el-button>
        </div>
      </template>

      <div ref="terminalContainer" class="terminal-container"></div>

      <div class="terminal-status">
        <el-tag :type="connected ? 'success' : 'info'">
          {{ connected ? '已连接' : '未连接' }}
        </el-tag>
        <span class="tips">
          提示：已自动切换到root用户，可直接执行命令
        </span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Terminal } from 'xterm'
import { FitAddon } from 'xterm-addon-fit'
import 'xterm/css/xterm.css'
import api from '../api/axios'

const terminalContainer = ref(null)
const connected = ref(false)
const serverHost = ref('')

let terminal = null
let fitAddon = null
let ws = null

const loadServerConfig = async () => {
  try {
    const response = await api.get('/config')
    if (response.code === 200) {
      serverHost.value = response.data.host
    }
  } catch (e) {
    console.error('获取配置失败', e)
  }
}

const initTerminal = () => {
  terminal = new Terminal({
    cursorBlink: true,
    fontSize: 14,
    fontFamily: 'Consolas, Monaco, monospace',
    theme: {
      background: '#1e1e1e',
      foreground: '#d4d4d4',
      cursor: '#ffffff'
    },
    rows: 24,
    cols: 100
  })

  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)

  nextTick(() => {
    terminal.open(terminalContainer.value)
    fitAddon.fit()
    terminal.focus()
  })
}

const connect = () => {
  if (ws) {
    ws.close()
  }

  const wsUrl = `ws://localhost:9090/ws/terminal`
  ws = new WebSocket(wsUrl)

  ws.onopen = () => {
    connected.value = true
    terminal.writeln('\x1b[36m[系统]\x1b[0m WebSocket连接已建立')
  }

  ws.onmessage = (event) => {
    terminal.write(event.data)
  }

  ws.onclose = () => {
    connected.value = false
    terminal.writeln('\r\n\x1b[33m[系统]\x1b[0m 连接已断开')
  }

  ws.onerror = () => {
    ElMessage.error('WebSocket连接失败，请检查后端服务是否启动')
    connected.value = false
  }

  // 终端输入监听
  terminal.onData((data) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(data)
    }
  })
}

const disconnect = () => {
  if (ws) {
    ws.close()
    ws = null
  }
  connected.value = false
}

const toggleConnection = () => {
  if (connected.value) {
    disconnect()
  } else {
    connect()
  }
}

// 窗口大小变化时调整终端
const handleResize = () => {
  if (fitAddon) {
    fitAddon.fit()
  }
}

onMounted(() => {
  loadServerConfig()
  initTerminal()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  disconnect()
  window.removeEventListener('resize', handleResize)
  if (terminal) {
    terminal.dispose()
  }
})
</script>

<style scoped>
.terminal-view {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.terminal-container {
  width: 100%;
  height: 500px;
  background: #1e1e1e;
  border-radius: 4px;
  padding: 10px;
}

.terminal-status {
  margin-top: 15px;
  display: flex;
  align-items: center;
  gap: 15px;
}

.tips {
  color: #999;
  font-size: 12px;
}
</style>
