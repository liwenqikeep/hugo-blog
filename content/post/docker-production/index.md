---
title: "Docker（四）：生产环境部署最佳实践"
date: 2019-05-31
draft: false
categories: ["容器化"]
tags: ["Docker", "生产部署", "安全", "监控", "Swarm", "CI/CD"]
toc: true
---

## 前言

前三篇文章分别介绍了 Docker 入门、Compose 编排、网络与存储。但开发环境跑得再好，上了生产也可能翻车——未设置资源限制导致宿主机 OOM、日志撑爆磁盘、容器以 root 运行留下安全隐患……

本文总结生产环境部署 Docker 的核心最佳实践，涵盖**资源管理**、**安全加固**、**日志监控**、**CI/CD 集成**和**集群管理**。

<!--more-->

## 一、资源限制

没有资源限制的容器就像没有刹车的车——一个出问题的容器可能拖垮整个宿主机。

### 1.1 内存限制

```bash
# 限制最大使用 512MB 内存
docker run -d --memory=512m nginx

# 限制内存 + 禁止使用 Swap
docker run -d --memory=512m --memory-swap=512m nginx

# 预留 256MB（系统保证至少可用）
docker run -d --memory=512m --memory-reservation=256m nginx

# OOM 优先级（值越低越容易被杀死，默认 0）
docker run -d --memory=512m --oom-score-adj=-500 nginx
```

### 1.2 CPU 限制

```bash
# 限制使用 1.5 个 CPU 核心
docker run -d --cpus=1.5 nginx

# 绑定到特定 CPU（0 和 2 号核心）
docker run -d --cpuset-cpus="0,2" nginx

# CPU 份额（相对权重，默认 1024）
docker run -d --cpu-shares=512 nginx    # 只分配一半的 CPU 时间
```

### 1.3 磁盘 I/O 限制

```bash
# 限制读写速度（B/s）
docker run -d --device-read-bps=/dev/sda:50mb --device-write-bps=/dev/sda:50mb nginx

# 限制 IOPS
docker run -d --device-read-iops=/dev/sda:1000 --device-write-iops=/dev/sda:1000 nginx
```

### 1.4 Compose 中的资源限制

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
        reservations:
          cpus: '0.5'
          memory: 256M
```

---

## 二、重启策略与健康检查

### 2.1 重启策略

```bash
# 不重启（默认）
docker run -d --restart=no nginx

# 容器退出码非 0 时重启
docker run -d --restart=on-failure nginx

# 最多重启 5 次
docker run -d --restart=on-failure:5 nginx

# 总是重启，除非手动停止
docker run -d --restart=always nginx

# 除非手动停止，且退出后总是重启（推荐生产用）
docker run -d --restart=unless-stopped nginx
```

**生产推荐**：大多数服务使用 `unless-stopped`，批处理任务使用 `on-failure`。

### 2.2 健康检查

为容器定义健康检查，让 Docker 知道你的应用是否真正可用（而非仅进程存活）：

```
FROM nginx:alpine
HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=5s \
  CMD curl -f http://localhost/ || exit 1
```

或在运行时添加：

```bash
docker run -d \
  --health-cmd="curl -f http://localhost:8080/actuator/health || exit 1" \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  --health-start-period=30s \
  app:latest
```

查看健康状态：

```bash
docker ps
# CONTAINER ID   STATUS                      PORTS
# a1b2c3d4e5f6   Up 2 minutes (healthy)      8080/tcp
# f6e5d4c3b2a1   Up 5 minutes (unhealthy)    8080/tcp

# 查看具体检查记录
docker inspect --format='{{json .State.Health}}' container-name
```

---

## 三、安全最佳实践

### 3.1 不要以 root 运行

这是最常见的生产安全问题。容器默认以 root 运行，一旦被突破，攻击者直接获得宿主机 root 权限。

```
# ❌ Unsafe: runs as root
FROM node:12
COPY . /app
CMD ["node", "app.js"]

# ✅ Safe: create dedicated user
FROM node:12
RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app
COPY . .
RUN chown -R appuser:appuser /app
USER appuser
CMD ["node", "app.js"]
```

验证容器内的用户：

```bash
docker run --rm alpine whoami
# root  ← 默认就是 root

docker run --rm -u 1000:1000 alpine whoami
# whoami: cannot find name for user ID 1000
# 但实际上以非 root 用户运行
```

### 3.2 只读根文件系统

```bash
docker run -d \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64M \    # 需要写入的目录显式挂载 tmpfs
  --tmpfs /var/run:rw,noexec,nosuid,size=64M \
  nginx:alpine
```

### 3.3 内核能力限制

```bash
# 丢弃所有能力，再按需添加
docker run -d \
  --cap-drop=ALL \
  --cap-add=NET_BIND_SERVICE \    # 允许绑定低端口（<1024）
  nginx:alpine

# 常见需要保留的能力
# NET_BIND_SERVICE：绑定低端口
# CHOWN：修改文件所有者
# SETGID/SETUID：切换用户
# SYS_PTRACE：调试（生产不要加）
```

### 3.4 其他安全措施

```bash
# 禁止容器获取新的能力
docker run -d --security-opt=no-new-privileges nginx

# 限制系统调用
docker run -d --security-opt seccomp=/path/to/seccomp-profile.json nginx

# 限制对宿主机内核的访问
docker run -d --privileged=false nginx    # 默认就是 false
```

### 3.5 安全清单

- [ ] 使用非 root 用户运行
- [ ] 设置 `--read-only` 文件系统
- [ ] `--cap-drop=ALL` + 按需添加能力
- [ ] `--security-opt=no-new-privileges`
- [ ] 镜像来源可靠（使用官方镜像或自建）
- [ ] 定期扫描镜像漏洞（如 Trivy、Clair）
- [ ] 敏感信息使用 Docker Secrets 而非环境变量

---

## 四、日志管理

容器日志默认写入 stdout/stderr，由 Docker 的 logging driver 处理。

### 4.1 配置日志驱动

```bash
# 全局配置（/etc/docker/daemon.json）
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}

# 或对单个容器设置
docker run -d \
  --log-driver=json-file \
  --log-opt max-size=10m \
  --log-opt max-file=3 \
  nginx
```

### 4.2 常用日志驱动

| 驱动 | 说明 | 适用场景 |
|------|------|----------|
| `json-file` | 默认，写本地文件 | 单机开发/测试 |
| `syslog` | 发送到 Syslog | 已有 Syslog 基础设施 |
| `gelf` | Graylog Extended Log Format | ELK / Graylog 集中日志 |
| `fluentd` | 发送到 Fluentd | 统一日志采集管道 |
| `awslogs` | 发送到 CloudWatch | AWS 环境 |

### 4.3 集中日志架构（ELK）

```
                   ┌──────────┐
                   │  Logstash │
                   │  (采集)    │
                   └────┬─────┘
                        │
                   ┌────▼─────┐     ┌──────────┐
  Docker ──▶ Fluentd ───▶  Elasticsearch ──▶  Kibana
  容器日志    (转发)       (存储)       (可视化)
```

Docker Compose 配置：

```yaml
version: '3.8'
services:
  app:
    build: .
    logging:
      driver: "fluentd"
      options:
        fluentd-address: "localhost:24224"
        tag: "docker.app.{{.Name}}"

  fluentd:
    image: fluent/fluentd:v1.11
    ports:
      - "24224:24224"
    volumes:
      - ./fluentd.conf:/fluentd/etc/fluentd.conf
```

---

## 五、Docker Swarm 集群管理

如果服务器数量超过一台，单机 Docker 就不够用了。Docker Swarm 是 Docker 原生的集群管理方案，优势是**与 Docker API 完全兼容**，学习成本低。

### 5.1 初始化集群

```bash
# 在 Manager 节点执行
docker swarm init --advertise-addr 192.168.1.10

# 输出加入 Worker 节点的命令
# docker swarm join --token SWMTKN-1-xxxxx 192.168.1.10:2377

# 查看集群节点
docker node ls

# 在 Worker 节点加入
docker swarm join --token SWMTKN-1-xxxxx 192.168.1.10:2377
```

### 5.2 部署服务

```bash
# 创建服务（类似 docker run，但跨多台机器）
docker service create \
  --name web \
  --replicas 3 \
  --publish 80:80 \
  --network overlay-net \
  --limit-memory 512M \
  nginx:alpine

# 查看服务
docker service ls

# 查看服务实例分布
docker service ps web

# 滚动更新
docker service update \
  --image nginx:1.19 \
  --update-parallelism 2 \
  --update-delay 10s \
  web

# 回滚
docker service rollback web
```

### 5.3 Swarm 与 Compose

使用 `docker stack deploy` 将 Compose 文件部署到 Swarm 集群：

```yaml
# docker-stack.yml
version: '3.8'
services:
  app:
    image: app:latest
    deploy:
      replicas: 3
      resources:
        limits:
          memory: 512M
      update_config:
        parallelism: 1
        delay: 10s
      restart_policy:
        condition: on-failure

  mysql:
    image: mysql:8.0
    volumes:
      - mysql-data:/var/lib/mysql
    deploy:
      placement:
        constraints: [node.role == manager]    # 有状态服务固定在 Manager 节点

volumes:
  mysql-data:
    driver: local
```

```bash
# 部署到 Swarm
docker stack deploy -c docker-stack.yml prod-stack

# 查看服务
docker stack services prod-stack

# 移除
docker stack rm prod-stack
```

---

## 六、CI/CD 集成

### 6.1 GitHub Actions 示例

```yaml
name: Build and Deploy

on:
  push:
    branches: [main]

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Build Docker image
        run: docker build -t my-app:${{ github.sha }} .

      - name: Push to registry
        run: |
          docker tag my-app:${{ github.sha }} registry.example.com/my-app:latest
          docker push registry.example.com/my-app:latest

      - name: Deploy to server
        uses: appleboy/ssh-action@master
        with:
          host: ${{ secrets.HOST }}
          script: |
            docker pull registry.example.com/my-app:latest
            docker stack deploy -c docker-compose.prod.yml my-app
```

### 6.2 镜像标签策略

```bash
# ❌ 不推荐：latest 标签，无法追溯
registry.example.com/my-app:latest

# ✅ 推荐：版本号 + Git Commit
registry.example.com/my-app:1.2.3
registry.example.com/my-app:1.2.3-a1b2c3d
```

---

## 七、生产 Checklist

部署前逐项确认：

| 类别 | 检查项 |
|------|--------|
| **资源** | 是否设置了 CPU/内存限制？是否配置了 OOM 策略？ |
| **安全** | 是否以非 root 用户运行？是否 drop 了不必要的 cap？是否是只读文件系统？ |
| **数据** | 关键数据是否使用命名卷？数据卷是否有定期备份？ |
| **日志** | 是否限制了日志文件大小和数量？是否接入集中日志系统？ |
| **监控** | 是否配置了健康检查？是否接入了容器监控（cAdvisor / Prometheus）？ |
| **网络** | 是否使用自定义网络？数据库是否暴露到了公网？ |
| **更新** | 是否有滚动更新策略？是否有回滚方案？ |
| **镜像** | 镜像是否经过漏洞扫描？是否使用 Alpine 等精简基础镜像？ |

---

## 总结

| 领域 | 核心原则 |
|------|----------|
| **资源** | 永远设置 CPU/内存上限，防止单个容器拖垮宿主机 |
| **安全** | 非 root 运行 + drop 所有 cap + 只读文件系统 |
| **日志** | 限制日志文件大小，接入集中日志系统 |
| **集群** | 单机用 Compose，多机用 Swarm/K8s |
| **CI/CD** | 构建不可变镜像，使用版本标签，自动化部署 |

至此，Docker 系列四篇文章全部完成：

1. [容器化入门]({{< relref "post/docker-introduction" >}}) —— 概念 + 命令 + Dockerfile
2. [Compose 多容器编排]({{< relref "post/docker-compose" >}}) —— 编排实战
3. [网络与存储]({{< relref "post/docker-network-storage" >}}) —— 网络模式 + 数据持久化
4. **生产部署最佳实践**（本文）—— 安全 + 监控 + 集群 + CI/CD
