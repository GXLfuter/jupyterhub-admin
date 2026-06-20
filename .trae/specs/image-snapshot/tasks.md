# JupyterHub 管理平台 - 镜像快照功能实现计划

## [ ] Task 1: 后端 ImageSnapshotService 服务实现
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 创建 ImageSnapshotService.java，提供镜像扫描、恢复、创建快照功能
  - 使用 SSH 命令执行 docker images、docker load、docker save 命令
  - 定义三个关键镜像常量及其备份文件映射
  - 提供日志记录功能
- **Acceptance Criteria Addressed**: [AC-1, AC-2, AC-3, AC-4]
- **Test Requirements**:
  - `programmatic` TR-1.1: 后端编译通过
  - `human-judgement` TR-1.2: 服务类结构清晰，方法命名规范
- **Notes**: 参考 PortForwardService.java 的实现模式

## [ ] Task 2: 后端 ImageSnapshotController 控制器实现
- **Priority**: P0
- **Depends On**: Task 1
- **Description**: 
  - 创建 ImageSnapshotController.java，提供 REST API 接口
  - 接口列表：
    - GET /image-snapshot/status - 获取所有镜像状态
    - POST /image-snapshot/restore - 恢复关键镜像
    - POST /image-snapshot/create - 创建镜像快照
    - GET /image-snapshot/logs - 获取操作日志
- **Acceptance Criteria Addressed**: [AC-1, AC-2, AC-3, AC-4]
- **Test Requirements**:
  - `programmatic` TR-2.1: 后端编译通过
  - `human-judgement` TR-2.2: 接口设计符合 REST 规范
- **Notes**: 参考 PortForwardController.java 的实现模式

## [ ] Task 3: 前端 API 封装
- **Priority**: P0
- **Depends On**: Task 2
- **Description**: 
  - 创建 frontend/src/api/imageSnapshot.js
  - 封装后端 API 调用方法
- **Acceptance Criteria Addressed**: [AC-1, AC-2, AC-3, AC-4]
- **Test Requirements**:
  - `human-judgement` TR-3.1: API 方法与后端接口对应
- **Notes**: 参考 frontend/src/api/portForward.js

## [ ] Task 4: 前端路由配置
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 在 router/index.js 中添加 /image-snapshot 路由
  - 配置路由守卫，学生角色不可访问
- **Acceptance Criteria Addressed**: [AC-5]
- **Test Requirements**:
  - `human-judgement` TR-4.1: 路由配置正确，学生访问被拦截
- **Notes**: 参考现有路由配置

## [ ] Task 5: 前端侧边栏菜单配置
- **Priority**: P0
- **Depends On**: Task 4
- **Description**: 
  - 在 Layout.vue 中添加"镜像快照"菜单项
  - 配置角色权限，仅管理员可见
- **Acceptance Criteria Addressed**: [AC-1, AC-5]
- **Test Requirements**:
  - `human-judgement` TR-5.1: 管理员可见菜单，学生不可见
- **Notes**: 参考现有菜单配置

## [ ] Task 6: 前端 ImageSnapshot.vue 页面实现
- **Priority**: P0
- **Depends On**: Task 3, Task 4, Task 5
- **Description**: 
  - 创建 frontend/src/views/ImageSnapshot.vue
  - 实现镜像状态展示表格（关键镜像与普通镜像分开显示）
  - 实现一键恢复功能（仅关键镜像）
  - 实现创建快照功能（所有镜像）
  - 实现操作日志面板
  - 实现15秒自动刷新
- **Acceptance Criteria Addressed**: [AC-1, AC-2, AC-3, AC-4]
- **Test Requirements**:
  - `human-judgement` TR-6.1: UI 风格与其他栏一致
  - `human-judgement` TR-6.2: 自动刷新正常工作（15秒）
  - `human-judgement` TR-6.3: 恢复和创建快照功能正常
- **Notes**: 参考 PortForward.vue 的实现模式，保持 UI 风格一致

## [ ] Task 7: 后端编译与前端构建验证
- **Priority**: P1
- **Depends On**: Task 1, Task 2, Task 3, Task 4, Task 5, Task 6
- **Description**: 
  - 执行 mvn compile 验证后端编译
  - 执行 npm run build 验证前端构建
- **Acceptance Criteria Addressed**: [全部]
- **Test Requirements**:
  - `programmatic` TR-7.1: 后端 mvn compile 通过
  - `programmatic` TR-7.2: 前端 npm run build 通过
- **Notes**: 确保无编译错误