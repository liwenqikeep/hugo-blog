---
title: "Docker（一）：从概念到实战——容器化入门"
date: 2019-05-10
draft: false
categories: ["容器化"]
tags: ["Docker", "容器", "Dockerfile", "镜像", "DevOps"]
toc: true
---

## 前言

Docker 是近年来最火爆的 DevOps 工具之一，它让"**构建一次，到处运行**"成为现实。无论是开发环境搭建、微服务部署，还是 CI/CD 流水线，Docker 已经成为现代软件开发的基础设施。

本文是 Docker 系列的第一篇，从**核心概念**出发，配合**常用命令**和**一个完整的 Java 应用容器化实战**，帮助你快速上手 Docker。

后续文章预告：
- [Docker Compose：多容器编排]({{< relref "post/docker-compose" >}})
- [Docker 网络与存储深度配置]({{< relref "post/docker-network-storage" >}})
- [Docker 生产环境部署最佳实践]({{< relref "post/docker-production" >}})

<!--more-->

## 一、什么是容器？

### 1.1 容器 vs 虚拟机

在理解 Docker 之前，先搞清楚容器与虚拟机的本质区别：

```
┌─────────────────────────────┐      ┌─────────────────────────────┐
│         VM 架构              │      │       容器架构               │
│                             │      │                             │
│  ┌─────┐ ┌─────┐ ┌─────┐  │      │  ┌─────┐ ┌─────┐ ┌─────┐  │
│  │ App1│ │ App2│ │ App3│  │      │  │ App1│ │ App2│ │ App3│  │
│  ├─────┤ ├─────┤ ├─────┤  │      │  ├─────┤ ├─────┤ ├─────┤  │
│  │Guest│ │Guest│ │Guest│  │      │  │     │ │     │ │     │  │
│  │  OS │ │  OS │ │  OS │  │      │  └──┬──┘ └──┬──┘ └──┬──┘  │
│  ├─────┤ ├─────┤ ├─────┤  │      │     │       │       │     │
│  │Hypervisor (VMM)        │  │      │  ┌───────┴───────┴───┐  │
│  ├────────────────────────┤  │      │  │   Docker Engine    │  │
│  │     Host OS            │  │      │  ├────────────────────┤  │
│  ├────────────────────────┤  │      │  │     Host OS        │  │
│  │     Hardware           │  │      │  ├────────────────────┤  │
│  └─────────────────────────┘      │  │     Hardware         │  │
│                             │      │  └─────────────────────┘  │
└─────────────────────────────┘      └─────────────────────────────┘
```

| 特性 | 虚拟机 | 容器 |
|------|--------|------|
| **启动速度** | 分钟级（启动完整 Guest OS） | 秒级（共享宿主机内核） |
| **资源占用** | 高（每个 VM 独占 OS + 内存） | 低（仅进程级隔离） |
| **磁盘占用** | GB 级（包含完整 OS 镜像） | MB 级（仅应用 + 依赖） |
| **隔离级别** | 完全虚拟化，强隔离 | 进程级隔离（Namespace + Cgroups） |
| **移植性** | 依赖 Hypervisor 兼容性 | 一次构建，任意 Docker 环境运行 |

### 1.2 Docker 的核心理念

Docker 利用 Linux 内核的两项关键能力：

- **Namespace**：实现进程隔离——每个容器拥有独立的 PID、网络、文件系统、用户空间
- **Cgroups（Control Groups）**：实现资源限制——精确控制 CPU、内存、磁盘 IO

简单来说：**容器就是加了隔离限制的普通进程**。

---

## 二、Docker 核心概念

```
                       ┌──────────┐
                       │ Registry │  ← 镜像仓库（Docker Hub）
                       └────┬─────┘
                            │ pull / push
                       ┌────▼─────┐
              build    │  Image   │  ← 镜像（只读模板）
              Dockerfile ─────▶   │
                       └────┬─────┘
                            │ run
                       ┌────▼─────┐
                       │ Container│  ← 容器（运行中的实例）
                       └──────────┘
```

### 2.1 镜像（Image）

- **只读模板**，包含运行应用所需的完整文件系统（代码、运行时、库、环境变量、配置）
- 由多层（Layer）组成，每一层对应 Dockerfile 中的一条指令
- 多个镜像共享相同层，节省磁盘空间

### 2.2 容器（Container）

- 镜像的运行实例，**可读可写**
- 每个容器在镜像层之上增加一个可写层（Container Layer）
- 可以 start / stop / restart / rm

### 2.3 Dockerfile

- 构建镜像的"配方"，每一条指令生成一个只读层
- 常见指令：`FROM`、`RUN`、`COPY`、`ADD`、`CMD`、`ENTRYPOINT`、`EXPOSE`、`ENV`、`WORKDIR`

### 2.4 仓库（Registry）

- 存储和分发镜像的服务，默认是 Docker Hub
- 可以自建私有仓库（如 Harbor、Nexus）

---

## 三、Docker 常用命令

### 3.1 镜像相关

```bash
# 搜索镜像
docker search nginx

# 拉取镜像
docker pull nginx:latest
docker pull openjdk:8-jre-alpine

# 列出本地镜像
docker images

# 删除镜像
docker rmi nginx:latest

# 构建镜像（-t 指定名称和标签）
docker build -t my-app:1.0 .
```

### 3.2 容器相关

```bash
# 创建并运行容器
# -d: 后台运行  -p: 端口映射  --name: 容器名
docker run -d -p 8080:8080 --name my-app my-app:1.0

# 列出运行中的容器
docker ps

# 列出所有容器（包含已停止的）
docker ps -a

# 停止容器
docker stop my-app

# 启动已停止的容器
docker start my-app

# 重启容器
docker restart my-app

# 进入容器内部
docker exec -it my-app /bin/bash

# 查看容器日志
docker logs -f my-app

# 查看容器资源使用
docker stats

# 删除容器
docker rm my-app
# 强制删除运行中的容器
docker rm -f my-app
```

### 3.3 清理相关

```bash
# 清理所有停止的容器
docker container prune

# 清理未被使用的镜像
docker image prune

# 清理所有悬空资源（容器、镜像、网络）
docker system prune
```

---

## 四、实战：Dockerize 一个 Spring Boot 应用

### 4.1 准备应用

假设我们有一个最简单的 Spring Boot 应用 `demo.jar`，提供一个 REST 接口：

> 你也可以用任何 Java Web 应用替代，核心流程一致。

### 4.2 编写 Dockerfile

```
# Stage 1: Build
FROM maven:3.6-jdk-8 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Run
FROM openjdk:8-jre-alpine

LABEL maintainer="developer@example.com"

WORKDIR /app

COPY --from=builder /app/target/demo.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

**不使用多阶段构建**的简化版本（已有 jar 包时）：

```
FROM openjdk:8-jre-alpine
WORKDIR /app
COPY target/demo.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 4.3 构建镜像

```bash
# 在项目根目录执行（包含 Dockerfile 的目录）
docker build -t demo-app:1.0 .
```

输出示例：
```
Sending build context to Docker daemon  16.5MB
Step 1/5 : FROM openjdk:8-jre-alpine
 ---> a1e8f06a5b3e
Step 2/5 : WORKDIR /app
 ---> Running in 8b9d0c3f5e1a
Removing intermediate container 8b9d0c3f5e1a
 ---> f3c4d5e6f7a8
Step 3/5 : COPY target/demo.jar app.jar
 ---> 9b0c1d2e3f4a
Step 4/5 : EXPOSE 8080
 ---> Running in 1a2b3c4d5e6f
Removing intermediate container 1a2b3c4d5e6f
 ---> 2a3b4c5d6e7f
Step 5/5 : ENTRYPOINT ["java", "-jar", "app.jar"]
 ---> Running in 3a4b5c6d7e8f
Removing intermediate container 3a4b5c6d7e8f
 ---> 4a5b6c7d8e9f
Successfully built 4a5b6c7d8e9f
Successfully tagged demo-app:1.0
```

### 4.4 运行容器

```bash
# 后台运行，映射端口 8080 → 8080
docker run -d -p 8080:8080 --name demo-container demo-app:1.0

# 验证是否运行
docker ps
# 输出示例：
# CONTAINER ID   IMAGE          COMMAND                  PORTS                    STATUS
# a1b2c3d4e5f6   demo-app:1.0   "java -jar app.jar"      0.0.0.0:8080->8080/tcp   Up 5 seconds

# 测试 API
curl http://localhost:8080/hello

# 查看日志
docker logs -f demo-container
```

### 4.5 镜像体积优化技巧

精简前的镜像体积往往在 200MB+，通过以下方式可以大幅缩减：

```
# 1. Use Alpine base image (80% smaller than ubuntu)
FROM openjdk:8-jre-alpine       # ~80MB
# Compare FROM openjdk:8-jre    # ~200MB+

# 2. Multi-stage build
# Build stage uses full JDK, run stage only needs JRE

# 3. Clean APK cache when using Alpine
RUN apk add --no-cache curl     # --no-cache removes cache files
```

最终镜像体积对比：

| 基础镜像 | 体积 | 说明 |
|----------|------|------|
| `openjdk:8-jre` | ~200MB | 完整 Ubuntu + JRE |
| `openjdk:8-jre-slim` | ~120MB | 精简版 Debian + JRE |
| `openjdk:8-jre-alpine` | ~80MB | Alpine Linux + JRE（推荐） |

### 4.6 推送镜像到仓库

```bash
# 登录 Docker Hub（默认 Registry）
docker login

# 给镜像打标签（用户名/镜像名:标签）
docker tag demo-app:1.0 your-username/demo-app:1.0

# 推送
docker push your-username/demo-app:1.0

# 在其他机器上拉取并运行
docker pull your-username/demo-app:1.0
docker run -d -p 8080:8080 your-username/demo-app:1.0
```

---

## 五、Dockerfile 最佳实践

```
# 1. Use official base image, prefer Alpine
FROM openjdk:8-jre-alpine

# 2. Set working directory
WORKDIR /app

# 3. Leverage build cache: put stable instructions first
COPY pom.xml ./
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# 4. Use .dockerignore to exclude unnecessary files
# .dockerignore:
#   .git
#   node_modules
#   target/*.jar
#   *.md

# 5. Combine RUN instructions to reduce layers
RUN apk add --no-cache curl && \
    rm -rf /var/cache/apk/*

# 6. Use JSON form of ENTRYPOINT (handles signals correctly)
ENTRYPOINT ["java", "-jar", "app.jar"]

# 7. Use HEALTHCHECK so Docker knows container health
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
```

对应的 `.dockerignore`：

```
.git
.gitignore
*.md
target/
!.mvn
```

---

## 总结

本文介绍了 Docker 的核心概念和实战操作，要点回顾：

| 概念 | 一句话 |
|------|--------|
| **镜像** | 只读模板，应用 + 依赖打包成多层文件系统 |
| **容器** | 镜像的运行实例，进程级隔离，秒级启动 |
| **Dockerfile** | 构建镜像的配方，每条指令生成一层 |
| **仓库** | 存储和分发镜像，默认 Docker Hub |

**三条核心命令**：
```bash
docker build -t my-app .    # 构建
docker run -d -p 8080:8080 my-app  # 运行
docker push my-app          # 推送
```

下一篇我们将介绍 **Docker Compose**，学习如何通过一个 `docker-compose.yml` 文件编排多个容器（应用 + 数据库 + 缓存等），敬请期待。
