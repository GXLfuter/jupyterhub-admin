#!/bin/bash
# =============================================
# JupyterHub 管理平台 - 一键打包脚本
# 适用系统: Ubuntu 22.04 x86_64
# 功能: 编译后端 JAR + 构建前端静态文件
# =============================================

set -e

# 颜色定义
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
RESET="\033[0m"

# 项目根目录（脚本所在目录）
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${PROJECT_DIR}/backend"
FRONTEND_DIR="${PROJECT_DIR}/frontend"
OUTPUT_DIR="${PROJECT_DIR}/dist-package"

echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  JupyterHub 管理平台 - 一键打包${RESET}"
echo -e "${GREEN}========================================${RESET}"
echo ""
echo -e "${YELLOW}  项目目录: ${PROJECT_DIR}${RESET}"
echo ""

# 检查 Java
echo -e "${YELLOW}[1/3] 检查 Java 环境...${RESET}"
if ! command -v java &> /dev/null; then
    echo -e "${RED}错误: 未找到 Java，请先安装 openjdk-8-jdk${RESET}"
    exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
echo -e "${GREEN}  Java 版本: ${JAVA_VERSION}${RESET}"

# 检查 Maven
echo -e "${YELLOW}[2/3] 检查 Maven 环境...${RESET}"
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}错误: 未找到 Maven，请先安装 maven${RESET}"
    exit 1
fi
echo -e "${GREEN}  Maven 版本: $(mvn -v | head -1 | awk '{print $3}')${RESET}"

# 检查 Node.js
echo -e "${YELLOW}[3/3] 检查 Node.js 环境...${RESET}"
if ! command -v node &> /dev/null; then
    echo -e "${RED}错误: 未找到 Node.js，请先安装 Node.js 18+${RESET}"
    exit 1
fi
echo -e "${GREEN}  Node.js 版本: $(node -v)${RESET}"
echo ""

# ==================== 打包后端 ====================
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  步骤 1/3: 编译后端 (Spring Boot)${RESET}"
echo -e "${GREEN}========================================${RESET}"
cd "${BACKEND_DIR}"
echo -e "${YELLOW}  清理旧构建...${RESET}"
mvn clean -q
echo -e "${YELLOW}  编译打包 (跳过测试)...${RESET}"
mvn package -DskipTests -q
echo -e "${GREEN}  ✅ 后端编译完成${RESET}"
echo ""

# ==================== 打包前端 ====================
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  步骤 2/3: 构建前端 (Vue3 + Vite)${RESET}"
echo -e "${GREEN}========================================${RESET}"
cd "${FRONTEND_DIR}"
if [ ! -d "node_modules" ]; then
    echo -e "${YELLOW}  首次构建，安装依赖...${RESET}"
    npm install --registry=https://registry.npmmirror.com --loglevel=error
fi
echo -e "${YELLOW}  构建静态文件...${RESET}"
npm run build
echo -e "${GREEN}  ✅ 前端构建完成${RESET}"
echo ""

# ==================== 整理输出 ====================
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  步骤 3/3: 整理部署包${RESET}"
echo -e "${GREEN}========================================${RESET}"
rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}/backend"
mkdir -p "${OUTPUT_DIR}/frontend"

# 复制后端 JAR
JAR_FILE=$(find "${BACKEND_DIR}/target" -name "jupyterhub-admin-*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
if [ -z "${JAR_FILE}" ]; then
    echo -e "${RED}错误: 未找到后端 JAR 文件${RESET}"
    exit 1
fi
cp "${JAR_FILE}" "${OUTPUT_DIR}/backend/jupyterhub-admin.jar"
echo -e "${GREEN}  ✅ 后端 JAR: ${JAR_FILE}${RESET}"

# 复制前端 dist
cp -r "${FRONTEND_DIR}/dist/"* "${OUTPUT_DIR}/frontend/"
echo -e "${GREEN}  ✅ 前端静态文件: ${FRONTEND_DIR}/dist${RESET}"

# 复制部署脚本
cp "${PROJECT_DIR}/deploy.sh" "${OUTPUT_DIR}/" 2>/dev/null || true
cp "${PROJECT_DIR}/jupyterhub-admin-backend.service" "${OUTPUT_DIR}/" 2>/dev/null || true
cp "${PROJECT_DIR}/nginx.conf" "${OUTPUT_DIR}/" 2>/dev/null || true
echo -e "${GREEN}  ✅ 部署脚本已复制${RESET}"
echo ""

# ==================== 完成 ====================
echo -e "${GREEN}========================================${RESET}"
echo -e "${GREEN}  打包完成！${RESET}"
echo -e "${GREEN}========================================${RESET}"
echo ""
echo -e "${YELLOW}  部署包位置: ${OUTPUT_DIR}${RESET}"
echo -e "${YELLOW}  目录结构:${RESET}"
find "${OUTPUT_DIR}" -maxdepth 2 | head -20 | sed 's/^/  /'
echo ""
echo -e "${YELLOW}  下一步: 将 ${OUTPUT_DIR} 拷贝到服务器${RESET}"
echo -e "${YELLOW}  然后在服务器运行: cd ${OUTPUT_DIR} && sudo bash deploy.sh${RESET}"
echo ""
