---
title: "Docker（二）：Docker Compose——多容器编排实战"
date: 2019-05-17
draft: false
categories: ["容器化"]
tags: ["Docker", "Docker Compose", "编排", "微服务", "DevOps"]
toc: true
---

## 前言

在上一篇文章中，我们学会了如何将单个应用容器化。但在实际项目中，一个服务往往依赖多个组件——比如 Web 应用需要数据库、缓存、消息队列等。如果每个容器都手动 `docker run`，不仅效率低，还容易出错。

**Docker Compose** 就是来解决这个问题的：通过一个 `docker-compose.yml` 文件，定义所有服务、网络、卷的配置，然后一条命令启动整个应用栈。

本文以 **Spring Boot + MySQL + Redis** 为例，演示 Compose 的完整使用。

<!--more-->

## 一、什么是 Docker Compose？

Docker Compose 是 Docker 官方的容器编排工具，核心能力：

| 能力 | 说明 |
|------|------|
| **多容器定义** | 一个文件定义所有服务 |
| **一键启停** | `docker-compose up / down` |
| **网络自动管理** | 同一 Compose 项目内的服务自动互通 |
| **依赖管理** | `depends_on` 控制启动顺序 |
| **环境隔离** | 不同项目（`-p`）互不干扰 |
| **扩展方便** | `--scale` 轻松扩容 |

## 二、安装与验证

Docker Compose 现在已集成到 Docker Desktop 中，无需单独安装。验证：

```bash
docker-compose --version
# Docker Compose version v2.x.x
```

如果使用 Linux 服务器，需单独安装：

```bash
# 下载二进制
sudo curl -L "https://github.com/docker/compose/releases/download/v2.4.1/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

---

## 三、docker-compose.yml 详解

### 3.1 基础结构

```yaml
version: '3.8'                        # Compose 文件格式版本

services:                              # 定义所有服务
  web:                                 # 服务名
    build: .                           # 从 Dockerfile 构建
    ports:
      - "8080:8080"
    depends_on:
      - db

  db:                                  # 另一个服务
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root123

networks:                              # 自定义网络
  app-net:
    driver: bridge

volumes:                               # 命名卷
  mysql-data:
```

### 3.2 常用配置项

```yaml
services:
  app:
    # 镜像来源
    image: nginx:alpine                # 直接从仓库拉取
    build: ./dir                       # 从 Dockerfile 构建
    build:
      context: .
      dockerfile: Dockerfile.prod

    # 端口映射
    ports:
      - "8080:8080"                    # 宿主机:容器
      - "443:8443"

    # 环境变量
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:mysql://db:3306/app
    env_file: .env                     # 从文件加载

    # 卷挂载
    volumes:
      - app-data:/app/data             # 命名卷
      - ./config:/app/config           # 绑定挂载

    # 资源限制
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 512M

    # 健康检查
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

    # 依赖
    depends_on:
      db:
        condition: service_healthy     # 等待健康检查通过

    # 网络
    networks:
      - frontend
      - backend

    # 重启策略
    restart: unless-stopped
```

---

## 四、实战：Spring Boot + MySQL + Redis

### 4.1 项目结构

```
docker-compose-demo/
├── app/                        # Spring Boot 应用
│   ├── Dockerfile
│   └── target/demo.jar
├── docker-compose.yml
├── .env                        # 环境变量
├── mysql/                      # MySQL 初始化脚本
│   └── init.sql
└── README.md
```

### 4.2 Dockerfile

```
FROM openjdk:8-jre-alpine
WORKDIR /app
COPY target/demo.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.3 docker-compose.yml

```yaml
version: '3.8'

services:
  # === Web 应用 ===
  app:
    build: ./app
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILE:-dev}
      DB_HOST: mysql
      DB_PORT: 3306
      DB_NAME: demo
      DB_USER: ${DB_USER:-root}
      DB_PASSWORD: ${DB_PASSWORD:-root123}
      REDIS_HOST: redis
      REDIS_PORT: 6379
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_started
    networks:
      - backend
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 40s

  # === MySQL ===
  mysql:
    image: mysql:8.0
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_PASSWORD:-root123}
      MYSQL_DATABASE: demo
    volumes:
      - mysql-data:/var/lib/mysql
      - ./mysql/init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - backend
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    restart: unless-stopped

  # === Redis ===
  redis:
    image: redis:6-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - backend
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 3
    restart: unless-stopped

networks:
  backend:
    driver: bridge

volumes:
  mysql-data:
  redis-data:
```

### 4.4 .env 文件

```bash
# 环境变量，Compose 自动加载
SPRING_PROFILE=prod
DB_USER=root
DB_PASSWORD=Root@123456
```

### 4.5 启动与验证

```bash
# 启动所有服务（-d 后台运行）
docker-compose up -d

# 输出示例：
# [+] Running 4/4
#  ✔ Network compose-demo_backend    Created
#  ✔ Volume "compose-demo_mysql-data"  Created
#  ✔ Volume "compose-demo_redis-data"  Created
#  ✔ Container compose-demo-redis-1   Started
#  ✔ Container compose-demo-mysql-1   Started
#  ✔ Container compose-demo-app-1     Started

# 查看服务状态
docker-compose ps

# 查看日志（指定服务）
docker-compose logs -f app
docker-compose logs -f mysql

# 查看实时日志（所有服务）
docker-compose logs -f

# 验证应用健康状态
curl http://localhost:8080/actuator/health

# 在容器内执行命令
docker-compose exec app ls -la
docker-compose exec mysql mysql -uroot -proot123 -e "SHOW DATABASES;"

# 停止所有服务（保留数据卷）
docker-compose down

# 完全清理（删除数据卷）
docker-compose down -v
```

### 4.6 扩容

Compose 支持水平扩展无状态服务：

```bash
# 将 app 扩展到 3 个实例
docker-compose up -d --scale app=3

# 验证
docker-compose ps
# 输出：
#   Name                    Command      State   Ports
# ─────────────────────────────────────────────────────
#   compose-demo-app-1     java -jar ... Up    0.0.0.0:8080->8080/tcp
#   compose-demo-app-2     java -jar ... Up    0.0.0.0:8081->8080/tcp
#   compose-demo-app-3     java -jar ... Up    0.0.0.0:8082->8080/tcp
```

> **注意**：扩展时需要配合负载均衡（如 Nginx）才能对外提供统一入口。

---

## 五、常用 Compose 命令速查

```bash
# 启动
docker-compose up -d            # 后台启动所有服务
docker-compose up -d app        # 启动指定服务

# 构建
docker-compose build            # 重新构建所有镜像
docker-compose build app        # 构建指定服务

# 查看
docker-compose ps               # 服务状态
docker-compose logs -f          # 日志（-f 持续跟踪）
docker-compose top              # 进程列表

# 执行
docker-compose exec app bash    # 进入容器

# 启停
docker-compose stop             # 停止（不删除）
docker-compose start            # 启动已停止的
docker-compose restart          # 重启

# 清理
docker-compose down             # 停止并删除容器/网络
docker-compose down -v          # 额外删除数据卷
docker-compose down --rmi all   # 额外删除镜像

# 其他
docker-compose config           # 验证配置文件语法
docker-compose images           # 列出使用的镜像
```

---

## 六、Compose 最佳实践

### 6.1 多环境管理

通过多个 Compose 文件和 `-f` 参数区分环境：

```bash
# 基础配置 + 开发环境覆盖
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# 基础配置 + 生产环境覆盖
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

`docker-compose.prod.yml` 示例：

```yaml
version: '3.8'
services:
  app:
    environment:
      SPRING_PROFILES_ACTIVE: prod
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 1G
  mysql:
    deploy:
      resources:
        limits:
          memory: 2G
```

### 6.2 依赖管理要点

```yaml
# ❌ 错误：depends_on 只保证启动顺序，不保证服务就绪
depends_on:
  - mysql

# ✅ 正确：配合 healthcheck 等待服务就绪
depends_on:
  mysql:
    condition: service_healthy
```

### 6.3 安全注意事项

- **不要在 Compose 文件中硬编码密码**，使用 `${VAR}` 配合 `.env` 文件
- 将 `.env` 加入 `.gitignore`，只提交 `.env.example`
- 生产环境建议使用 Docker Swarm Secrets 或外部密钥管理

---

## 总结

本文从 Compose 的核心概念出发，通过 **Spring Boot + MySQL + Redis** 的完整示例，演示了多容器编排的实战流程。

| 场景 | 命令 |
|------|------|
| 启动整个应用栈 | `docker-compose up -d` |
| 查看服务状态 | `docker-compose ps` |
| 跟踪日志 | `docker-compose logs -f` |
| 优雅关闭 | `docker-compose down` |

下一篇：[Docker 网络与存储深度配置]({{< relref "post/docker-network-storage" >}})，学习如何配置网络模式、管理持久化数据。
