# 显卡分配功能实现计划

## 需求分析
在前端文件管理栏下方添加"显卡分配"功能面板，实现：
1. **GPU配置控制**：通过按钮控制后端配置文件 `/opt/jupyterhub_prod/config/jupyterhub_config.py` 中的 `student1234_use_gpu_settings` 变量值（0/1/2/3）
2. **GPU状态监控**：显示服务器GPU显卡占用和信息状态面板

## 实现方案

### 后端修改
1. **创建新控制器**：`GpuController.java` - 处理GPU配置和状态查询的API
2. **创建新服务**：`GpuService.java` - 执行SSH命令操作配置文件和获取GPU状态

### 前端修改
1. **创建新页面**：`Gpu.vue` - 显卡分配面板UI
2. **创建新API**：`gpu.js` - 调用后端API
3. **更新路由**：`router/index.js` - 添加新路由
4. **更新侧边栏**：`Layout.vue` - 添加菜单项和页面标题

## 修改文件清单

### 后端文件（新建）
- `backend/src/main/java/com/jupyterhub/controller/GpuController.java`
- `backend/src/main/java/com/jupyterhub/service/GpuService.java`

### 前端文件（新建）
- `frontend/src/api/gpu.js`
- `frontend/src/views/Gpu.vue`

### 前端文件（修改）
- `frontend/src/router/index.js`
- `frontend/src/components/Layout.vue`

## API设计

| API | 方法 | 功能 |
|-----|------|------|
| `/gpu/status` | GET | 获取GPU状态信息（nvidia-smi输出解析） |
| `/gpu/config` | GET | 获取当前GPU配置值 |
| `/gpu/config` | POST | 设置GPU配置值（0/1/2/3）并重启服务 |

## 配置操作逻辑
1. 使用 `sed` 命令替换配置文件中的变量值：
   ```bash
   sed -i "s/student1234_use_gpu_settings = [0-9]/student1234_use_gpu_settings = ${value}/" /opt/jupyterhub_prod/config/jupyterhub_config.py
   ```
2. 重启服务：
   ```bash
   systemctl restart jupyterhub.service
   ```

## 风险评估
- **低风险**：仅添加新功能模块，不修改现有代码逻辑
- **配置文件操作**：使用 sed 命令进行精确替换，不会影响其他配置
- **服务重启**：重启 jupyterhub 服务会短暂中断学生使用，需在前端提示用户

## 实施步骤
1. 创建后端 GpuService 和 GpuController
2. 创建前端 gpu.js API 文件
3. 创建前端 Gpu.vue 页面
4. 更新路由配置
5. 更新侧边栏菜单和标题
6. 编译后端项目
7. 验证功能

## 界面设计参考
参考 Monitor.vue 的卡片式布局风格，保持与其他页面一致的设计
