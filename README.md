# JupyterHub管理平台

一个用于管理JupyterHub学生容器的Java Web管理平台。

## 功能特性

- **登录认证** - 默认账号: admin / hsj@2024
- **容器管理** - 查看、删除学生容器
- **在线学生** - 查看当前在线的学生
- **文件上传** - 上传学习资料到共享目录
- **定时任务** - 定时清理学生数据
- **资源监控** - CPU、内存、磁盘监控
- **SSH终端** - WebSocket实时操作服务器

## 技术栈

### 后端
- Java 8
- Spring Boot 2.7.x
- Spring Security
- Apache MINA SSHD
- JSch (SSH连接)
- H2 Database

### 前端
- Vue 3
- Element Plus
- Axios
- xterm.js (终端)
- Pinia (状态管理)

## 项目结构

```
jupyterhub-admin/
├── backend/                 # Spring Boot后端 (端口9090)
│   ├── src/main/java/com/jupyterhub/
│   │   ├── config/         # 配置类
│   │   ├── controller/     # 控制器
│   │   ├── service/        # 服务层
│   │   ├── model/          # 实体类
│   │   ├── dto/            # 数据传输对象
│   │   └── common/         # 公共类
│   └── src/main/resources/
│       └── application.yml
├── frontend/               # Vue前端 (端口9091)
│   └── src/
│       ├── api/           # API接口
│       ├── components/     # 组件
│       ├── views/          # 页面
│       ├── router/         # 路由
│       └── stores/         # 状态管理
├── start.sh               # Linux启动脚本
└── start.bat              # Windows启动脚本
```

## 快速开始

### 环境要求

- JDK 1.8+
- Maven 3.6+
- Node.js 16+
- npm 或 yarn

### 启动步骤

#### 方式一: 使用启动脚本

```bash
# Linux/Mac
chmod +x start.sh
./start.sh

# Windows
start.bat
```

#### 方式二: 手动启动

**1. 启动后端**

```bash
cd backend
mvn clean package
java -jar target/jupyterhub-admin-1.0.0.jar
# 或使用 Maven
mvn spring-boot:run
```

**2. 启动前端**

```bash
cd frontend
npm install
npm run dev
```

### 访问地址

- 前端: http://localhost:9091
- 后端: http://localhost:9090
- 默认账号: admin / hsj@2024

## 服务器配置

在 `backend/src/main/resources/application.yml` 中配置:

```yaml
jupyterhub:
  host: 192.168.29.220
  port: 22
  username: root
  password: hsj@2024
  data-dir: /opt/jupyterhub_prod/data/users/
  shared-dir: /opt/jupyterhub_prod/data/shared
  container-prefix: jupyter-
```

## 部署到服务器

### 1. 打包后端

```bash
cd backend
mvn clean package -DskipTests
```

### 2. 打包前端

```bash
cd frontend
npm run build
```

### 3. 配置Nginx

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态文件
    location / {
        root /path/to/frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    # API代理
    location /api {
        proxy_pass http://localhost:9090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # WebSocket代理
    location /ws {
        proxy_pass http://localhost:9090;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

## API接口

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /api/auth/login | 用户登录 |
| GET | /api/containers | 获取所有容器 |
| DELETE | /api/containers/{username} | 删除容器 |
| GET | /api/students/online | 获取在线学生 |
| POST | /api/files/upload | 上传文件 |
| GET | /api/files/list | 获取文件列表 |
| POST | /api/tasks/cleanup | 执行清理 |
| GET | /api/monitor/stats | 获取服务器状态 |
| WS | /ws/terminal | SSH终端 |

## 注意事项

1. SSH连接使用root账号，密码在配置文件中
2. 文件上传限制100MB
3. 清理操作不可逆，请谨慎操作
4. 建议使用HTTPS访问

## License

MIT
