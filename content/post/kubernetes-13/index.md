---
title: "K8s（十三）：生产实践与系列总结"
date: 2022-04-25
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "生产实践", "最佳实践"]
toc: true
---

终于到了系列最后一篇。在过去的十二篇文章中，我们走过了从"K8s 是什么"到"如何部署一个有状态应用"的完整旅程。但纸上得来终觉浅——把应用真正跑在生产环境，还有一系列"必修课"：资源怎么隔离？Pod 怎么优雅关闭？如何让 Pod 调度到合适的节点？出了问题怎么排障？

本文将这些生产实践融会贯通，同时也作为整个系列的收官回顾。

<!--more-->

## 回顾：整个系列的知识地图

在进入新内容之前，先回顾一下我们走过的路：

| 篇目 | 核心内容 | 一句话总结 |
|------|---------|-----------|
| (一) 架构与核心概念 | K8s 是什么、架构组件 | 搭建认知框架 |
| (二) kubeadm 搭建集群 | 集群安装、节点管理 | 动手搭建环境 |
| (三) Pod 与容器编排 | Pod、容器生命周期 | 最小调度单元 |
| (四) Deployment 与 ReplicaSet | 无状态应用部署、扩缩容 | 让应用跑起来 |
| (五) Service | 集群内服务发现 | 稳定的网络入口 |
| (六) Ingress | 外部流量接入 | 对外暴露服务 |
| (七) ConfigMap 与 Secret | 配置管理 | 配置与镜像分离 |
| (八) Volume 与持久化存储 | 存储卷、PV/PVC | 持久化数据 |
| (九) StatefulSet | 有状态应用 | 数据库类应用 |
| (十) Helm | 包管理器 | 模板化部署 |
| (十一) RBAC 与安全 | 认证授权 | 权限管控 |
| (十二) 监控与日志 | 可观测性 | 了解集群状态 |

最后这篇文章，我们来补充那些"生产环境缺一不可"的知识点。

---

## 命名空间隔离与资源配额

### 多租户隔离

生产环境一般按环境或团队划分 Namespace：

```bash
kubectl create namespace production
kubectl create namespace staging
kubectl create namespace development
```

每个 Namespace 就是独立的"虚拟集群"，资源名称可以在不同 Namespace 中重复，互不干扰。

### ResourceQuota（资源配额）

ResourceQuota 限制一个 Namespace 能使用的总资源上限，防止一个团队"吃光"整个集群：

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: dev-quota
  namespace: development
spec:
  hard:
    requests.cpu: "4"         # 所有 Pod CPU 请求总和不超过 4 核
    requests.memory: "8Gi"    # 所有 Pod 内存请求总和不超过 8Gi
    limits.cpu: "8"           # 所有 Pod CPU 限制总和不超过 8 核
    limits.memory: "16Gi"     # 所有 Pod 内存限制总和不超过 16Gi
    persistentvolumeclaims: "5"  # PVC 数量不超过 5 个
    pods: "20"                # Pod 数量不超过 20 个
```

### LimitRange（默认资源限制）

LimitRange 为单个 Pod 或容器设置默认的资源请求/限制，并约束资源上下限：

```yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: dev-limits
  namespace: development
spec:
  limits:
  - default:                    # 默认 limit
      cpu: "500m"
      memory: "512Mi"
    defaultRequest:             # 默认 request
      cpu: "200m"
      memory: "256Mi"
    max:                        # 单个容器上限
      cpu: "2"
      memory: "2Gi"
    min:                        # 单个容器下限
      cpu: "50m"
      memory: "64Mi"
    type: Container
```

**最佳实践**：每个团队 Namespace 都配置 ResourceQuota 和 LimitRange，避免资源争抢。

---

## Pod 优雅终止

当 Pod 被删除或滚动更新时，K8s 会给容器发送 `SIGTERM` 信号，等待一段时间（默认 30 秒）后如果还没结束，就强制 `SIGKILL`。

但如果你的应用正在处理重要的请求，30 秒可能不够。你需要**优雅终止**。

### preStop 钩子

`preStop` 在容器收到 `SIGTERM` 之前执行，可以用来通知负载均衡器摘除节点、等待正在处理的请求完成：

```yaml
spec:
  containers:
  - name: my-app
    image: my-app:latest
    lifecycle:
      preStop:
        exec:
          command:
          - /bin/sh
          - -c
          - |
            # 通知注册中心摘除节点
            curl -X POST http://localhost:8080/shutdown
            # 等待正在处理的请求完成
            sleep 10
```

### terminationGracePeriodSeconds

调整优雅终止的等待时间：

```yaml
spec:
  terminationGracePeriodSeconds: 60   # 给 60 秒完成收尾工作
```

**完整工作流**：

1. Pod 状态变为 `Terminating`
2. 执行 `preStop` 钩子
3. 向主容器进程发送 `SIGTERM`
4. 等待 `terminationGracePeriodSeconds` 秒
5. 若仍未退出，发送 `SIGKILL` 强制终止

---

## 节点污点（Taints）与 Pod 容忍（Tolerations）

### 为什么需要污点？

默认情况下，任何 Pod 都可以调度到任何节点。但生产环境中你可能希望：

- 专用节点运行 GPU 任务
- 专用节点运行系统组件
- 不让普通 Pod 调度到坏掉的节点

**污点（Taints）** 就是节点上的"排斥标记"，只有**容忍（Tolerations）** 了该污点的 Pod 才能被调度到该节点。

### 添加和查看污点

```bash
# 给节点添加污点：key=value:Effect
kubectl taint nodes node1 gpu=true:NoSchedule

# 查看节点污点
kubectl describe node node1 | grep Taints

# 移除污点
kubectl taint nodes node1 gpu=true:NoSchedule-
```

### 三种污点效果（Effect）

| Effect | 行为 |
|--------|------|
| `NoSchedule` | 不容忍该污点的 Pod 不会被调度到此节点 |
| `PreferNoSchedule` | 尽量不调度，但无法避免时仍可调度 |
| `NoExecute` | 不容忍的 Pod 不仅不会调度，已有的也会被驱逐 |

### 在 Pod 中添加容忍

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: gpu-pod
spec:
  tolerations:
  - key: "gpu"
    operator: "Equal"
    value: "true"
    effect: "NoSchedule"
  containers:
  - name: cuda-container
    image: nvidia/cuda:11.0
```

### 常见的预置污点

K8s 在节点出现问题时会自动打上污点：

- `node.kubernetes.io/unreachable`：节点不可达
- `node.kubernetes.io/out-of-disk`：磁盘空间不足
- `node.kubernetes.io/memory-pressure`：内存压力
- `node.kubernetes.io/disk-pressure`：磁盘压力

大多数 Pod 默认容忍短时间的节点不可达（`tolerationSeconds: 300`），超过 5 分钟会被驱逐。

---

## 亲和性调度

污点和容忍是"排斥"逻辑，而**亲和性（Affinity）** 是"吸引"逻辑——你希望 Pod 优先调度到某些节点，或者与某些 Pod 靠近/远离。

### nodeAffinity（节点亲和性）

将 Pod 调度到具有特定标签的节点：

```yaml
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: disk-type
            operator: In
            values:
            - ssd
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        preference:
          matchExpressions:
          - key: zone
            operator: In
            values:
            - az-a
```

- `requiredDuringSchedulingIgnoredDuringExecution`：硬需求，必须满足
- `preferredDuringSchedulingIgnoredDuringExecution`：软偏好，尽量满足

### podAffinity 与 podAntiAffinity

控制 Pod 之间的调度关系：

```yaml
spec:
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
            - key: app
              operator: In
              values:
              - my-app
          topologyKey: "kubernetes.io/hostname"
```

**典型场景**：

| 策略 | 用途 | 示例 |
|------|------|------|
| `podAntiAffinity` | 分散部署 | 同一应用的多个副本分布在不同节点，提高可用性 |
| `podAffinity` | 就近部署 | 缓存和 Web 应用部署到同一节点，减少网络延迟 |

---

## 常用排障手段

生产环境中，问题迟早会发生。下面这些命令是你的"急救箱"：

### 1. Pod 层面

```bash
# 查看 Pod 状态和事件
kubectl describe pod <pod-name>

# 查看实时日志
kubectl logs -f <pod-name>

# 查看崩溃前日志
kubectl logs --previous <pod-name>

# 进入容器交互式排查
kubectl exec -it <pod-name> -- /bin/sh

# 端口转发（本地访问 Pod 端口）
kubectl port-forward pod/<pod-name> 8080:80
```

### 2. 节点层面

```bash
# 查看节点状态和资源
kubectl describe node <node-name>

# 查看节点资源使用
kubectl top node

# 查看节点条件（Ready、DiskPressure 等）
kubectl get node <node-name> -o json | jq '.status.conditions'

# SSH 登录节点（如果可达）
ssh <node-ip>
# 查看 kubelet 日志
journalctl -u kubelet -n 100 --no-pager
```

### 3. 集群层面

```bash
# 查看集群事件
kubectl get events --sort-by='.lastTimestamp'

# 查看所有 Namespace 的资源概览
kubectl get all --all-namespaces

# API Server 健康检查
kubectl get --raw=/healthz

# 查看组件状态
kubectl get componentstatuses
```

### 4. 常用排障口诀

> "先看 Events，再看 Logs，describe 是万能钥匙"

遇到问题按此顺序排查：

1. `kubectl get pods` — 确认状态
2. `kubectl describe pod <name>` — 查看事件和条件
3. `kubectl logs <name> [--previous]` — 查看应用日志
4. `kubectl exec -it <name> -- sh` — 进入容器进一步排查

---

## 学习路线推荐

K8s 的学习是"实践驱动"的，以下是进阶学习路线：

### 运维方向

1. **集群管理**：多集群管理（Karmada）、集群升级、备份恢复（Velero）
2. **网络方案**：Calico/Cilium 网络策略、Service Mesh（Istio）
3. **容器运行时**：Containerd 原理、安全容器（Kata Containers）
4. **GitOps**：ArgoCD / Flux — 声明式应用交付
5. **云原生存储**：Rook/Ceph、Longhorn
6. **服务网格**：Istio 流量管理、可观测性

### 开发方向

1. **Operator 模式**：用 Operator SDK 编写 K8s 扩展
2. **自定义资源（CRD）**：扩展 K8s API
3. **调度器扩展**：自定义调度策略
4. **K8s 开发框架**：controller-runtime、client-go
5. **云原生应用设计**：12-Factor App、无服务器（Knative）

### 认证推荐

- **CKA（Certified Kubernetes Administrator）**：运维方向的行业标准认证
- **CKAD（Certified Kubernetes Application Developer）**：开发方向认证
- **CKS（Certified Kubernetes Security Specialist）**：安全方向高级认证

---

## 系列总结与寄语

历时十三篇文章，我们从零到一梳理了 Kubernetes 的核心知识体系。

还记得系列第一篇开头的追问吗？**"当你在一个拥有几十台服务器的公司做运维时，你是怎么发布一个 Java 项目的？"** 到了今天，你应该已经有了答案——你不再 SSH 登录到某一台服务器上手动部署，不再担心某台机器宕机导致服务不可用，也不再为了扩容而彻夜加班。你学会了声明式地描述"最终状态"，让 K8s 自己搞定一切。

Kubernetes 的学习曲线确实陡峭，但一旦上手，你会感受到它带来的"确定性"——部署是确定的、伸缩是确定的、自愈是确定的。这种确定性，正是云原生时代工程师追求的核心体验。

最后，送你三句话：

> **学 K8s，不懂原理是走不远的。** 不要把 YAML 当脚本写，理解每个字段背后的设计意图。
>
> **动手实践是最好的老师。** 把每篇文章的示例亲手敲一遍，比读十遍更有效。
>
> **遇到问题不要慌，Events+Logs+Describe 能解决 90% 的问题。**

感谢你一路读到这里。如果这个系列对你有哪怕一点点帮助，它就完成了自己的使命。未来云原生的世界，期待你的参与！🚀

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
