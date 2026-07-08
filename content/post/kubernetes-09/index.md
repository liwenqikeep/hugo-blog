---
title: "K8s（九）：StatefulSet 与有状态应用"
date: 2022-04-17
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "StatefulSet", "有状态应用"]
toc: true
---

在前面的文章中，我们学习了 Deployment、Service、Volume 等核心概念。Deployment 非常适合部署无状态应用——所有 Pod 都是可互换的，任何一个 Pod 都可以随时被替换。但如果应用需要**持久化数据**和**稳定的网络标识**呢？比如数据库、消息队列、缓存集群等有状态应用。这时，StatefulSet 就派上了用场。

<!--more-->

## 有状态 vs 无状态应用

在深入 StatefulSet 之前，先理解两个概念：

| 特性 | 无状态应用（Stateless） | 有状态应用（Stateful） |
|------|------------------------|------------------------|
| 数据持久性 | 不需要持久化数据 | 需要持久化数据 |
| 网络标识 | 不要求固定名称 | 需要稳定的网络标识 |
| 扩缩容 | 随意扩缩、顺序无关 | 需要有序、可控的扩缩 |
| 典型代表 | Web 前端、API 服务 | 数据库、消息队列、缓存 |

Deployment 创建的 Pod 名称是随机 hash（如 `web-7d8b9c5d6f-abcde`），重启后名称和 IP 都会变。这对无状态应用没问题，但对有状态应用——比如数据库主从节点——节点身份必须固定，不能随着重启而改变。

## StatefulSet 核心特性

StatefulSet 专门为有状态应用设计，提供了三个关键保证：

### 1. 稳定的网络标识

StatefulSet 创建的每个 Pod 都有一个**固定且有序的标识**，格式为 `$(statefulset名称)-$(序号)`。

例如，一个名为 `mysql`、副本数为 3 的 StatefulSet 会依次创建：

```text
mysql-0
mysql-1
mysql-2
```

每个 Pod 的名称在整个生命周期内保持不变——即使 Pod 被重新调度到其他节点，名称也不会变。配合无头服务（Headless Service），可以通过 DNS 以固定域名访问每个 Pod。

### 2. 稳定的存储

StatefulSet 通过 `volumeClaimTemplates`（卷申请模板）为每个 Pod 自动创建独立的 PVC（PersistentVolumeClaim）。即使 Pod 被删除重建，PVC 依然保留，新 Pod 会自动挂载同一块持久化存储。

### 3. 有序部署、伸缩与删除

StatefulSet 对 Pod 的创建和删除遵循严格的顺序：

- **有序创建**：从序号 0 开始，依次创建，只有前一个 Pod 进入 Running 状态后才会创建下一个
- **有序缩容**：从序号最大的 Pod 开始删除
- **有序滚动更新**：默认从最大序号开始更新，保证应用始终可用

## StatefulSet 的 YAML 结构

下面是一个 StatefulSet 的典型 YAML：

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: web
spec:
  serviceName: "nginx"          # 关联的无头 Service 名称
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
        image: nginx:1.21
        ports:
        - containerPort: 80
          name: web
        volumeMounts:
        - name: www
          mountPath: /usr/share/nginx/html
  volumeClaimTemplates:          # 卷申请模板
  - metadata:
      name: www
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 1Gi
```

关键字段说明：

- **`serviceName`**：必须指定一个无头 Service（Headless Service，即 `clusterIP: None`），用来为每个 Pod 提供稳定的 DNS 域名
- **`volumeClaimTemplates`**：定义 PVC 模板，StatefulSet 会为每个 Pod 自动创建一个 PVC，命名格式为 `$(volumeClaimTemplates名称)-$(statefulset名称)-$(序号)`

### 无头 Service 示例

```yaml
apiVersion: v1
kind: Service
metadata:
  name: nginx
spec:
  clusterIP: None        # 无头 Service
  selector:
    app: nginx
  ports:
  - port: 80
```

有了这个 Service，每个 Pod 可以通过以下 DNS 名称被直接访问：

```text
web-0.nginx.default.svc.cluster.local
web-1.nginx.default.svc.cluster.local
web-2.nginx.default.svc.cluster.local
```

## 实战：部署有状态 MySQL 实例

下面我们通过 StatefulSet 部署一个单实例 MySQL，体会稳定的存储和网络标识带来的好处。

### 1. 创建 Namespace

```bash
kubectl create namespace database
```

### 2. 创建 Secret 保存 MySQL 密码

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: database
type: Opaque
data:
  root-password: cm9vdDEyMzQ1Ng==   # root123456 的 base64
```

### 3. 创建无头 Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
  namespace: database
spec:
  clusterIP: None
  selector:
    app: mysql
  ports:
  - port: 3306
```

### 4. 创建 StatefulSet

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
  namespace: database
spec:
  serviceName: mysql
  replicas: 1
  selector:
    matchLabels:
      app: mysql
  template:
    metadata:
      labels:
        app: mysql
    spec:
      containers:
      - name: mysql
        image: mysql:8.0
        env:
        - name: MYSQL_ROOT_PASSWORD
          valueFrom:
            secretKeyRef:
              name: mysql-secret
              key: root-password
        ports:
        - containerPort: 3306
          name: mysql
        volumeMounts:
        - name: data
          mountPath: /var/lib/mysql
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
```

启动后，MySQL Pod 名称为 `mysql-0`，数据存储在自动创建的 PVC `data-mysql-0` 中。

### 5. 验证

```bash
# 查看 Pod
kubectl -n database get pods

# 查看 PVC
kubectl -n database get pvc

# 通过 DNS 访问
kubectl run -it --rm busybox --image=busybox -- sh
# nslookup mysql-0.mysql.database.svc.cluster.local
```

删除 Pod 后重建，验证数据是否保留：

```bash
# 删除 Pod
kubectl -n database delete pod mysql-0

# 等待 StatefulSet 自动重建
kubectl -n database get pods -w

# 进入容器检查数据
kubectl -n database exec mysql-0 -- mysql -uroot -proot123456 -e "SHOW DATABASES;"
```

数据依然存在！这就是 `volumeClaimTemplates` 的功劳——Pod 虽然重建了，但 PVC 没有删除，新 Pod 自动绑定了同一块存储。

## 扩缩容与更新策略

### 扩缩容

与 Deployment 类似，修改 `replicas` 即可：

```bash
kubectl scale sts mysql --replicas=3 -n database
```

StatefulSet 会依次创建 `mysql-1`、`mysql-2`（前提是你配置了主从同步机制）。

### 更新策略

StatefulSet 支持两种更新策略：

1. **`RollingUpdate`（默认）**：滚动更新，从最大序号开始更新，保证应用持续可用
2. **`OnDelete`**：手动删除 Pod 后才触发更新，适合需要精细控制的场景

```yaml
spec:
  updateStrategy:
    type: RollingUpdate
```

`RollingUpdate` 还支持 `partition` 参数，用于实现**金丝雀发布**（灰度发布）：

```yaml
spec:
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: 2   # 只更新序号 >= 2 的 Pod
```

这样只有 `mysql-2` 会被更新，`mysql-0` 和 `mysql-1` 保持不变，验证新版本没问题后再逐步降低 `partition` 值。

## 总结

本文我们学习了：

- **StatefulSet** 是 K8s 中有状态应用的核心工作负载，提供稳定的网络标识、稳定的存储和有序的部署/伸缩行为
- 通过 **`volumeClaimTemplates`** 自动为每个 Pod 创建独立的 PVC，实现存储与 Pod 生命周期的解耦
- 配合**无头 Service（Headless Service）**，为每个 Pod 提供固定的 DNS 域名
- 支持**有序滚动更新**和 **OnDelete** 两种更新策略，以及**分区更新（partition）**实现灰度发布
- 实战部署了 MySQL 实例，验证了 Pod 重建后数据持久化能力

StatefulSet 在 K8s 生态中承担着"压舱石"的角色——当你的应用需要稳定的身份和数据时，它就是不二之选。下一篇文章我们将介绍 Helm 包管理器，看看如何将复杂的 K8s 资源打包复用。

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
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
