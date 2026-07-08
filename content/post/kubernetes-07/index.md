---
title: "K8s（七）：ConfigMap 与 Secret — 配置管理"
date: 2022-04-13
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "ConfigMap", "Secret", "配置"]
toc: true
---

## 前言

在传统部署方式中，应用程序的配置通常以配置文件、环境变量或命令行参数的形式存在。但在 Kubernetes 中，我们面临几个新挑战：

1. **环境差异**：开发、测试、生产环境使用不同的配置（数据库地址、日志级别等）
2. **配置更新**：修改配置后需要重启 Pod，但不想重建镜像
3. **敏感信息**：数据库密码、API Key 等不应硬编码在镜像中

Kubernetes 提供了两个专用资源来解决这些问题：

- **ConfigMap**：存储非敏感的配置数据（配置文件、环境变量等）
- **Secret**：存储敏感数据（密码、密钥、证书等），使用 Base64 编码，并支持加密存储

<!--more-->

## 一、ConfigMap 概述

### 1.1 什么是 ConfigMap？

ConfigMap 是一个键值对（key-value）存储对象，用于保存非敏感的配置数据。它的核心思想是**将配置与镜像解耦**——同一份镜像可以通过不同的 ConfigMap 适配不同环境。

```text
   ┌─────────────┐          ┌──────────┐
   │ ConfigMap   │          │  镜像     │
   │ dev 环境配置  │          │  app:1.0 │
   │ DB=dev-db   │          └────┬─────┘
   │ LOG=debug   │               │
   └──────┬──────┘               │
          │ 挂载/注入             │
          └──────────┬───────────┘
                     ▼
              ┌────────────┐
              │ Pod (dev)  │
              │ DB=dev-db  │
              │ LOG=debug  │
              └────────────┘
```

### 1.2 ConfigMap 的创建方式

**方式一：字面值创建**

```bash
kubectl create configmap app-config --from-literal=APP_ENV=production --from-literal=LOG_LEVEL=info
```

**方式二：从文件创建**

```bash
# 将文件内容作为键值对
kubectl create configmap app-config --from-file=application.properties

# 指定键名
kubectl create configmap app-config --from-file=my-config=application.properties
```

**方式三：从目录创建**

```bash
kubectl create configmap app-config --from-file=./config-dir/
```

目录下的每个文件名成为 key，文件内容成为 value。

**方式四：通过 YAML 声明式创建**

```yaml
# configmap-demo.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  APP_ENV: production
  LOG_LEVEL: info
  # 多行配置
  nginx.conf: |
    server {
      listen 80;
      server_name example.com;
    }
```

```bash
kubectl apply -f configmap-demo.yaml
```

---

## 二、ConfigMap 的使用方式

Pod 可以通过两种方式使用 ConfigMap 中的数据。

### 2.1 环境变量方式

将 ConfigMap 中的键值对注入为 Pod 的环境变量：

```yaml
# pod-env.yaml
apiVersion: v1
kind: Pod
metadata:
  name: configmap-env-pod
spec:
  containers:
    - name: app
      image: busybox
      command: ["/bin/sh", "-c", "env | grep APP"]
      env:
        # 方式一：直接引用单个键
        - name: APP_ENV
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: APP_ENV
        # 方式二：注入所有键值对
        # envFrom:
        #   - configMapRef:
        #       name: app-config
```

**推荐使用 `envFrom`** 注入所有键值对：

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: configmap-env-pod
spec:
  containers:
    - name: app
      image: busybox
      command: ["/bin/sh", "-c", "env | grep APP"]
      envFrom:
        - configMapRef:
            name: app-config
```

### 2.2 Volume 挂载方式

将 ConfigMap 挂载为容器内的文件系统，适合挂载配置文件：

```yaml
# pod-volume.yaml
apiVersion: v1
kind: Pod
metadata:
  name: configmap-volume-pod
spec:
  containers:
    - name: app
      image: nginx:1.21-alpine
      volumeMounts:
        - name: config
          mountPath: /etc/nginx/conf.d   # 挂载到目录
          readOnly: true
  volumes:
    - name: config
      configMap:
        name: app-config                 # 引用 ConfigMap
```

Volume 挂载后，ConfigMap 中的每个 key 会成为一个文件，value 为文件内容：

```text
/etc/nginx/conf.d/
├── APP_ENV        # 内容: production
├── LOG_LEVEL      # 内容: info
└── nginx.conf     # 内容: server { listen 80; ... }
```

**Volume 挂载 vs 环境变量**

```text
| 对比维度 | 环境变量方式 | Volume 挂载方式 |
|---------|------------|---------------|
| 使用场景 | 简单的键值对（URL、端口等） | 完整的配置文件 |
| 自动更新 | ❌ 不会更新（Pod 生命周期内固定） | ✅ 支持（定期同步，需应用重新加载） |
| 支持大小 | 适合小数据 | 无限制 |
| 配置复杂度 | 简单 | 灵活 |
```

---

## 三、Secret 概述

### 3.1 什么是 Secret？

Secret 与 ConfigMap 非常相似，但专门用于存储敏感信息。它使用 Base64 编码存储数据，并且在 Kubernetes 1.13+ 中支持**静态加密**（etcd 中的加密存储）。

| 特性 | ConfigMap | Secret |
|------|-----------|--------|
| 数据类型 | 非敏感的配置 | 敏感信息 |
| 存储编码 | 明文 | Base64 |
| etcd 加密 | ❌ | ✅ 可配置 |
| RBAC 控制 | 标准 | 可额外设置细粒度权限 |

### 3.2 Secret 的类型

Kubernetes 支持多种 Secret 类型：

| 类型 | 用途 | data 字段 |
|------|------|----------|
| `Opaque` | 通用（任意键值对） | 自定义 |
| `kubernetes.io/dockerconfigjson` | Docker 私有仓库认证 | `.dockerconfigjson` |
| `kubernetes.io/tls` | TLS 证书 | `tls.crt`、`tls.key` |
| `kubernetes.io/service-account-token` | ServiceAccount 令牌（自动创建） | `token` |

### 3.3 创建 Secret

**方式一：命令行创建**

```bash
# Opaque Secret
kubectl create secret generic db-secret \
  --from-literal=username=admin \
  --from-literal=password=SuperSecret123

# TLS Secret
kubectl create secret tls my-tls \
  --key=tls.key \
  --cert=tls.crt

# Docker 仓库认证
kubectl create secret docker-registry registry-secret \
  --docker-server=https://index.docker.io/v1/ \
  --docker-username=myuser \
  --docker-password=mypassword
```

**方式二：YAML 声明式创建**

```yaml
# secret-demo.yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-secret
type: Opaque
data:
  username: YWRtaW4=          # admin 的 Base64
  password: U3VwZXJTZWNyZXQxMjM=  # SuperSecret123 的 Base64
```

生成 Base64：

```bash
echo -n "admin" | base64
# 输出: YWRtaW4=

echo -n "SuperSecret123" | base64
# 输出: U3VwZXJTZWNyZXQxMjM=
```

> 使用 `stringData` 字段可以直接写明文，Kubernetes 会在创建时自动编码：

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: db-secret
type: Opaque
stringData:          # 明文写入，自动编码
  username: admin
  password: SuperSecret123
```

---

## 四、Secret 的使用方式

### 4.1 环境变量方式

```yaml
# pod-secret-env.yaml
apiVersion: v1
kind: Pod
metadata:
  name: secret-env-pod
spec:
  containers:
    - name: app
      image: busybox
      command: ["/bin/sh", "-c", "env | grep DB"]
      env:
        - name: DB_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: username
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: password
```

### 4.2 Volume 挂载方式

```yaml
# pod-secret-volume.yaml
apiVersion: v1
kind: Pod
metadata:
  name: secret-volume-pod
spec:
  containers:
    - name: app
      image: nginx:1.21-alpine
      volumeMounts:
        - name: secret-volume
          mountPath: /etc/db-credentials
          readOnly: true
  volumes:
    - name: secret-volume
      secret:
        secretName: db-secret
```

挂载后，每个 key 成为一个文件，内容为**解码后的原始值**（不是 Base64）：

```text
/etc/db-credentials/
├── username    # 内容: admin（解码后）
└── password    # 内容: SuperSecret123（解码后）
```

---

## 五、不可变配置（Immutable）

Kubernetes 1.21+ 支持将 ConfigMap 和 Secret 标记为不可变（immutable）：

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
immutable: true   # 设置为不可变
data:
  APP_ENV: production
  LOG_LEVEL: info
```

**优点**：

- 提高性能：不可变的 ConfigMap/Secret 不会被 watch，减轻 API Server 负担
- 增强安全性：防止配置被意外修改

**注意事项**：

- 一旦设置 `immutable: true`，**只能删除重建**，不能修改
- 如果 Pod 引用了不可变的 ConfigMap，必须更新 Pod 模板才能使用新配置
- 适用于**确定不变**的配置（如日志格式、框架初始化参数等）

---

## 六、最佳实践

### 6.1 ConfigMap 使用建议

1. **不要将敏感信息放入 ConfigMap**——使用 Secret
2. **命名规范**：使用有意义的命名，如 `app-config`、`nginx-config`
3. **版本管理**：ConfigMap 的 YAML 应纳入 Git 仓库管理
4. **按环境拆分**：不同环境使用不同的 ConfigMap
5. **Volume 挂载的自动更新**：ConfigMap 更新后，挂载的文件会同步更新（数分钟延迟），但应用需要自行检测文件变化并重新加载

### 6.2 Secret 使用建议

1. **不要提交明文 Secret 到 Git**——使用外部密钥管理工具（如 HashiCorp Vault、AWS Secrets Manager）
2. **开启 etcd 加密**：配置 `--encryption-provider-config` 加密 etcd 中的 Secret 数据
3. **最小权限原则**：通过 RBAC 限制 Secret 的访问权限
4. **使用 External Secrets Operator**：通过 CRD 从外部密钥管理系统同步 Secret，避免明文 YAML 存储
5. **Secret 大小限制**：单个 Secret 最大 1MB（etcd 限制）

### 6.3 配置更新策略

| 使用方式 | 更新后 Pod 行为 | 推荐做法 |
|---------|---------------|---------|
| 环境变量 | Pod 不感知，需重建 | 用于启动时确定不变的配置 |
| Volume 挂载 | 文件自动更新，需应用热加载 | 用于运行时动态更新的配置 |
| immutable | 永远不变，需重建 Pod | 用于真正的不可变配置 |

---

## 总结

ConfigMap 和 Secret 是 Kubernetes 配置管理的基石，它们将配置与镜像解耦，使应用可以灵活适配不同环境。

| 概念 | 一句话 |
|------|--------|
| **ConfigMap** | 存储非敏感配置，支持环境变量和 Volume 挂载 |
| **Secret** | 存储敏感信息，Base64 编码，支持 etcd 加密 |
| **immutable** | 不可变标记，提高性能和安全性 |
| **envFrom** | 批量注入 ConfigMap/Secret 的所有键值对 |
| **Volume 挂载** | 将配置以文件形式挂载到容器，支持自动更新 |

**核心命令**：

```bash
# ConfigMap
kubectl create configmap <name> --from-literal=key=value
kubectl create configmap <name> --from-file=<path>

# Secret
kubectl create secret generic <name> --from-literal=key=value
kubectl create secret tls <name> --key=tls.key --cert=tls.crt

# 查看
kubectl get configmap
kubectl get secret
kubectl describe configmap <name>
```

在下一篇文章中，我们将学习 Kubernetes 的存储体系——**Volume、PV、PVC**，理解如何为容器提供持久化存储。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
