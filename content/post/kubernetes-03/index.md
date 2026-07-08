---
title: "K8s（三）：Pod 核心概念与容器编排基础"
date: 2022-04-05
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Pod", "容器"]
toc: true
---

## 前言：Pod 为何是"最小调度单元"

在第一篇文章中我们提到，Pod 是 Kubernetes 中**最小的调度和运行单元**。但你可能会有疑问——为什么 K8s 不直接调度容器，而是要在容器外面再包一层 Pod？

这要从容器的本质说起。Docker 等容器技术采用的是"单进程模型"，一个容器通常只运行一个进程。但在很多场景下，多个进程需要紧密协作：

- 一个 Web 服务需要搭配一个日志收集 sidecar
- 一个应用容器需要代理容器来处理网络流量

如果 K8s 直接调度独立的容器，这些"需要在一起"的容器可能会被调度到不同的节点上，导致网络不通、无法共享数据。**Pod 就是为解决这个问题而生的**——它保证一组容器永远运行在同一台机器上，共享网络和存储资源。

<!--more-->

## Pod 设计哲学

### 单容器 Pod vs 多容器 Pod

**单容器 Pod** 是最常见的模式，一个 Pod 里只运行一个业务容器。

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: nginx-pod
spec:
  containers:
  - name: nginx
    image: nginx:1.25
```

**多容器 Pod** 用于"主容器 + 辅助容器"的场景。辅助容器通常被称为 **Sidecar**（边车模式），它们辅助主容器工作，比如日志采集、流量代理、配置同步等。

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: web-app
spec:
  containers:
  - name: app
    image: my-web-app:latest
    ports:
    - containerPort: 8080
  - name: log-collector
    image: fluentd:latest
    volumeMounts:
    - name: log-volume
      mountPath: /var/log/app
```

### 共享网络和存储

Pod 内的所有容器共享以下资源：

- **网络**：同一个 Pod IP 和端口空间，容器之间通过 `localhost` 通信。这也是为什么一个 Pod 内的容器端口不能冲突。
- **存储**：通过 Volume 挂载，多个容器可以读写同一份数据。
- **生命周期**：Pod 是整个生命周期管理的最小单位，所有容器一起启动、一起停止。

## YAML 编写基础

在 K8s 中，所有资源都是通过 YAML 文件定义的。一个完整的 YAML 包含以下几个核心字段：

| 字段 | 含义 | 示例 |
|------|------|------|
| `apiVersion` | API 版本 | `v1`（核心 API）、`apps/v1`（Deployment） |
| `kind` | 资源类型 | `Pod`、`Deployment`、`Service` |
| `metadata` | 元数据 | `name`、`labels`、`namespace` |
| `spec` | 期望状态 | 容器的镜像、端口、资源限制等 |

```yaml
# 一个完整的 Pod YAML 示例
apiVersion: v1
kind: Pod
metadata:
  name: my-pod
  labels:
    app: my-app
    env: dev
spec:
  containers:
  - name: my-container
    image: nginx:1.25
    ports:
    - containerPort: 80
```

## 创建与管理 Pod

### 使用 YAML 创建

```bash
# 使用 YAML 文件创建 Pod
kubectl apply -f pod.yaml

# 或者直接使用 nginx 镜像创建一个 Pod
kubectl run nginx --image=nginx:1.25
```

### 查看 Pod

```bash
# 查看所有 Pod
kubectl get pods

# 查看更详细的信息（IP、节点等）
kubectl get pods -o wide

# 持续监听变化
kubectl get pods -w
```

### 查看详情

```bash
# 查看 Pod 的完整信息
kubectl describe pod nginx

# 查看日志
kubectl logs nginx

# 进入 Pod 内部
kubectl exec -it nginx -- /bin/bash
```

### 删除 Pod

```bash
# 删除 Pod
kubectl delete pod nginx

# 通过 YAML 删除
kubectl delete -f pod.yaml
```

> **注意**：直接创建的 Pod 在节点故障或异常删除后**不会自动恢复**。实际生产环境中，应该使用 Deployment 等控制器来管理 Pod。这将在下一篇文章中详细介绍。

## Init 容器

Init 容器是在 Pod 的主容器启动之前运行的专用容器，用于完成初始化任务。与主容器不同，Init 容器**按顺序依次执行**，每个 Init 容器成功完成后才会启动下一个。

使用场景：

- 等待外部服务就绪（如数据库）
- 初始化配置文件或数据库结构
- 执行数据库迁移脚本

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: init-demo
spec:
  initContainers:
  - name: init-db
    image: busybox:1.28
    command: ['sh', '-c', 'until nc -z db-service 5432; do echo waiting for db; sleep 2; done;']
  - name: init-migrations
    image: my-migration-tool:latest
    command: ['npm', 'run', 'migrate']
  containers:
  - name: app
    image: my-app:latest
```

上面的示例中，Pod 会先启动 `init-db` 容器等待数据库就绪，再启动 `init-migrations` 执行迁移，最后才启动主容器 `app`。

## 健康检查

K8s 提供了三种探针来检测容器的健康状态：

| 探针 | 作用 | 失败后果 |
|------|------|----------|
| **livenessProbe**（存活探针） | 判断容器是否存活，如果失败则重启容器 | 重启容器 |
| **readinessProbe**（就绪探针） | 判断容器是否准备好接收流量，如果失败则从 Service 中移除 | 不接收流量 |
| **startupProbe**（启动探针） | 判断容器是否已成功启动，用于慢启动容器 | 重启容器 |

每种探针都支持三种检测方式：

### HTTP 请求检查

```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
```

### TCP 连接检查

```yaml
readinessProbe:
  tcpSocket:
    port: 3306
  initialDelaySeconds: 5
  periodSeconds: 10
```

### 命令执行检查

```yaml
livenessProbe:
  exec:
    command:
    - cat
    - /tmp/healthy
  initialDelaySeconds: 5
  periodSeconds: 10
```

**最佳实践**：对每个生产环境的容器至少配置 `readinessProbe`，确保流量只发给健康的 Pod。对于有自愈能力的应用，再配置 `livenessProbe`。

## 资源限制

为了让集群资源得到合理分配，K8s 允许你为每个容器指定资源请求和限制：

- **requests**（请求）：调度时保证的最小资源量，决定了 Pod 被调度到哪个节点。
- **limits**（限制）：容器最多能使用的资源上限，超出会被限制或杀死。

```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "500m"      # 500 millicores = 0.5 核
  limits:
    memory: "512Mi"
    cpu: "1000m"     # 1 核
```

> **单位说明**：
> - CPU：`1` = 1 核，`100m` = 0.1 核，`500m` = 0.5 核
> - 内存：`256Mi` = 256 Mebibytes，`1Gi` = 1 Gibibyte

如果一个 Pod 没有设置 requests，它可能会被调度到资源紧张的节点上，影响其他应用的运行。因此，**为每个容器设置合理的资源限制是一个好习惯**。

## 总结

本文深入介绍了 Kubernetes 中最核心的概念——Pod：

| 知识点 | 要点 |
|--------|------|
| **Pod 设计哲学** | 最小调度单元，支持单容器和多容器（Sidecar 模式），共享网络和存储 |
| **YAML 语法** | `apiVersion`、`kind`、`metadata`、`spec` 四个核心字段 |
| **Pod 生命周期** | `kubectl apply` → `get` → `describe` → `logs` → `exec` → `delete` |
| **Init 容器** | 在主容器之前按顺序执行初始化任务 |
| **健康检查** | livenessProbe（存活）、readinessProbe（就绪）、startupProbe（慢启动） |
| **资源限制** | requests（调度保证）和 limits（使用上限） |

掌握了 Pod，你就掌握了 Kubernetes 最基本的部署单元。但直接使用 Pod 管理应用存在一个关键问题——Pod 挂了不会自动恢复。下一篇文章将介绍 **Deployment**，它是 K8s 中管理 Pod 最常用的控制器，可以实现自动扩缩容、滚动更新和回滚。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
