# JupyterHub 管理平台 - 镜像快照功能 PRD

## Overview
- **Summary**: 在左侧菜单栏添加"镜像快照"功能栏，实现 Docker 镜像的备份与恢复管理。核心功能包括：自动扫描并恢复三个关键镜像（jupyter/base-notebook:latest、jupyter-cpu-zh:v18、jupyter-gpu-zh:v18），以及为其他镜像创建快照备份。
- **Purpose**: 防止学生误删关键镜像导致系统不可用，同时提供通用的镜像备份能力。
- **Target Users**: 管理员（ADMIN角色）

## Goals
- 在左侧菜单栏"端口转发"下方添加"镜像快照"菜单项
- 实现关键镜像自动扫描（15秒刷新）和一键恢复功能
- 实现任意镜像的快照创建功能
- 展示镜像状态、大小、备份路径、快照时间和操作日志

## Non-Goals (Out of Scope)
- 学生角色不可访问此功能
- 不修改其他现有栏目的代码
- 不实现镜像删除功能（仅备份和恢复）

## Background & Context
- 服务器备份目录：/opt/jupyterhub_prod/backups/
- 三个关键镜像及其备份文件：
  - jupyter/base-notebook:latest → jupyter-base-notebook.tar
  - jupyter-cpu-zh:v18 → jupyter-cpu-zh-v18.tar
  - jupyter-gpu-zh:v18 → jupyter-gpu-zh-v18.tar
- 使用 ssh 命令执行 docker 操作
- 参考现有端口转发功能的前后端实现模式

## Functional Requirements
- **FR-1**: 左侧菜单栏添加"镜像快照"菜单项，仅管理员可见
- **FR-2**: 页面标题显示"镜像备份恢复管理"
- **FR-3**: 自动扫描当前 Docker 镜像列表（15秒刷新）
- **FR-4**: 识别三个关键镜像是否缺失，显示状态（正常/缺失/恢复中）
- **FR-5**: 对缺失的关键镜像执行一键恢复（docker load -i）
- **FR-6**: 扫描所有镜像，显示是否已备份
- **FR-7**: 对未备份的镜像创建快照（docker save -o）
- **FR-8**: 显示镜像大小、备份路径、快照时间
- **FR-9**: 显示操作日志（恢复日志、快照日志）

## Non-Functional Requirements
- **NFR-1**: 自动刷新间隔为15秒
- **NFR-2**: UI风格与其他栏保持一致，不违和
- **NFR-3**: 恢复和创建快照操作需要显示进度状态

## Constraints
- **Technical**: Java Spring Boot + Vue3 + Element Plus
- **Business**: 仅管理员可访问
- **Dependencies**: SSH连接服务器执行docker命令

## Assumptions
- 服务器已正确配置SSH连接
- 备份目录 /opt/jupyterhub_prod/backups/ 存在且有读写权限
- 三个关键镜像的tar备份文件已存在于备份目录

## Acceptance Criteria

### AC-1: 关键镜像状态显示
- **Given**: 管理员登录系统，进入镜像快照页面
- **When**: 页面加载后自动扫描镜像
- **Then**: 三个关键镜像显示状态（正常/缺失），列表包含镜像名称、标签、大小、状态、备份路径
- **Verification**: `human-judgment`

### AC-2: 关键镜像恢复功能
- **Given**: 某个关键镜像被删除（如jupyter-cpu-zh:v18）
- **When**: 页面刷新后检测到镜像缺失，管理员点击恢复按钮
- **Then**: 状态变为"恢复中"，执行 docker load 命令，完成后状态变为"正常"
- **Verification**: `human-judgment`

### AC-3: 通用镜像快照功能
- **Given**: 系统中有未备份的镜像（如vllm/vllm-openai:0.21.0）
- **When**: 管理员点击"创建备份"按钮
- **Then**: 状态变为"处理中"，执行 docker save 命令，完成后显示"已创建快照"、快照时间、备份路径
- **Verification**: `human-judgment`

### AC-4: 操作日志显示
- **Given**: 执行恢复或创建快照操作
- **When**: 操作完成后
- **Then**: 日志面板显示操作时间、操作类型、操作结果
- **Verification**: `human-judgment`

### AC-5: 学生权限控制
- **Given**: 学生账号（student1）登录系统
- **When**: 访问镜像快照页面或查看侧边栏
- **Then**: 侧边栏不显示"镜像快照"菜单，访问页面被重定向到端口转发页
- **Verification**: `human-judgment`

## Open Questions
- [ ] 无