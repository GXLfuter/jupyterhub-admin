# JupyterHub 管理平台 - 镜像快照功能验证清单

## 功能验证

- [x] Checkpoint 1: 左侧菜单栏"端口转发"下方显示"镜像快照"菜单项（仅管理员可见）
- [x] Checkpoint 2: 进入页面后显示"镜像备份恢复管理"标题
- [x] Checkpoint 3: 页面自动扫描镜像列表（15秒刷新）
- [x] Checkpoint 4: 三个关键镜像（jupyter/base-notebook:latest、jupyter-cpu-zh:v18、jupyter-gpu-zh:v18）显示状态正常/缺失/恢复中
- [x] Checkpoint 5: 关键镜像缺失时显示红色警告状态
- [x] Checkpoint 6: 点击恢复按钮后状态变为"恢复中"
- [x] Checkpoint 7: 恢复完成后状态变为"正常"
- [x] Checkpoint 8: 其他镜像显示是否已备份状态
- [x] Checkpoint 9: 未备份镜像可点击"创建备份"按钮
- [x] Checkpoint 10: 创建备份时显示"处理中"状态
- [x] Checkpoint 11: 创建备份完成后显示"已创建快照"、快照时间、备份路径
- [x] Checkpoint 12: 所有镜像显示大小信息
- [x] Checkpoint 13: 操作日志面板显示恢复和创建快照的日志
- [x] Checkpoint 14: 学生账号登录后侧边栏不显示"镜像快照"菜单
- [x] Checkpoint 15: 学生账号直接访问 /image-snapshot 页面被重定向到 /port-forward

## 代码验证

- [x] Checkpoint 16: 后端 ImageSnapshotService.java 创建并编译通过
- [x] Checkpoint 17: 后端 ImageSnapshotController.java 创建并编译通过
- [x] Checkpoint 18: 前端 api/imageSnapshot.js 创建
- [x] Checkpoint 19: 前端路由 /image-snapshot 配置正确
- [x] Checkpoint 20: 前端 Layout.vue 菜单配置正确
- [x] Checkpoint 21: 前端 ImageSnapshot.vue 创建且构建通过
- [x] Checkpoint 22: 未修改其他栏目的代码

## 界面验证

- [x] Checkpoint 23: UI 风格与其他栏保持一致，不违和
- [x] Checkpoint 24: 表格布局清晰，操作按钮醒目