#!/bin/bash

# JupyterHub管理平台 - 启动脚本

echo "=========================================="
echo "JupyterHub管理平台启动脚本"
echo "=========================================="

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/backend"
FRONTEND_DIR="$SCRIPT_DIR/frontend"

# 启动后端
echo ""
echo "[1/2] 启动后端服务 (端口 9090)..."
cd "$BACKEND_DIR"

# 检查Maven
if ! command -v mvn &> /dev/null; then
    echo "错误: Maven未安装，请先安装Maven"
    exit 1
fi

# 编译并启动
mvn spring-boot:run > backend.log 2>&1 &
BACKEND_PID=$!

echo "后端服务 PID: $BACKEND_PID"
echo "后端日志: $BACKEND_DIR/backend.log"

# 等待后端启动
echo "等待后端服务启动..."
sleep 10

# 检查后端是否启动成功
if ! curl -s http://localhost:9090/api/auth/validate > /dev/null 2>&1; then
    echo "警告: 后端服务可能未启动成功，请检查日志"
fi

# 启动前端
echo ""
echo "[2/2] 启动前端服务 (端口 9091)..."
cd "$FRONTEND_DIR"

# 检查Node.js
if ! command -v node &> /dev/null; then
    echo "错误: Node.js未安装，请先安装Node.js"
    exit 1
fi

# 安装依赖
if [ ! -d "node_modules" ]; then
    echo "安装前端依赖..."
    npm install
fi

# 启动前端
npm run dev > frontend.log 2>&1 &
FRONTEND_PID=$!

echo "前端服务 PID: $FRONTEND_PID"
echo "前端日志: $FRONTEND_DIR/frontend.log"

echo ""
echo "=========================================="
echo "服务已启动！"
echo "=========================================="
echo "前端地址: http://localhost:9091"
echo "后端地址: http://localhost:9090"
echo "默认账号: admin / hsj@2024"
echo ""
echo "停止服务: kill $BACKEND_PID $FRONTEND_PID"
echo "=========================================="
