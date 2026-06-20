#!/bin/bash
# =============================================
# JupyterHub 管理平台 - 一键部署脚本（生产环境优化版）
# 适用系统: Ubuntu 22.04 / Debian 11+ / CentOS 7+
#
# 使用方式:
#   sudo bash deploy.sh --install     # 首次安装：安装依赖 + 部署 + 启动
#   sudo bash deploy.sh --deploy      # 更新部署：停止旧服务 + 构建 + 部署 + 启动
#   sudo bash deploy.sh --build       # 仅构建：编译后端 + 构建前端静态文件
#   sudo bash deploy.sh --start       # 仅启动服务
#   sudo bash deploy.sh --stop        # 仅停止服务
#   sudo bash deploy.sh --restart     # 重启服务
#   sudo bash deploy.sh --status      # 查看服务状态
#   sudo bash deploy.sh --logs        # 查看实时日志
#   sudo bash deploy.sh --rollback    # 回滚到上一版本（如果有备份）
#
# 部署架构:
#   - 后端: Spring Boot JAR + systemd (端口 9090)
#   - 前端: Nginx 托管静态文件（端口 80）
#   - 反向代理: Nginx 将 /api 转发到后端 9090 端口
# =============================================

set -e

export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8

# ============ 颜色定义 ============
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
BLUE="\033[34m"
RESET="\033[0m"

# ============ 路径配置 ============
DEPLOY_ROOT="/opt/jupyterhub_project"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_SRC_DIR="${PROJECT_DIR}/backend"
FRONTEND_SRC_DIR="${PROJECT_DIR}/frontend"
BACKEND_DEPLOY_DIR="${DEPLOY_ROOT}/backend"
FRONTEND_DEPLOY_DIR="${DEPLOY_ROOT}/frontend"
LOG_DIR="${DEPLOY_ROOT}/logs"
BACKUP_DIR="${DEPLOY_ROOT}/backup/$(date +%Y%m%d_%H%M%S)"
CONFIG_DIR="${DEPLOY_ROOT}/config"

# ============ 端口配置 ============
BACKEND_PORT=9090
NGINX_PORT=80

# ============ 检测 Linux 发行版 ============
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_NAME=$ID
        OS_VERSION=$VERSION_ID
    elif type lsb_release >/dev/null 2>&1; then
        OS_NAME=$(lsb_release -si | tr '[:upper:]' '[:lower:]')
        OS_VERSION=$(lsb_release -sr)
    elif [ -f /etc/redhat-release ]; then
        OS_NAME="centos"
        OS_VERSION=$(cat /etc/redhat-release | grep -oE '[0-9]+\.[0-9]+' | head -1)
    else
        OS_NAME=$(uname -s)
        OS_VERSION=$(uname -r)
    fi
    echo -e "${BLUE}检测到操作系统: ${OS_NAME} ${OS_VERSION}${RESET}"
}

# ============ 包管理器命令 ============
PKG_INSTALL=""
PKG_UPDATE=""
setup_package_manager() {
    case "$OS_NAME" in
        ubuntu|debian|linuxmint)
            PKG_INSTALL="apt-get install -y"
            PKG_UPDATE="apt-get update"
            ;;
        centos|rhel|fedora|rocky|almalinux)
            PKG_INSTALL="yum install -y"
            PKG_UPDATE="yum check-update || true"
            ;;
        *)
            PKG_INSTALL="apt-get install -y"
            PKG_UPDATE="apt-get update"
            ;;
    esac
}

# ============ 打印函数 ============
print_banner() {
    echo -e ""
    echo -e "${GREEN}╔══════════════════════════════════════════════╗${RESET}"
    echo -e "${GREEN}║         JupyterHub 管理平台 - 部署脚本         ║${RESET}"
    echo -e "${GREEN}╚══════════════════════════════════════════════╝${RESET}"
    echo -e ""
    echo -e "${YELLOW}  项目目录: ${PROJECT_DIR}${RESET}"
    echo -e "${YELLOW}  部署目录: ${DEPLOY_ROOT}${RESET}"
    echo -e ""
}

print_success() { echo -e "${GREEN}✅ ${1}${RESET}"; }
print_warning() { echo -e "${YELLOW}⚠️  ${1}${RESET}"; }
print_error()   { echo -e "${RED}❌ ${1}${RESET}"; }
print_info()    { echo -e "${BLUE}ℹ️  ${1}${RESET}"; }

# ============ 检查 root 权限 ============
check_root() {
    if [ "$EUID" -ne 0 ]; then
        print_error "请使用 sudo 或 root 权限运行此脚本"
        exit 1
    fi
}

# ============ 环境安装 ============
install_environment() {
    echo ""
    print_info "[环境准备] 安装基础依赖..."
    echo ""

    # 设置时区
    timedatectl set-timezone Asia/Shanghai 2>/dev/null || true
    print_success "时区已设置为 Asia/Shanghai"

    # 更新包管理器
    print_info "更新软件包索引..."
    $PKG_UPDATE > /dev/null 2>&1
    print_success "包管理器更新完成"

    # 安装 Java 11（更稳定，兼容 Spring Boot 2.7）
    if ! command -v java &> /dev/null; then
        print_info "安装 Java 11..."
        case "$OS_NAME" in
            ubuntu|debian|linuxmint)
                apt-get install -y openjdk-11-jdk > /dev/null 2>&1
                ;;
            centos|rhel|fedora|rocky|almalinux)
                yum install -y java-11-openjdk-devel > /dev/null 2>&1
                ;;
        esac
        print_success "Java 11 已安装"
    else
        print_success "Java 已存在: $(java -version 2>&1 | head -1)"
    fi

    # 安装 Maven
    if ! command -v mvn &> /dev/null; then
        print_info "安装 Maven..."
        case "$OS_NAME" in
            ubuntu|debian|linuxmint)
                apt-get install -y maven > /dev/null 2>&1
                ;;
            centos|rhel|fedora|rocky|almalinux)
                yum install -y maven > /dev/null 2>&1
                ;;
        esac
        print_success "Maven 已安装"
    else
        print_success "Maven 已存在: $(mvn -v 2>&1 | head -1 | awk '{print $3}')"
    fi

    # 安装 Node.js 18+
    if ! command -v node &> /dev/null || [ "$(node -v | cut -d'v' -f2 | cut -d'.' -f1)" -lt 18 ]; then
        print_info "安装 Node.js 18..."
        case "$OS_NAME" in
            ubuntu|debian|linuxmint)
                curl -fsSL https://deb.nodesource.com/setup_18.x | bash - > /dev/null 2>&1
                apt-get install -y nodejs > /dev/null 2>&1
                ;;
            centos|rhel|fedora|rocky|almalinux)
                curl -fsSL https://rpm.nodesource.com/setup_18.x | bash - > /dev/null 2>&1
                yum install -y nodejs > /dev/null 2>&1
                ;;
        esac
        print_success "Node.js 18 已安装"
    else
        print_success "Node.js 已存在: $(node -v)"
    fi

    # 安装 Nginx
    if ! command -v nginx &> /dev/null; then
        print_info "安装 Nginx..."
        case "$OS_NAME" in
            ubuntu|debian|linuxmint)
                apt-get install -y nginx > /dev/null 2>&1
                ;;
            centos|rhel|fedora|rocky|almalinux)
                yum install -y nginx > /dev/null 2>&1
                ;;
        esac
        systemctl enable nginx > /dev/null 2>&1
        print_success "Nginx 已安装并启用开机自启"
    else
        print_success "Nginx 已存在: $(nginx -v 2>&1)"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  环境安装完成！${RESET}"
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo ""
    echo "  Java:    $(java -version 2>&1 | head -1)"
    echo "  Maven:   $(mvn -v 2>&1 | head -1 | awk '{print $3}')"
    echo "  Node.js: $(node -v)"
    echo "  Nginx:   $(nginx -v 2>&1)"
    echo ""
}

# ============ 验证项目结构 ============
validate_project() {
    print_info "验证项目结构..."
    if [ ! -d "${BACKEND_SRC_DIR}" ]; then
        print_error "未找到后端目录: ${BACKEND_SRC_DIR}"
        exit 1
    fi
    if [ ! -f "${BACKEND_SRC_DIR}/pom.xml" ]; then
        print_error "未找到 pom.xml: ${BACKEND_SRC_DIR}/pom.xml"
        exit 1
    fi
    if [ ! -d "${FRONTEND_SRC_DIR}" ]; then
        print_error "未找到前端目录: ${FRONTEND_SRC_DIR}"
        exit 1
    fi
    if [ ! -f "${FRONTEND_SRC_DIR}/package.json" ]; then
        print_error "未找到 package.json: ${FRONTEND_SRC_DIR}/package.json"
        exit 1
    fi
    print_success "项目结构验证通过"
}

# ============ 创建部署目录 ============
create_directories() {
    print_info "创建部署目录..."
    mkdir -p "${BACKEND_DEPLOY_DIR}"
    mkdir -p "${FRONTEND_DEPLOY_DIR}"
    mkdir -p "${LOG_DIR}"
    mkdir -p "${CONFIG_DIR}"
    mkdir -p "${DEPLOY_ROOT}/backup"
    print_success "部署目录创建完成"
}

# ============ 备份当前版本 ============
backup_current_version() {
    print_info "备份当前部署版本..."
    local has_backup=false

    # 备份后端
    if [ -f "${BACKEND_DEPLOY_DIR}/jupyterhub-admin.jar" ]; then
        mkdir -p "${BACKUP_DIR}/backend"
        cp "${BACKEND_DEPLOY_DIR}/jupyterhub-admin.jar" "${BACKUP_DIR}/backend/"
        print_success "后端 JAR 已备份"
        has_backup=true
    fi

    # 备份前端
    if [ -d "${FRONTEND_DEPLOY_DIR}/assets" ] || [ -f "${FRONTEND_DEPLOY_DIR}/index.html" ]; then
        mkdir -p "${BACKUP_DIR}/frontend"
        cp -r "${FRONTEND_DEPLOY_DIR}/"* "${BACKUP_DIR}/frontend/" 2>/dev/null || true
        print_success "前端静态文件已备份"
        has_backup=true
    fi

    # 备份配置
    if [ -f "/etc/systemd/system/jupyterhub-admin-backend.service" ]; then
        mkdir -p "${BACKUP_DIR}/config"
        cp "/etc/systemd/system/jupyterhub-admin-backend.service" "${BACKUP_DIR}/config/" 2>/dev/null || true
        cp "/etc/nginx/conf.d/jupyterhub-admin.conf" "${BACKUP_DIR}/config/" 2>/dev/null || true
    fi

    if [ "$has_backup" = true ]; then
        print_success "备份完成: ${BACKUP_DIR}"
        echo "${BACKUP_DIR}" > "${DEPLOY_ROOT}/backup/last_backup.txt"
    else
        print_warning "未找到可备份的内容（首次部署）"
    fi
}

# ============ 构建后端 ============
build_backend() {
    echo ""
    echo -e "${GREEN}[1/2] 编译后端 (Spring Boot)${RESET}"
    echo "──────────────────────────────────────────"
    cd "${BACKEND_SRC_DIR}"

    print_info "清理旧构建..."
    mvn clean -q 2>&1 | tail -5

    print_info "编译打包 (跳过测试)..."
    if ! mvn package -DskipTests -q 2>&1; then
        print_error "后端编译失败，请检查 Maven 输出"
        exit 1
    fi

    # 查找 JAR 文件
    local JAR_FILE
    JAR_FILE=$(find "${BACKEND_SRC_DIR}/target" -name "jupyterhub-admin-*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
    if [ -z "${JAR_FILE}" ]; then
        print_error "未找到后端 JAR 文件"
        exit 1
    fi

    # 复制到部署目录
    cp "${JAR_FILE}" "${BACKEND_DEPLOY_DIR}/jupyterhub-admin.jar"
    print_success "后端编译完成: $(basename "${JAR_FILE}") ($(du -h "${JAR_FILE}" | cut -f1))"
}

# ============ 构建前端 ============
build_frontend() {
    echo ""
    echo -e "${GREEN}[2/2] 构建前端 (Vue3 + Vite)${RESET}"
    echo "──────────────────────────────────────────"
    cd "${FRONTEND_SRC_DIR}"

    # 安装依赖
    if [ ! -d "node_modules" ]; then
        print_info "首次构建，安装依赖..."
        npm install --registry=https://registry.npmmirror.com --loglevel=error 2>&1
        print_success "依赖安装完成"
    fi

    # 构建
    print_info "构建静态文件..."
    if ! npm run build 2>&1; then
        print_error "前端构建失败"
        exit 1
    fi

    # 复制静态文件到部署目录
    if [ ! -d "dist" ]; then
        print_error "前端构建失败：未找到 dist 目录"
        exit 1
    fi

    rm -rf "${FRONTEND_DEPLOY_DIR}/"*
    cp -r dist/* "${FRONTEND_DEPLOY_DIR}/"
    print_success "前端构建完成: $(du -sh dist | cut -f1)"
}

# ============ 停止服务 ============
stop_services() {
    print_info "停止旧服务..."
    if systemctl is-active --quiet jupyterhub-admin-backend; then
        systemctl stop jupyterhub-admin-backend
        print_success "后端服务已停止"
    fi
    if systemctl is-active --quiet nginx; then
        # 只重载 nginx，不停止，避免完全断网
        print_success "Nginx 保持运行，稍后重载配置"
    fi
}

# ============ 安装后端 systemd 服务 ============
install_backend_service() {
    print_info "安装后端 systemd 服务..."
    cat > /etc/systemd/system/jupyterhub-admin-backend.service << 'EOF'
[Unit]
Description=JupyterHub Admin Backend Service
After=network.target nginx.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/jupyterhub_project/backend
ExecStart=/usr/bin/java -Xmx1g -Xms256m -Djava.security.egd=file:/dev/./urandom -Dspring.profiles.active=prod -jar /opt/jupyterhub_project/backend/jupyterhub-admin.jar
StandardOutput=append:/opt/jupyterhub_project/logs/backend.log
StandardError=append:/opt/jupyterhub_project/logs/backend-error.log
Restart=always
RestartSec=5
Environment="JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64"
Environment="LANG=en_US.UTF-8"
Environment="LC_ALL=en_US.UTF-8"
Environment="TZ=Asia/Shanghai"
# 内存限制
LimitMEMLOCK=infinity

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable jupyterhub-admin-backend > /dev/null 2>&1
    print_success "后端服务已注册并启用开机自启"
}

# ============ 安装 Nginx 配置 ============
install_nginx_config() {
    print_info "配置 Nginx 反向代理..."

    cat > /etc/nginx/conf.d/jupyterhub-admin.conf << EOF
server {
    listen ${NGINX_PORT};
    server_name _;

    # 前端静态文件
    location / {
        root ${FRONTEND_DEPLOY_DIR};
        try_files \$uri \$uri/ /index.html;
        index index.html;

        # 静态资源缓存
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            expires 30d;
            add_header Cache-Control "public, immutable";
            access_log off;
        }

        # HTML 不缓存（确保刷新能拿到最新版本）
        location ~* \.html$ {
            add_header Cache-Control "no-store, no-cache, must-revalidate";
        }
    }

    # 后端 API 代理
    location /api {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        client_max_body_size 1000m;
        proxy_connect_timeout 300s;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    # WebSocket 代理（聊天功能）
    location /ws {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }
}
EOF

    # 测试并重载 Nginx
    if nginx -t -q 2>&1; then
        systemctl reload nginx
        print_success "Nginx 配置已重载"
    else
        print_error "Nginx 配置测试失败，请手动检查"
        nginx -t 2>&1
        exit 1
    fi
}

# ============ 启动服务 ============
start_services() {
    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  启动服务${RESET}"
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo ""

    # 启动后端
    print_info "启动后端服务 (端口 ${BACKEND_PORT})..."
    systemctl start jupyterhub-admin-backend
    sleep 5

    # 检查后端是否启动成功
    local backend_running=false
    for i in {1..12}; do
        if systemctl is-active --quiet jupyterhub-admin-backend; then
            backend_running=true
            print_success "后端服务已启动"
            break
        fi
        sleep 2
    done

    if [ "$backend_running" = false ]; then
        print_error "后端服务启动失败，请查看日志"
        echo ""
        systemctl status jupyterhub-admin-backend --no-pager -l | tail -20
        exit 1
    fi

    # 启动 Nginx
    print_info "启动 Nginx (端口 ${NGINX_PORT})..."
    if ! systemctl is-active --quiet nginx; then
        systemctl start nginx
    fi
    systemctl reload nginx
    print_success "Nginx 已启动"

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  ✅ 部署完成！${RESET}"
    echo -e "${GREEN}═══════════════════════════════════════════════════${RESET}"
    echo ""

    local server_ip
    server_ip=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")
    echo -e "${YELLOW}  访问地址:${RESET}"
    echo -e "    前端管理平台: http://${server_ip}:${NGINX_PORT}"
    echo -e "    后端 API:    http://${server_ip}:${BACKEND_PORT}/api/"
    echo ""
    echo -e "${YELLOW}  服务状态:${RESET}"
    echo -e "    后端: $(systemctl is-active jupyterhub-admin-backend)"
    echo -e "    Nginx: $(systemctl is-active nginx)"
    echo ""
    echo -e "${YELLOW}  查看日志:${RESET}"
    echo -e "    后端: sudo journalctl -u jupyterhub-admin-backend -f"
    echo -e "    后端错误: tail -f ${LOG_DIR}/backend-error.log"
    echo -e "    Nginx: sudo tail -f /var/log/nginx/error.log"
    echo ""
    echo -e "${YELLOW}  服务管理:${RESET}"
    echo -e "    启动: sudo systemctl start jupyterhub-admin-backend nginx"
    echo -e "    停止: sudo systemctl stop jupyterhub-admin-backend"
    echo -e "    重启: sudo systemctl restart jupyterhub-admin-backend"
    echo ""
}

# ============ 查看状态 ============
show_status() {
    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  服务状态"
    echo "═══════════════════════════════════════════════════"
    echo ""
    echo "  后端服务 (jupyterhub-admin-backend):"
    systemctl status jupyterhub-admin-backend --no-pager -l | head -8 | sed 's/^/    /'
    echo ""
    echo "  Nginx:"
    systemctl status nginx --no-pager -l | head -8 | sed 's/^/    /'
    echo ""
    echo "  端口占用:"
    if command -v ss &> /dev/null; then
        ss -tlnp 2>/dev/null | grep -E ":(80|9090)" | sed 's/^/    /' || echo "    无"
    else
        netstat -tlnp 2>/dev/null | grep -E ":(80|9090)" | sed 's/^/    /' || echo "    无"
    fi
    echo ""
    echo "  部署目录:"
    echo "    后端: ${BACKEND_DEPLOY_DIR} ($(du -sh "${BACKEND_DEPLOY_DIR}" 2>/dev/null | cut -f1 || echo "-"))"
    echo "    前端: ${FRONTEND_DEPLOY_DIR} ($(du -sh "${FRONTEND_DEPLOY_DIR}" 2>/dev/null | cut -f1 || echo "-"))"
    echo "    日志: ${LOG_DIR}"
    echo ""
}

# ============ 查看日志 ============
show_logs() {
    echo ""
    print_info "显示后端实时日志 (Ctrl+C 退出)..."
    echo ""
    journalctl -u jupyterhub-admin-backend -f --no-pager -n 50
}

# ============ 回滚 ============
rollback_version() {
    print_info "准备回滚..."
    local last_backup
    last_backup=$(cat "${DEPLOY_ROOT}/backup/last_backup.txt" 2>/dev/null || true)

    if [ -z "$last_backup" ] || [ ! -d "$last_backup" ]; then
        # 找最新的备份目录
        last_backup=$(ls -td "${DEPLOY_ROOT}/backup/"*/ 2>/dev/null | head -1 | sed 's/\/$//')
    fi

    if [ -z "$last_backup" ]; then
        print_error "未找到备份文件，无法回滚"
        exit 1
    fi

    print_info "回滚到版本: $(basename "${last_backup}")"
    read -p "  确认回滚? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_warning "已取消回滚"
        exit 0
    fi

    # 停止服务
    stop_services

    # 恢复后端
    if [ -f "${last_backup}/backend/jupyterhub-admin.jar" ]; then
        cp "${last_backup}/backend/jupyterhub-admin.jar" "${BACKEND_DEPLOY_DIR}/"
        print_success "后端 JAR 已恢复"
    fi

    # 恢复前端
    if [ -f "${last_backup}/frontend/index.html" ]; then
        rm -rf "${FRONTEND_DEPLOY_DIR}/"*
        cp -r "${last_backup}/frontend/"* "${FRONTEND_DEPLOY_DIR}/"
        print_success "前端静态文件已恢复"
    fi

    # 恢复服务配置
    if [ -f "${last_backup}/config/jupyterhub-admin-backend.service" ]; then
        cp "${last_backup}/config/jupyterhub-admin-backend.service" /etc/systemd/system/
        systemctl daemon-reload
    fi

    # 启动服务
    start_services
    print_success "回滚完成"
}

# ============ 主流程 ============
main() {
    local action="${1:---deploy}"

    case "$action" in
        -h|--help|help)
            echo ""
            echo "用法: sudo bash deploy.sh [选项]"
            echo ""
            echo "选项:"
            echo "  --install    首次安装（依赖 + 构建 + 部署 + 启动）"
            echo "  --deploy     更新部署（构建 + 部署 + 重启）"
            echo "  --build      仅构建（不启动服务）"
            echo "  --start      启动服务"
            echo "  --stop       停止服务"
            echo "  --restart    重启服务"
            echo "  --status     查看服务状态"
            echo "  --logs       查看后端日志"
            echo "  --rollback   回滚到上一版本"
            echo "  -h, --help   显示此帮助"
            echo ""
            exit 0
            ;;
    esac

    check_root
    detect_os
    setup_package_manager
    print_banner

    case "$action" in
        --install)
            install_environment
            validate_project
            create_directories
            backup_current_version
            build_backend
            build_frontend
            install_backend_service
            install_nginx_config
            start_services
            ;;
        --deploy)
            validate_project
            create_directories
            backup_current_version
            stop_services
            build_backend
            build_frontend
            install_backend_service
            install_nginx_config
            start_services
            show_status
            ;;
        --build)
            validate_project
            create_directories
            build_backend
            build_frontend
            print_success "构建完成！文件位于:"
            echo "    后端: ${BACKEND_DEPLOY_DIR}/jupyterhub-admin.jar"
            echo "    前端: ${FRONTEND_DEPLOY_DIR}/"
            ;;
        --start)
            systemctl start jupyterhub-admin-backend
            systemctl start nginx
            sleep 3
            show_status
            ;;
        --stop)
            systemctl stop jupyterhub-admin-backend
            print_success "后端服务已停止"
            echo ""
            print_warning "提示: Nginx 保持运行，如需停止请执行: sudo systemctl stop nginx"
            ;;
        --restart)
            systemctl restart jupyterhub-admin-backend
            systemctl reload nginx
            sleep 3
            show_status
            ;;
        --status)
            show_status
            ;;
        --logs)
            show_logs
            ;;
        --rollback)
            rollback_version
            ;;
        *)
            print_error "未知选项: ${action}"
            echo "使用 --help 查看可用选项"
            exit 1
            ;;
    esac
}

main "$@"
