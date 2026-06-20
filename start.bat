@echo off
REM JupyterHub管理平台 - Windows启动脚本

echo ==========================================
echo JupyterHub管理平台启动脚本 (Windows)
echo ==========================================

set SCRIPT_DIR=%~dp0
set BACKEND_DIR=%SCRIPT_DIR%backend
set FRONTEND_DIR=%SCRIPT_DIR%frontend

REM 启动后端
echo.
echo [1/2] 启动后端服务 (端口 9090)...
cd /d %BACKEND_DIR%
start "JupyterHub Backend" cmd /c "mvn spring-boot:run"

echo 等待后端服务启动...
timeout /t 15 /nobreak > nul

REM 启动前端
echo.
echo [2/2] 启动前端服务 (端口 9091)...
cd /d %FRONTEND_DIR%

if not exist "node_modules" (
    echo 安装前端依赖...
    call npm install
)

start "JupyterHub Frontend" cmd /c "npm run dev"

echo.
echo ==========================================
echo 服务已启动！
echo ==========================================
echo 前端地址: http://localhost:9091
echo 后端地址: http://localhost:9090
echo 默认账号: admin / hsj@2024
echo ==========================================
pause
