---
title: "K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount"
date: 2022-04-21
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "RBAC", "安全", "ServiceAccount"]
toc: true
---

在前面的文章中，我们学会了一堆 kubectl 命令来操作集群：`kubectl get pods`、`kubectl delete deployment`……但仔细想想——是谁在背后控制这些操作权限？任何人都能随意删除 Pod 吗？如果让一个 CI/CD 工具操作集群，它应该拥有多大的权限？这些问题都指向 K8s 安全的核心——**认证（Authentication）** 和 **授权（Authorization）**。

<!--more-->

## 前言：K8s 安全模型概述

K8s 的安全模型分为三个层次：

1. **认证（Authentication）**：你是谁？——验证请求者的身份
2. **授权（Authorization）**：你能做什么？——判断请求者是否有权限执行操作
3. **准入控制（Admission Control）**：资源是否符合规范？——在资源被持久化之前拦截和修改

本文聚焦前两层——认证和授权。K8s 支持多种授权模式，其中最常用、最灵活的就是 **RBAC（基于角色的访问控制）**。

## 认证：kubeconfig、证书与 ServiceAccount

在 K8s 中，所有请求（包括 kubectl、Pod 内应用、外部工具）都需要经过 API Server 的认证。

### kubeconfig

当你用 `kubectl` 操作集群时，默认读取 `~/.kube/config` 文件（由 `kubeadm init` 或云服务商自动生成）。这个文件包含了集群信息、用户凭证和上下文配置：

```yaml
apiVersion: v1
kind: Config
clusters:
- cluster:
    certificate-authority-data: ...   # CA 证书
    server: https://192.168.1.100:6443
  name: my-cluster
users:
- name: kubernetes-admin
  user:
    client-certificate-data: ...      # 客户端证书
    client-key-data: ...              # 客户端私钥
contexts:
- context:
    cluster: my-cluster
    user: kubernetes-admin
  name: admin-context
current-context: admin-context
```

`kubectl` 向 API Server 发起请求时，会出示客户端证书完成双向 TLS 认证。

### ServiceAccount

刚才说的是**人类用户**的认证方式。那**应用**呢？Pod 内的应用也需要访问 API Server——比如 Prometheus 要查询 Pod 指标、CI/CD Agent 要创建 Deployment。Pod 使用 **ServiceAccount** 进行认证。

ServiceAccount 是 K8s 中的一种资源类型，专门为 Pod 提供身份。每个 Namespace 都有一个默认的 ServiceAccount（名为 `default`），但最佳实践是为每个应用创建独立的 ServiceAccount。

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app-sa
  namespace: default
```

在 Pod 中指定 ServiceAccount：

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: my-app
spec:
  serviceAccountName: my-app-sa   # 指定 ServiceAccount
  containers:
  - name: my-app
    image: my-app:latest
```

Pod 启动后，K8s 会将该 ServiceAccount 对应的 Token 自动挂载到 `/var/run/secrets/kubernetes.io/serviceaccount/token`，Pod 内的应用可以用这个 Token 与 API Server 通信。

## 授权：RBAC 模型

认证通过后，API Server 需要判断你**有没有权限**执行某个操作。K8s 支持多种授权模式：RBAC、ABAC、Node、Webhook 等。其中 **RBAC（Role-Based Access Control）** 是生产环境的标准配置。

### RBAC 的四类资源

RBAC 由四个资源组成，两两配对：

| 资源类型 | 作用范围 | 用途 |
|---------|---------|------|
| **Role** | 命名空间级 | 定义一组权限规则 |
| **ClusterRole** | 集群级 | 定义集群范围的权限 |
| **RoleBinding** | 命名空间级 | 将 Role 绑定到用户/组/ServiceAccount |
| **ClusterRoleBinding** | 集群级 | 将 ClusterRole 绑定到用户/组/ServiceAccount |

### Role 与 ClusterRole

Role 在指定 Namespace 内生效，定义了对哪些资源有什么操作权限：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  namespace: default
  name: pod-reader
rules:
- apiGroups: [""]           # 核心 API 组
  resources: ["pods"]       # 资源类型
  verbs: ["get", "list", "watch"]   # 允许的操作
```

ClusterRole 则可以跨 Namespace 访问集群级资源（如 Nodes、PersistentVolumes），或者跨 Namespace 访问命名空间级资源：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cluster-reader
rules:
- apiGroups: [""]
  resources: ["nodes", "persistentvolumes"]   # 集群级资源
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods", "services", "configmaps"]  # 命名空间级资源
  verbs: ["get", "list", "watch"]
```

**verbs 常用取值：**

| Verb | 含义 |
|------|------|
| `get` | 获取单个资源 |
| `list` | 列出资源 |
| `watch` | 监听资源变化 |
| `create` | 创建资源 |
| `update` | 更新资源 |
| `patch` | 部分更新资源 |
| `delete` | 删除资源 |

### RoleBinding 与 ClusterRoleBinding

RoleBinding 将 Role 绑定到一个或多个 Subject（用户、组、ServiceAccount）：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: read-pods
  namespace: default
subjects:
- kind: User              # 也可以是 Group 或 ServiceAccount
  name: alice
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: pod-reader
  apiGroup: rbac.authorization.k8s.io
```

ClusterRoleBinding 同理，但作用于整个集群：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: cluster-admin-binding
subjects:
- kind: ServiceAccount
  name: admin-sa
  namespace: kube-system
roleRef:
  kind: ClusterRole
  name: cluster-admin
  apiGroup: rbac.authorization.k8s.io
```

### 为什么要区分 Role 和 ClusterRole？

当一个 **ClusterRole** 通过 **RoleBinding** 绑定时，该绑定只会在特定的 Namespace 内生效——这是一种"降级"使用方式。比如，你可以创建一个集群级的 `pod-reader` ClusterRole，然后在不同 Namespace 用 RoleBinding 来绑定它，而不用在每个 Namespace 重复创建相同的 Role。

## 实战：创建只读用户

假设你需要为团队中的新成员小明创建一个只能查看资源的只读账号。

### 1. 创建证书和 kubeconfig

```bash
# 生成私钥
openssl genrsa -out xiaoming.key 2048

# 生成证书签名请求
openssl req -new -key xiaoming.key -out xiaoming.csr -subj "/CN=xiaoming/O=dev-team"

# 用集群 CA 签发证书
sudo openssl x509 -req -in xiaoming.csr -CA /etc/kubernetes/pki/ca.crt \
  -CAkey /etc/kubernetes/pki/ca.key -CAcreateserial -out xiaoming.crt -days 365

# 配置 kubeconfig
kubectl config set-credentials xiaoming \
  --client-certificate=xiaoming.crt \
  --client-key=xiaoming.key

kubectl config set-context xiaoming-context \
  --cluster=my-cluster \
  --user=xiaoming

kubectl config use-context xiaoming-context
```

此时小明使用 `xiaoming-context` 访问集群，但由于没有授权，任何操作都会被拒绝。

### 2. 创建只读 Role 并绑定

```yaml
# reader-role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  namespace: default
  name: readonly-role
rules:
- apiGroups: ["", "apps", "networking.k8s.io"]
  resources: ["pods", "services", "deployments", "ingresses", "configmaps", "secrets"]
  verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  namespace: default
  name: xiaoming-readonly
subjects:
- kind: User
  name: xiaoming
  apiGroup: rbac.authorization.k8s.io
roleRef:
  kind: Role
  name: readonly-role
  apiGroup: rbac.authorization.k8s.io
```

```bash
kubectl apply -f reader-role.yaml
```

### 3. 验证

```bash
# 切换到小明上下文
kubectl config use-context xiaoming-context

# 只读操作 ✅
kubectl get pods
kubectl get deployments
kubectl get services

# 写操作 ❌
kubectl delete pod nginx-xxx
# Error from server (Forbidden): pods "nginx-xxx" is forbidden
```

## 实战：为应用创建专用 ServiceAccount

假设你有一个应用需要读取当前 Namespace 下的所有 Pod 和 Service 信息。

### 1. 创建 ServiceAccount

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: app-reader
  namespace: default
```

### 2. 创建 ClusterRole 和 ClusterRoleBinding

因为我们要创建一个通用的只读角色，用 ClusterRole 方便以后复用：

```yaml
# app-reader-role.yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: app-reader-role
rules:
- apiGroups: [""]
  resources: ["pods", "services", "endpoints"]
  verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-reader-binding
  namespace: default
subjects:
- kind: ServiceAccount
  name: app-reader
  namespace: default
roleRef:
  kind: ClusterRole
  name: app-reader-role
  apiGroup: rbac.authorization.k8s.io
```

### 3. 在 Pod 中使用

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: app-with-sa
spec:
  serviceAccountName: app-reader
  containers:
  - name: my-app
    image: curlimages/curl
    command: ["/bin/sh", "-c"]
    args:
    - |
      # 使用挂载的 Token 访问 API
      TOKEN=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
      curl -sSk -H "Authorization: Bearer $TOKEN" \
        https://kubernetes.default.svc/api/v1/namespaces/default/pods
```

## 最小权限原则

生产环境中，不要为了省事而直接使用 `cluster-admin` 角色。遵循**最小权限原则**：

> 每个用户、每个应用只授予完成其任务所必需的最小权限集合。

### 常见权限设计模式

| 场景 | 推荐角色 | 说明 |
|------|---------|------|
| 开发人员（只读） | Role/ClusterRole + get/list/watch | 查看资源，不修改 |
| 运维人员 | 根据职责自定义 Role | 可操作对应 Namespace 的资源 |
| CI/CD 工具 | 特定 Namespace 的创建/更新权限 | 只部署到目标环境 |
| Prometheus | ClusterRole + get/list/watch | 跨 Namespace 采集指标 |
| Dashboard | 用户自身 Namespace 的管理权限 | 避免越权操作 |

### 常用检查命令

```bash
# 查看当前用户权限
kubectl auth can-i create deployments

# 查看其他用户权限
kubectl auth can-i get pods --as system:serviceaccount:default:app-reader

# 查看某用户在 Namespace 下的权限
kubectl auth can-i list pods --namespace kube-system --as xiaoming
```

## 总结

本文我们学习了：

- K8s 安全三层模型：**认证 → 授权 → 准入控制**
- **认证**方式：kubeconfig（人类用户）、ServiceAccount（应用身份）
- **RBAC 授权模型**：Role / ClusterRole 定义权限，RoleBinding / ClusterRoleBinding 将权限绑定到 Subject
- 实战创建了**只读用户**和**应用专用 ServiceAccount**，走通了完整的认证授权流程
- **最小权限原则**是生产安全的基本准则，结合 `kubectl auth can-i` 可以随时验证权限

安全是 K8s 生产化道路上不可绕过的基石。掌握了 RBAC，你就拥有了精细控制谁能做什么的能力。下一篇文章我们将进入可观测性领域，学习如何监控集群和采集日志。

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
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
