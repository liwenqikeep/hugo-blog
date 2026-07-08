---
title: "K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容"
date: 2022-04-07
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Deployment", "ReplicaSet"]
toc: true
---

## 前言：从 Pod 到 Deployment 的演进

在上一篇文章中，我们学习了 Pod——Kubernetes 中最小的调度单元。但直接使用裸 Pod 存在一个致命问题：**Pod 不会自愈**。

如果你用 `kubectl run` 创建了一个 Pod，当这个 Pod 因为节点故障、资源耗尽、程序崩溃等原因挂掉时，K8s 并不会自动重建它。在生产环境中，你不可能半夜爬起来手动 `kubectl apply` 一个 Pod。

这就引出了控制器的概念。**控制器**是 K8s 中负责维持期望状态的管理组件。它们持续监控集群状态，当实际状态偏离期望状态时自动进行修正。

**Deployment** 是 K8s 中最常用的控制器之一，专门用于管理无状态应用的声明式更新。而 Deployment 底层依赖的是 **ReplicaSet**。

<!--more-->

## ReplicaSet：副本管理机制

### 什么是 ReplicaSet

ReplicaSet 的核心职责很简单：**保证指定数量的 Pod 副本始终运行**。如果某个 Pod 挂了，它会创建一个新的来替代；如果 Pod 多于期望数量，它会杀掉多余的。

```yaml
apiVersion: apps/v1
kind: ReplicaSet
metadata:
  name: nginx-rs
  labels:
    app: nginx
spec:
  replicas: 3          # 期望 3 个副本
  selector:
    matchLabels:
      app: nginx
  template:             # Pod 模板
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.25
        ports:
        - containerPort: 80
```

关键字段说明：

- **`replicas`**：期望的 Pod 副本数量
- **`selector.matchLabels`**：标签选择器，ReplicaSet 通过标签来找到它管理的 Pod
- **`template`**：Pod 模板，定义了新创建的 Pod 的规格

### ReplicaSet 的工作方式

ReplicaSet 通过**标签选择器**来管理 Pod。当 ReplicaSet 发现匹配标签的 Pod 数量与 `replicas` 不一致时，就会创建或删除 Pod 来达成期望状态。

> **最佳实践**：不要直接操作 ReplicaSet。Deployment 会自动管理 ReplicaSet，你直接使用 Deployment 即可。

## Deployment：声明式更新

Deployment 在 ReplicaSet 的基础上增加了**声明式更新**和**版本管理**的能力。它是生产环境中部署无状态应用的首选方式。

### 创建 Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
      - name: nginx
        image: nginx:1.25
        ports:
        - containerPort: 80
```

```bash
# 部署应用
kubectl apply -f deployment.yaml

# 查看 Deployment
kubectl get deployments

# 查看 Deployment 管理的 ReplicaSet
kubectl get replicasets

# 查看 Deployment 管理的 Pod
kubectl get pods -l app=nginx
```

### 滚动更新策略

滚动更新是 Deployment 最核心的能力——更新应用版本时，Pod 会**逐个或分批**替换，而不是一次性全部重启，从而实现**零停机**更新。

滚动更新的关键参数：

```yaml
spec:
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1           # 更新时最多允许超出期望数的 Pod 数量
      maxUnavailable: 0     # 更新时最多允许不可用的 Pod 数量
```

| 参数 | 说明 | 默认值 |
|------|------|--------|
| **`maxSurge`** | 滚动更新期间，最多允许超出期望副本数的 Pod 数量。可以是绝对数值或百分比。 | 25% |
| **`maxUnavailable`** | 滚动更新期间，最多允许不可用的 Pod 数量。可以是绝对数值或百分比。 | 25% |

示例场景（`replicas: 3`, `maxSurge: 1`, `maxUnavailable: 0`）：

1. 先创建一个新版本的 Pod（总数变为 4，超出 1 个）
2. 新 Pod 就绪后，删除一个旧版本的 Pod（总数回到 3）
3. 再创建一个新版本的 Pod（总数变为 4）
4. 继续替换，直到所有 Pod 都更新为新版本

由于 `maxUnavailable: 0`，整个过程中始终有 3 个 Pod 在提供服务，保证了**零停机**。

## 扩缩容

### 手动扩缩容

```bash
# 将副本数从 3 扩展到 5
kubectl scale deployment nginx-deployment --replicas=5

# 也可以修改 YAML 中的 replicas 后重新 apply
kubectl apply -f deployment.yaml
```

### 自动扩缩容（HPA）

Kubernetes 支持基于 CPU、内存或自定义指标进行自动扩缩容（Horizontal Pod Autoscaler，HPA）：

```bash
# 创建 HPA，根据 CPU 使用率自动调整副本数（1~10 个副本）
kubectl autoscale deployment nginx-deployment \
  --min=1 --max=10 --cpu-percent=80
```

```yaml
# 或者使用 YAML 创建 HPA
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: nginx-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: nginx-deployment
  minReplicas: 1
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 80
```

## 回滚

Deployment 的每一次变更都会生成一个新的 **Revision**（版本号），方便你随时回滚到任意历史版本。

```bash
# 查看 Deployment 的变更历史
kubectl rollout history deployment nginx-deployment

# 输出类似：
# REVISION  CHANGE-CAUSE
# 1         <none>
# 2         <none>

# 回滚到上一个版本
kubectl rollout undo deployment nginx-deployment

# 回滚到指定版本
kubectl rollout undo deployment nginx-deployment --to-revision=1

# 查看回滚状态
kubectl rollout status deployment nginx-deployment
```

> **注意**：为了让版本历史一目了然，建议在更新 Deployment 时添加 `--record` 参数（或在 YAML 的 `metadata.annotations` 中添加变更说明）。

## 完整示例：部署 Nginx 并升级版本

下面是一个完整的实战演练，从部署 Nginx 1.25 开始，逐步升级到 1.26，然后回滚到 1.25。

### 1. 创建 Deployment

```bash
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-demo
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx-demo
  template:
    metadata:
      labels:
        app: nginx-demo
    spec:
      containers:
      - name: nginx
        image: nginx:1.25
        ports:
        - containerPort: 80
EOF
```

### 2. 验证部署

```bash
kubectl get pods -l app=nginx-demo
# NAME                           READY   STATUS    RESTARTS   AGE
# nginx-demo-7d9f8c6b9f-abc12   1/1     Running   0          30s
# nginx-demo-7d9f8c6b9f-abc34   1/1     Running   0          30s
# nginx-demo-7d9f8c6b9f-abc56   1/1     Running   0          30s
```

### 3. 升级镜像版本

```bash
kubectl set image deployment/nginx-demo nginx=nginx:1.26

# 查看滚动更新状态
kubectl rollout status deployment nginx-demo
# Waiting for rollout to finish: 1 out of 3 new replicas have been updated...
# deployment "nginx-demo" successfully rolled out
```

### 4. 回滚

```bash
# 查看部署历史
kubectl rollout history deployment nginx-demo

# 回滚到版本 1
kubectl rollout undo deployment nginx-demo --to-revision=1

# 确认 Pod 镜像已变回 1.25
kubectl describe pods -l app=nginx-demo | grep Image
```

## 总结

本文介绍了 Kubernetes 应用部署的核心控制器——Deployment 和 ReplicaSet：

| 概念 | 作用 |
|------|------|
| **ReplicaSet** | 保证指定数量的 Pod 副本始终运行，通过标签选择器管理 Pod |
| **Deployment** | 在 ReplicaSet 之上提供声明式更新、滚动更新、版本管理和回滚能力 |
| **滚动更新** | 逐步替换 Pod，实现零停机更新，通过 maxSurge / maxUnavailable 控制速率 |
| **扩缩容** | 手动 `kubectl scale` 或自动 HPA（Horizontal Pod Autoscaler） |
| **回滚** | `kubectl rollout undo` 随时回到历史版本 |

Deployment 适合管理**无状态应用**（如 Web 服务、API 后端），因为它们可以随意替换，不依赖本地数据。那么**有状态应用**（如数据库、消息队列）应该怎么管理呢？这将在后续文章中介绍。

应用的 Pod 已经部署好了，但另一个问题随之而来：Pod 的 IP 是动态变化的，Service A 如何找到 Service B？下一篇文章将介绍 **Service**——Kubernetes 的服务发现与负载均衡机制。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
