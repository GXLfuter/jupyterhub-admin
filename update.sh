#!/bin/bash
# =============================================
# JupyterHub 管理平台 - 一键更新部署脚本
# 用法: sudo bash update.sh
# 功能: git pull + 构建后端 + 备份 + 替换JAR + 重启 + 验证
# =============================================

set -e

GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
BLUE="\033[34m"
RESET="\033[0m"

# 路径配置
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_SRC="${PROJECT_DIR}/backend"
DEPLOY_DIR="/opt/jupyterhub_project/backend"
LOG_DIR="/opt/jupyterhub_project/logs"
BACKUP_DIR="/opt/jupyterhub_project/backend/backups"
JAR_NAME="jupyterhub-admin.jar"
SERVICE_NAME="jupyterhub-admin-backend"

print_success() { echo -e "${GREEN}✅ ${1}${RESET}"; }
print_error()   { echo -e "${RED}❌ ${1}${RESET}"; }
print_info()    { echo -e "${BLUE}ℹ️  ${1}${RESET}"; }
print_warning() { echo -e "${YELLOW}⚠️  ${1}${RESET}"; }

# 检查 root
check_root() {
    if [ "$EUID" -ne 0 ]; then
        print_error "请使用 sudo 运行此脚本: sudo bash update.sh"
        exit 1
    fi
}

# 回滚函数
rollback() {
    print_warning "部署失败，正在回滚..."
    local latest_backup=$(ls -t "${BACKUP_DIR}/"*.jar 2>/dev/null | head -1)
    if [ -n "$latest_backup" ]; then
        cp "$latest_backup" "${DEPLOY_DIR}/${JAR_NAME}"
        systemctl restart "${SERVICE_NAME}"
        sleep 5
        if systemctl is-active --quiet "${SERVICE_NAME}"; then
            print_success "回滚成功！已恢复到: $(basename "$latest_backup")"
        else
            print_error "回滚也失败了，请手动检查"
        fi
    else
        print_error "没有找到备份文件，无法回滚"
    fi
    exit 1
}

# 主流程
main() {
    check_root

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════╗${RESET}"
    echo -e "${GREEN}║   JupyterHub Admin - 一键更新部署         ║${RESET}"
    echo -e "${GREEN}╚══════════════════════════════════════════╝${RESET}"
    echo ""

    # ========== 1. 拉取最新代码 ==========
    print_info "[1/6] 拉取最新代码..."
    cd "${PROJECT_DIR}"
    if ! git pull; then
        print_error "git pull 失败"
        exit 1
    fi
    print_success "代码已更新"

    # ========== 2. 构建后端 ==========
    print_info "[2/6] 构建后端 JAR..."
    cd "${BACKEND_SRC}"
    if ! mvn clean package -DskipTests -q; then
        print_error "构建失败"
        exit 1
    fi
    JAR_FILE=$(find target -name "jupyterhub-admin-*.jar" -not -name "*-sources.jar" -not -name "*-original.jar" | head -1)
    if [ -z "$JAR_FILE" ]; then
        print_error "未找到生成的 JAR 文件"
        exit 1
    fi
    print_success "构建完成: $(basename "$JAR_FILE") ($(du -h "$JAR_FILE" | cut -f1))"

    # ========== 3. 备份当前版本 ==========
    print_info "[3/6] 备份当前版本..."
    mkdir -p "${BACKUP_DIR}"
    if [ -f "${DEPLOY_DIR}/${JAR_NAME}" ]; then
        BACKUP_NAME="${BACKUP_DIR}/jupyterhub-admin-$(date +%Y%m%d_%H%M%S).jar"
        cp "${DEPLOY_DIR}/${JAR_NAME}" "${BACKUP_NAME}"
        print_success "已备份到: $(basename "$BACKUP_NAME")"
        # 只保留最近 5 个备份
        ls -t "${BACKUP_DIR}/"*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f
    else
        print_warning "未找到现有 JAR，跳过备份"
    fi

    # ========== 4. 替换 JAR ==========
    print_info "[4/6] 替换 JAR 文件..."
    cp "${BACKEND_SRC}/${JAR_FILE}" "${DEPLOY_DIR}/${JAR_NAME}"
    print_success "JAR 已替换"

    # ========== 5. 重启服务 ==========
    print_info "[5/6] 重启后端服务..."
    systemctl restart "${SERVICE_NAME}"
    sleep 5

    # 检查服务状态
    if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
        print_error "服务启动失败"
        rollback
    fi
    print_success "服务已启动"

    # ========== 6. 验证 ==========
    print_info "[6/6] 验证 API 响应..."
    local api_ok=false
    for i in {1..10}; do
        if curl -s http://127.0.0.1:9090/api/image-snapshot/status > /dev/null 2>&1; then
            api_ok=true
            break
        fi
        sleep 2
    done

    if [ "$api_ok" = true ]; then
        # 测两次，验证缓存是否生效
        local t1=$( (time curl -s http://127.0.0.1:9090/api/image-snapshot/status > /dev/null) 2>&1 | grep real | awk '{print $2}' )
        local t2=$( (time curl -s http://127.0.0.1:9090/api/image-snapshot/status > /dev/null) 2>&1 | grep real | awk '{print $2}' )
        print_success "API 验证通过（第一次: $t1, 第二次: $t2）"
    else
        print_warning "API 响应超时，但服务运行中，可能是启动慢，稍后再试"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════${RESET}"
    echo -e "${GREEN}  🎉 更新部署完成！${RESET}"
    echo -e "${GREEN}═══════════════════════════════════════════${RESET}"
    echo ""
    echo -e "${YELLOW}  服务状态:${RESET} $(systemctl is-active "${SERVICE_NAME}")"
    echo -e "${YELLOW}  查看日志:${RESET} sudo journalctl -u ${SERVICE_NAME} -f"
    echo -e "${YELLOW}  回滚命令:${RESET} sudo bash ${PROJECT_DIR}/update.sh --rollback"
    echo ""
}

# 回滚模式
if [ "$1" = "--rollback" ]; then
    check_root
    BACKUP_FILE=$(ls -t "${BACKUP_DIR}/"*.jar 2>/dev/null | head -1)
    if [ -z "$BACKUP_FILE" ]; then
        print_error "没有找到备份文件"
        exit 1
    fi
    print_info "回滚到: $(basename "$BACKUP_FILE")"
    read -p "  确认回滚? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_warning "已取消"
        exit 0
    fi
    cp "$BACKUP_FILE" "${DEPLOY_DIR}/${JAR_NAME}"
    systemctl restart "${SERVICE_NAME}"
    sleep 5
    if systemctl is-active --quiet "${SERVICE_NAME}"; then
        print_success "回滚成功！"
    else
        print_error "回滚后服务启动失败"
        exit 1
    fi
    exit 0
fi

main "$@"
