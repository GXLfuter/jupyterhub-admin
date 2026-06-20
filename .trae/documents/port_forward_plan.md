# 端口转发功能实现计划

## 需求分析
在前端显卡分配栏下方添加"端口转发"功能，通过调用后端 `/opt/jupyterhub_prod/ip_res.sh` 脚本实现：
1. **添加转发**：选择学生、容器端口、宿主机端口，调用 `ip_res.sh <student_name> <container_port> <host_port>`
2. **停止转发**：停止指定转发规则，调用 `ip_res.sh stop <student_name> <host_port>`
3. **查看状态**：显示当前活跃的转发列表，调用 `ip_res.sh status`
4. **监控管理**：启动/停止后台监控，调用 `ip_res.sh start-monitor` / `stop-monitor`
5. **日志展示**：读取监控日志文件 `/tmp/ip_res_monitor.log`

## 实现方案

### 后端修改
1. **创建新控制器**：`PortForwardController.java` - 处理端口转发相关 API
2. **创建新服务**：`PortForwardService.java` - 执行 SSH 命令调用脚本

### 前端修改
1. **创建新页面**：`PortForward.vue` - 端口转发面板 UI
2. **创建新 API**：`portForward.js` - 调用后端 API
3. **更新路由**：`router/index.js` - 添加新路由
4. **更新侧边栏**：`Layout.vue` - 添加菜单项和页面标题

## API 设计

| API | 方法 | 功能 |
|-----|------|------|
| `/port-forward/status` | GET | 获取当前活跃转发列表 |
| `/port-forward/add` | POST | 添加端口转发 |
| `/port-forward/stop` | POST | 停止指定转发 |
| `/port-forward/logs` | GET | 获取监控日志 |
| `/port-forward/start-monitor` | POST | 启动监控 |
| `/port-forward/stop-monitor` | POST | 停止监控 |
| `/port-forward/monitor-status` | GET | 获取监控运行状态 |

## 修改文件清单

### 后端文件（新建）
- `backend/src/main/java/com/jupyterhub/controller/PortForwardController.java`
- `backend/src/main/java/com/jupyterhub/service/PortForwardService.java`

### 前端文件（新建）
- `frontend/src/api/portForward.js`
- `frontend/src/views/PortForward.vue`

### 前端文件（修改）
- `frontend/src/router/index.js`
- `frontend/src/components/Layout.vue`

## 风险评估
- **低风险**：仅添加新功能模块，不修改现有代码逻辑
- **端口冲突**：脚本已有端口占用检查，前端也会显示提示
- **容器不存在**：脚本会检查容器状态，前端会给出错误提示

## 实施步骤
1. 创建后端 PortForwardService 和 PortForwardController
2. 创建前端 portForward.js API 文件
3. 创建前端 PortForward.vue 页面
4. 更新路由配置
5. 更新侧边栏菜单和标题
6. 编译后端项目
7. 验证功能
