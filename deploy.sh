#!/bin/bash
# =============================================
# JupyterHub 管理平台 - 一键部署脚本（服务器端打包）
# 适用系统: Ubuntu 22.04 x86_64
# 使用方式: sudo bash deploy.sh [--install]
#           --install: 首次安装依赖环境（Java 8, Maven, Node.js）
# =============================================

set -e

export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# 颜色定义
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
RESET="\033[0m"

# 路径配置（固定路径，不要改）
DEPLOY_ROOT="/opt/jupyterhub_project"
PROJECT_DIR="${DEPLOY_ROOT}/jupyterhub-admin"
BACKEND_SRC_DIR="${PROJECT_DIR}/backend"
FRONTEND_SRC_DIR="${PROJECT_DIR}/frontend"
BACKEND_DEPLOY_DIR="${DEPLOY_ROOT}/backend"
FRONTEND_DEPLOY_DIR="${DEPLOY_ROOT}/frontend"
LOG_DIR="${DEPLOY_ROOT}/logs"

echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  JupyterHub 管理平台 - 一键部署${RESET}"
echo -e "${GREEN}========================================${RESET}"
echo ""
echo -e "${YELLOW}  项目目录: ${PROJECT_DIR}${RESET}"
echo ""

# ==================== 环境安装 ====================
if [[ "$1" == "--install" ]]; then
    echo -e "${YELLOW}[环境准备] 安装基础依赖...${RESET}"

    # 设置时区
    sudo timedatectl set-timezone Asia/Shanghai
    echo -e "${GREEN}  ✅ 时区已设置为 Asia/Shanghai${RESET}"

    # 更新包管理器
    sudo apt update -y
    echo -e "${GREEN}  ✅ apt 更新完成${RESET}"

    # 安装 Java 8（Spring Boot 2.7.x 需要）
    sudo apt install -y openjdk-8-jdk
    echo -e "${GREEN}  ✅ Java 8 已安装${RESET}"

    # 安装 Maven
    sudo apt install -y maven
    echo -e "${GREEN}  ✅ Maven 已安装${RESET}"

    # 安装 Node.js 18+（Vite需要）
    curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
    sudo apt install -y nodejs
    echo -e "${GREEN}  ✅ Node.js 18 已安装${RESET}"

    echo ""
    echo -e "${GREEN}========================================${RESET}"
    echo -e "${GREEN}  环境安装完成，版本验证：${RESET}"
    echo -e "${GREEN}========================================${RESET}"
    java -version
    echo ""
    mvn -v | head -1
    echo ""
    node -v
    echo ""
fi

# ==================== 检查项目目录 ====================
echo -e "${YELLOW}[检查] 验证项目结构...${RESET}"
if [[ ! -d "${BACKEND_SRC_DIR}" ]]; then
    echo -e "${RED}错误: 未找到后端源码目录 ${BACKEND_SRC_DIR}${RESET}"
    echo -e "${YELLOW}请确保项目代码已上传到 ${PROJECT_DIR}${RESET}"
    exit 1
fi
if [[ ! -d "${FRONTEND_SRC_DIR}" ]]; then
    echo -e "${RED}错误: 未找到前端源码目录 ${FRONTEND_SRC_DIR}${RESET}"
    exit 1
fi
echo -e "${GREEN}  ✅ 项目结构验证通过${RESET}"
echo ""

# ==================== 创建部署目录 ====================
echo -e "${YELLOW}[1/5] 创建部署目录...${RESET}"
sudo mkdir -p "${BACKEND_DEPLOY_DIR}"
sudo mkdir -p "${FRONTEND_DEPLOY_DIR}"
sudo mkdir -p "${LOG_DIR}"
echo -e "${GREEN}  ✅ 目录创建完成${RESET}"

# ==================== 停止旧服务 ====================
echo -e "${YELLOW}[2/5] 停止旧服务...${RESET}"
if systemctl is-active --quiet jupyterhub-admin-backend; then
    sudo systemctl stop jupyterhub-admin-backend
    echo -e "${YELLOW}  - 已停止后端服务${RESET}"
fi
if systemctl is-active --quiet jupyterhub-admin-frontend; then
    sudo systemctl stop jupyterhub-admin-frontend
    echo -e "${YELLOW}  - 已停止前端服务${RESET}"
fi
echo -e "${GREEN}  ✅ 旧服务已停止${RESET}"
echo ""

# ==================== 编译后端 ====================
echo -e "${YELLOW}[3/5] 编译后端服务 (Maven)...${RESET}"
cd "${BACKEND_SRC_DIR}"
echo -e "${YELLOW}  - 清理旧构建...${RESET}"
mvn clean -q
echo -e "${YELLOW}  - 编译打包 (跳过测试)...${RESET}"
mvn package -DskipTests -q

# 复制 JAR 到部署目录
JAR_FILE=$(find "${BACKEND_SRC_DIR}/target" -name "jupyterhub-admin-*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
if [[ -z "${JAR_FILE}" ]]; then
    echo -e "${RED}错误: 未找到后端 JAR 文件${RESET}"
    exit 1
fi
sudo cp "${JAR_FILE}" "${BACKEND_DEPLOY_DIR}/jupyterhub-admin.jar"
echo -e "${GREEN}  ✅ 后端编译完成: ${JAR_FILE}${RESET}"
echo ""

# ==================== 部署前端（源码模式，npm run dev） ====================
echo -e "${YELLOW}[4/5] 部署前端源码...${RESET}"
cd "${FRONTEND_SRC_DIR}"
if [[ ! -d "node_modules" ]]; then
    echo -e "${YELLOW}  - 首次部署，安装依赖...${RESET}"
    npm install --registry=https://registry.npmmirror.com --loglevel=error
fi
# 前端直接用源码目录运行，不需要复制
echo -e "${GREEN}  ✅ 前端依赖已安装${RESET}"
echo ""

# ==================== 安装 systemd 服务 ====================
echo -e "${YELLOW}[5/5] 安装 systemd 服务...${RESET}"

# 安装后端服务
sudo cp "${PROJECT_DIR}/jupyterhub-admin-backend.service" /etc/systemd/system/
# 安装前端服务
sudo cp "${PROJECT_DIR}/jupyterhub-admin-frontend.service" /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable jupyterhub-admin-backend
sudo systemctl enable jupyterhub-admin-frontend
echo -e "${GREEN}  ✅ 服务已注册并启用开机自启${RESET}"

# ==================== 启动服务 ====================
echo ""
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  启动服务...${RESET}"
echo -e "${GREEN}========================================${RESET}"

sudo systemctl start jupyterhub-admin-backend
echo -e "${YELLOW}  等待后端启动 (10秒)...${RESET}"
sleep 10

sudo systemctl start jupyterhub-admin-frontend
echo -e "${YELLOW}  等待前端启动 (5秒)...${RESET}"
sleep 5

echo ""
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  部署完成！${RESET}"
echo -e "${GREEN}========================================${RESET}"
echo ""
echo -e "${YELLOW}  访问地址: http://$(hostname -I | awk '{print $1}'):9091${RESET}"
echo -e "${YELLOW}  后端API: http://$(hostname -I | awk '{print $1}'):9090${RESET}"
echo ""
echo -e "${YELLOW}  服务状态:${RESET}"
echo -e "  后端: $(systemctl is-active jupyterhub-admin-backend)"
echo -e "  前端: $(systemctl is-active jupyterhub-admin-frontend)"
echo ""
echo -e "${YELLOW}  查看日志:${RESET}"
echo -e "  后端日志: sudo journalctl -u jupyterhub-admin-backend -f"
echo -e "  前端日志: sudo journalctl -u jupyterhub-admin-frontend -f"
echo -e "  后端错误: cat /opt/jupyterhub_project/logs/backend-error.log"
echo ""
echo -e "${YELLOW}  服务管理:${RESET}"
echo -e "  启动: sudo systemctl start jupyterhub-admin-backend jupyterhub-admin-frontend"
echo -e "  停止: sudo systemctl stop jupyterhub-admin-backend jupyterhub-admin-frontend"
echo -e "  重启: sudo systemctl restart jupyterhub-admin-backend jupyterhub-admin-frontend"
echo ""