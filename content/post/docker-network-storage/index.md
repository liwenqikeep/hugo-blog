---
title: "Docker（三）：网络与存储深度配置"
date: 2019-05-24
draft: false
categories: ["容器化"]
tags: ["Docker", "网络", "存储", "Volume", "数据持久化"]
toc: true
---

## 前言

在前两篇文章中，我们学会了单容器运行和多容器编排。但容器之间如何通信？数据如何持久化？这些都是生产环境中必须搞懂的问题。

本文分两大部分：

- **网络**：Docker 网络模型、通信模式、跨主机网络
- **存储**：数据卷类型、挂载方式、备份与迁移

<!--more-->

## 第一部分：Docker 网络

## 一、网络模式总览

Docker 提供五种网络模式：

| 网络模式 | 命令参数 | 隔离级别 | 适用场景 |
|----------|----------|----------|----------|
| **bridge** | `--network bridge` （默认） | 容器间隔离 | 单机多容器通信 |
| **host** | `--network host` | 无隔离 | 高性能需求，直接使用宿主机网络 |
| **none** | `--network none` | 完全隔离 | 安全性要求极高的离线容器 |
| **container** | `--network container:NAME` | 共享另一容器网络 | sidecar 模式（如日志收集） |
| **overlay** | `--network overlay` | 跨主机通信 | Docker Swarm 集群 |

### 1.1 bridge 模式（默认）

```
宿主机
┌───────────────────────────────────────┐
│  docker0 (172.17.0.1)                 │
│  ┌──────────┐   ┌──────────┐          │
│  │ Container│   │ Container│          │
│  │ 172.17.0.2│   │ 172.17.0.3│          │
│  └──────────┘   └──────────┘          │
│       │                │              │
│       └───── veth ─────┘              │
│              │                        │
│           docker0 bridge              │
│              │                        │
│          eth0 (物理网卡)               │
│              │                        │
│          外部网络                      │
└───────────────────────────────────────┘
```

- Docker 默认创建一个名为 `docker0` 的 Linux 网桥
- 每个容器通过 veth pair 连接到 docker0
- 容器之间通过 IP 通信，默认不 DNS 解析

### 1.2 host 模式

```bash
docker run --network host nginx
```

容器直接使用宿主机网络栈，**不进行网络隔离**：
- 无需端口映射（`-p` 参数无效）
- 性能最好，延迟最低
- 适合需要高网络性能的场景（如反向代理）

### 1.3 none 模式

```bash
docker run --network none alpine
```

容器有独立的网络栈，但**没有任何网络接口**：
- 适合纯离线计算任务
- 安全性最高的隔离

---

## 二、自定义网络

### 2.1 为什么需要自定义网络？

默认的 `docker0` 桥接网络有以下限制：
- 容器之间只能通过 IP 通信，不能通过容器名
- 网络隔离粒度不够

**自定义网络的优点**：
- **内置 DNS 解析**：容器名自动解析为 IP
- **更好的隔离性**：不同网络的容器完全隔离
- **灵活的配置**：自定义子网、网关、IP 范围

### 2.2 创建与使用

```bash
# 创建自定义桥接网络
docker network create --driver bridge \
  --subnet 172.20.0.0/16 \
  --gateway 172.20.0.1 \
  app-network

# 运行容器并连接到网络
docker run -d --name app1 --network app-network nginx
docker run -d --name app2 --network app-network alpine

# 在 app1 中可以通过容器名访问 app2
docker exec app1 ping app2

# 查看网络详情
docker network inspect app-network
# 输出会列出所有连接到该网络的容器

# 将已有容器连接到网络
docker network connect app-network existing-container

# 断开连接
docker network disconnect app-network existing-container

# 列出网络
docker network ls

# 删除网络
docker network rm app-network
```

### 2.3 容器间通信场景

**场景：Spring Boot 应用连接 MySQL**

```bash
# 1. 创建自定义网络
docker network create backend

# 2. 启动 MySQL，指定网络
docker run -d \
  --name mysql \
  --network backend \
  -e MYSQL_ROOT_PASSWORD=root123 \
  mysql:8.0

# 3. 启动应用，指定同一网络
docker run -d \
  --name app \
  --network backend \
  -p 8080:8080 \
  -e DB_HOST=mysql \          # ← 直接使用容器名作为主机名
  -e DB_PORT=3306 \
  app:latest

# ✅ 应用可以通过 "mysql:3306" 连接数据库
```

> **关键**：同一自定义网络中的容器可以通过**容器名**互相解析，不需要提前知道 IP。

---

## 三、端口映射与发布

```bash
# 随机映射（宿主机随机端口 → 容器 80 端口）
docker run -P nginx

# 指定映射（宿主机 8080 → 容器 80）
docker run -p 8080:80 nginx

# 指定协议（TCP + UDP）
docker run -p 8080:80/tcp -p 8080:80/udp nginx

# 绑定特定 IP
docker run -p 127.0.0.1:8080:80 nginx    # 仅本机可访问
docker run -p 0.0.0.0:8080:80 nginx      # 所有网卡可访问

# 查看端口映射
docker port container-name
# 输出：80/tcp -> 0.0.0.0:8080
```

---

## 第二部分：Docker 存储

## 四、数据持久化方式

Docker 提供三种数据挂载方式：

```
┌───────────────┬─────────────────┬──────────────────┐
│   Volume      │   Bind Mount    │     tmpfs        │
│   (命名卷)     │  (绑定挂载)      │  (临时内存)       │
├───────────────┼─────────────────┼──────────────────┤
│ Docker 管理    │ 用户指定路径      │ 内存中           │
│ /var/lib/docker│ /host/path      │ 不会写入磁盘      │
│ /volumes/xxx   │   :/container   │                  │
│               │                 │                  │
│ ✅ 推荐        │ ✅ 开发调试      │ ✅ 敏感数据       │
│ ✅ 可移植      │ ❌ 不可移植      │ ❌ 不可持久化     │
└───────────────┴─────────────────┴──────────────────┘
```

### 4.1 命名卷（Volume）

由 Docker 管理，存储在 `/var/lib/docker/volumes/` 下。

```bash
# 创建卷
docker volume create app-data

# 查看卷信息
docker volume inspect app-data
# [
#     {
#         "Driver": "local",
#         "Mountpoint": "/var/lib/docker/volumes/app-data/_data",
#         ...
#     }
# ]

# 使用卷
docker run -d \
  --name mysql \
  -v mysql-data:/var/lib/mysql \       # 命名卷
  -e MYSQL_ROOT_PASSWORD=root123 \
  mysql:8.0

# 列出所有卷
docker volume ls

# 清理未使用的卷
docker volume prune

# 删除卷
docker volume rm app-data
```

### 4.2 绑定挂载（Bind Mount）

将宿主机上的目录/文件直接挂载到容器中。

```bash
# 挂载目录（推荐使用绝对路径）
docker run -d \
  --name app \
  -v /host/app/config:/app/config \    # 绑定挂载
  -v /host/app/logs:/app/logs \
  app:latest

# macOS / Windows 上的路径
# macOS: -v /Users/me/project:/app
# Windows: -v C:\Users\me\project:/app

# 挂载单个文件
docker run -d \
  --name nginx \
  -v /host/nginx.conf:/etc/nginx/nginx.conf:ro \  # :ro 只读
  nginx:alpine

# 开发场景：挂载源码实现热更新
docker run -d \
  --name dev-app \
  -v $(pwd):/app \                    # 挂载当前目录
  -v /app/node_modules \              # 排除 node_modules
  node:12 npm run dev
```

> 绑定挂载的路径在 Docker Desktop for Windows/Mac 上需要先在 Docker 设置中共享目录。

### 4.3 tmpfs 挂载（内存存储）

数据存储在内存中，容器停止后自动消失。

```bash
# 挂载 tmpfs 到容器
docker run -d \
  --name app \
  --tmpfs /app/tmp:size=100M \        # 限制 100MB
  app:latest

# 或使用 --mount 语法
docker run -d \
  --name app \
  --mount type=tmpfs,destination=/app/tmp,tmpfs-size=100M \
  app:latest
```

适用场景：
- 临时缓存文件
- 敏感凭证（避免写入磁盘）
- 日志缓冲区

---

## 五、数据卷备份与迁移

### 5.1 备份

```bash
# 备份 MySQL 数据卷
docker run --rm \
  -v mysql-data:/source:ro \          # 挂载要备份的卷
  -v /host/backup:/backup \           # 挂载备份目标目录
  alpine \
  tar czf /backup/mysql-backup.tar.gz -C /source .
```

### 5.2 恢复

```bash
# 恢复到新卷
docker volume create mysql-data-restored

docker run --rm \
  -v mysql-data-restored:/target \
  -v /host/backup:/backup \
  alpine \
  tar xzf /backup/mysql-backup.tar.gz -C /target
```

### 5.3 迁移到另一台主机

```bash
# 主机A：备份并发送
docker run --rm -v mysql-data:/source:ro alpine \
  tar czf - -C /source . | ssh user@hostB "docker run --rm -i -v mysql-data:/target alpine tar xzf - -C /target"

# 或先压缩再 scp
docker run --rm -v mysql-data:/source:ro -v /tmp:/backup alpine \
  tar czf /backup/mysql-backup.tar.gz -C /source .
scp /tmp/mysql-backup.tar.gz user@hostB:/tmp/
```

---

## 六、存储驱动的选择

Docker 支持多种存储驱动，在 `/etc/docker/daemon.json` 中配置：

```json
{
  "storage-driver": "overlay2"
}
```

| 存储驱动 | 特点 | 推荐场景 |
|----------|------|----------|
| **overlay2** | 性能好，最常用 | ✅ 所有主流 Linux 发行版 |
| **aufs** | 旧版 Docker 默认 | ❌ 不再推荐 |
| **devicemapper** | 支持配置大小限制 | ❌ 仅 CentOS/RHEL 需要 |
| **btrfs/zfs** | 快照能力强 | 特定文件系统场景 |
| **vfs** | 无写时复制，性能差 | ❌ 仅测试用 |

**推荐**：所有新环境统一使用 `overlay2`。

---

## 七、实战：搭建 LNMP 环境

将网络和存储知识组合起来，搭建一套完整的 **Nginx + PHP + MySQL** 环境：

```yaml
version: '3.8'

services:
  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
      - ./html:/usr/share/nginx/html
    networks:
      - frontend
      - backend
    depends_on:
      - php

  php:
    image: php:7.4-fpm
    volumes:
      - ./html:/var/www/html
    networks:
      - backend
    environment:
      DB_HOST: mysql
      DB_USER: root
      DB_PASSWORD: root123

  mysql:
    image: mysql:8.0
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    networks:
      - backend
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: app

networks:
  frontend:
  backend:

volumes:
  mysql-data:
```

网络拓扑：

```
外部请求 ──▶ Nginx（frontend + backend）
                 │
                 ▼
                PHP（backend）
                 │
                 ▼
               MySQL（backend）
```

- Nginx 暴露 80 端口到外部（frontend），同时连接 backend 与 PHP 通信
- PHP 和 MySQL 只在 backend 网络中，外部不可直接访问
- MySQL 数据通过命名卷持久化

---

## 总结

| 主题 | 核心要点 |
|------|----------|
| **网络** | 默认 bridge 只有 IP 通信；自定义网络支持容器名 DNS 解析 |
| **host 模式** | 性能最好，不隔离，适合反向代理等场景 |
| **Volume** | Docker 管理，可移植，适合持久化数据 |
| **Bind Mount** | 依赖宿主机路径，适合开发和配置注入 |
| **tmpfs** | 内存存储，适合临时数据和凭证 |
| **存储驱动** | 统一使用 overlay2 |

下一篇：[Docker 生产环境部署最佳实践]({{< relref "post/docker-production" >}})，涵盖资源限制、安全加固、日志管理和监控。
