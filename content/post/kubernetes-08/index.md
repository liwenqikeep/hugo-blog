---
title: "K8s（八）：Volume 与持久化存储"
date: 2022-04-15
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Volume", "PV", "PVC", "存储"]
toc: true
---

## 前言

容器默认的存储行为是**临时且不可靠的**：

- 当容器崩溃重启后，容器内的文件系统被重置，所有数据丢失
- 当一个 Pod 中有多个容器，它们之间默认无法共享文件

对于日志收集、缓存共享等场景，我们需要容器内的临时存储；而对于数据库等有状态应用，我们需要**即使 Pod 被删除、数据依然保留**的持久化存储。

Kubernetes 的 Volume 体系就是为了解决这些问题而设计的。本文将从最基础的 Volume 类型出发，逐步深入 PV、PVC 和 StorageClass 的完整存储生态。

<!--more-->

## 一、Volume 基础

### 1.1 什么是 Volume？

Kubernetes 的 Volume 本质上是 Pod 中的一个目录，Pod 中所有容器都可以访问这个目录。不同类型的 Volume 决定了这个目录的来源和生命周期。

```text
  ┌─────────────────────────────┐
  │           Pod               │
  │  ┌─────────┐  ┌─────────┐  │
  │  │ 容器 A   │  │ 容器 B   │  │
  │  │  /data   │  │  /data   │  │
  │  └────┬────┘  └────┬────┘  │
  │       └──────┬──────┘      │
  │              ▼             │
  │        ┌──────────┐        │
  │        │  Volume   │        │  ← Pod 级别的存储
  │        └──────────┘        │
  └─────────────────────────────┘
```

**Volume 的生命周期与 Pod 绑定**——Pod 存在，Volume 就存在；Pod 被删除，Volume 也随之消亡（部分类型除外，如 PV）。

### 1.2 常见 Volume 类型

#### emptyDir

`emptyDir` 是最基础的 Volume 类型。Pod 创建时创建一个空目录，Pod 删除时目录被清空。

```yaml
# pod-emptydir.yaml
apiVersion: v1
kind: Pod
metadata:
  name: emptydir-pod
spec:
  containers:
    - name: writer
      image: busybox
      command: ["/bin/sh", "-c", "while true; do echo $(date) >> /data/log.txt; sleep 1; done"]
      volumeMounts:
        - name: shared-data
          mountPath: /data
    - name: reader
      image: busybox
      command: ["/bin/sh", "-c", "tail -f /data/log.txt"]
      volumeMounts:
        - name: shared-data
          mountPath: /data
  volumes:
    - name: shared-data
      emptyDir: {}
```

**典型用途**：

- 容器间共享数据（如 sidecar 模式：主容器写日志，sidecar 容器读取并发送到远程）
- 临时缓存
- 磁盘排序（将数据写入 emptyDir 比在内存中处理更稳定）

#### hostPath

`hostPath` 将宿主机上的目录挂载到 Pod 中。

```yaml
# pod-hostpath.yaml
apiVersion: v1
kind: Pod
metadata:
  name: hostpath-pod
spec:
  containers:
    - name: app
      image: nginx:1.21-alpine
      volumeMounts:
        - name: host-data
          mountPath: /usr/share/nginx/html
  volumes:
    - name: host-data
      hostPath:
        path: /data/nginx      # 宿主机路径
        type: DirectoryOrCreate # 目录不存在则创建
```

**注意事项**：

| hostPath 类型 | 行为 |
|--------------|------|
| `DirectoryOrCreate` | 目录不存在则自动创建 |
| `Directory` | 目录必须已存在 |
| `FileOrCreate` | 文件不存在则自动创建 |
| `File` | 文件必须已存在 |

**使用限制**：

- Pod 调度到不同节点后，访问的是不同宿主机的目录
- 不适合跨节点的数据共享
- 需要关注宿主机文件系统权限

**适用场景**：DaemonSet 读取宿主机日志（如 `/var/log`）、监控 agent 访问宿主机 `/sys` 和 `/proc`。

---

## 二、PV 与 PVC——存储与使用的解耦

单个 Pod 使用 emptyDir 或 hostPath 没问题，但当集群规模扩大后，我们需要更灵活的存储管理方式。Kubernetes 提供了 **PV（PersistentVolume）** 和 **PVC（PersistentVolumeClaim）** 两个抽象层。

### 2.1 核心概念

```text
  ┌────────────────┐
  │  管理员/集群    │
  │                │
  │   PV 池        │  ← 存储资源（由管理员预先创建或 StorageClass 动态供给）
  │  ┌──┐ ┌──┐    │
  │  │PV│ │PV│    │
  │  │10G│ │50G│   │
  │  └──┘ └──┘    │
  └───────┬───────┘
          │ 绑定（Bind）
          ▼
  ┌────────────────┐
  │  用户/开发者    │
  │                │
  │   PVC          │  ← 存储需求声明（用户指定大小、访问模式）
  │  "我需要 10G   │
  │  读写一次的存储"│
  └────────────────┘
```

| 角色 | 资源 | 职责 |
|------|------|------|
| **集群管理员** | PV | 准备存储资源（提前创建或配置 StorageClass） |
| **用户/开发者** | PVC | 声明存储需求（多大、什么读写模式） |
| **Kubernetes** | 绑定 | 将 PVC 与满足条件的 PV 自动匹配绑定 |

### 2.2 创建 PV

```yaml
# pv-example.yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: pv-local-10g
spec:
  capacity:
    storage: 10Gi                     # 容量
  volumeMode: Filesystem              # Filesystem 或 Block
  accessModes:
    - ReadWriteOnce                   # 单节点读写
  persistentVolumeReclaimPolicy: Retain # 回收策略
  storageClassName: manual            # 存储类名称
  local:
    path: /mnt/data                   # 宿主机路径
  nodeAffinity:
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - key: kubernetes.io/hostname
              operator: In
              values:
                - node-1              # 指定节点（local PV 必须）
```

### 2.3 访问模式（Access Modes）

| 模式 | 缩写 | 含义 |
|------|------|------|
| `ReadWriteOnce` | RWO | 单节点读写 |
| `ReadOnlyMany` | ROX | 多节点只读 |
| `ReadWriteMany` | RWX | 多节点读写 |

不同存储后端支持的访问模式不同：

| 存储后端 | RWO | ROX | RWX |
|---------|-----|-----|-----|
| hostPath | ✅ | ❌ | ❌ |
| NFS | ✅ | ✅ | ✅ |
| AWS EBS | ✅ | ❌ | ❌ |
| GCP PD | ✅ | ✅ | ❌ |

### 2.4 创建 PVC

```yaml
# pvc-example.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: pvc-local-10g
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
  storageClassName: manual      # 必须与 PV 匹配
```

```bash
kubectl apply -f pv-example.yaml
kubectl apply -f pvc-example.yaml
```

查看绑定状态：

```bash
kubectl get pv
kubectl get pvc
kubectl get pv pv-local-10g -o yaml
```

```text
NAME           CAPACITY   ACCESS MODES   RECLAIM POLICY   STATUS   CLAIM                  STORAGECLASS
pv-local-10g   10Gi       RWO            Retain           Bound    default/pvc-local-10g   manual
```

### 2.5 在 Pod 中使用 PVC

```yaml
# pod-pvc.yaml
apiVersion: v1
kind: Pod
metadata:
  name: pvc-pod
spec:
  containers:
    - name: app
      image: nginx:1.21-alpine
      volumeMounts:
        - name: storage
          mountPath: /usr/share/nginx/html
  volumes:
    - name: storage
      persistentVolumeClaim:
        claimName: pvc-local-10g   # 引用 PVC 名称
```

### 2.6 回收策略（Reclaim Policy）

当 PVC 被删除后，PV 的回收策略决定了其后续行为：

| 策略 | 行为 |
|------|------|
| `Retain` | 保留数据，PV 状态变为 Released，需管理员手动清理 |
| `Delete` | 自动删除 PV 和后端存储（仅云存储支持） |
| `Recycle` | 删除数据并标记为 Available（已废弃，建议使用动态供给） |

```text
PVC 删除后:
  Retain → PV 状态变为 Released（数据保留，等待管理员处理）
  Delete → PV 和后端存储一起删除
```

---

## 三、StorageClass——动态存储供给

手动创建 PV 的效率太低——数据库、缓存、消息队列等应用都需要存储，不可能每个都手动匹配。**StorageClass** 实现了存储的**动态供给**：用户创建 PVC 时，Kubernetes 自动调用存储后端（如 AWS EBS、GCP PD、NFS 等）创建对应的 PV。

### 3.1 工作原理

```text
  用户创建 PVC
       │
       ▼
  ┌─────────────────┐
  │   StorageClass   │  ← 定义了"如何创建存储"的模板
  │  provisioner:    │     提供者（如 kubernetes.io/aws-ebs）
  │  parameters:     │     参数（如 type: gp2）
  │  reclaimPolicy:  │     回收策略
  └────────┬────────┘
           │
           ▼
  调用云存储 API 创建磁盘
           │
           ▼
  自动创建 PV 并绑定 PVC
```

### 3.2 创建 StorageClass

**AWS EBS 示例**：

```yaml
# storageclass-aws.yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
provisioner: kubernetes.io/aws-ebs
parameters:
  type: gp3                    # SSD 类型
  fsType: ext4
  iopsPerGB: "10"
reclaimPolicy: Delete          # PVC 删除后自动删除 PV
allowVolumeExpansion: true     # 支持在线扩容
```

**NFS 示例（使用 nfs-subdir-external-provisioner）**：

```yaml
# storageclass-nfs.yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: nfs-storage
provisioner: k8s-sigs.io/nfs-subdir-external-provisioner
parameters:
  archiveOnDelete: "true"      # 删除 PVC 时归档数据
reclaimPolicy: Delete
```

### 3.3 使用 StorageClass

用户只需在 PVC 中指定 `storageClassName`，剩下的由系统自动完成：

```yaml
# pvc-dynamic.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: dynamic-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
  storageClassName: fast-ssd   # 引用 StorageClass
```

```bash
kubectl apply -f pvc-dynamic.yaml
# 自动创建 PV 并绑定
kubectl get pv
```

### 3.4 默认 StorageClass

可以设置一个 StorageClass 为集群默认值：

```bash
kubectl patch storageclass fast-ssd -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
```

这样不带 `storageClassName` 的 PVC 会自动使用默认 StorageClass。

---

## 四、实战：为 MySQL 提供持久化存储

这是一个完整的实战案例：部署一个使用 PVC 持久化的 MySQL 实例。

### 4.1 创建 PVC

```yaml
# mysql-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-data
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
  storageClassName: standard   # 根据环境替换
```

### 4.2 创建 MySQL Deployment

```yaml
# mysql-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
spec:
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
          volumeMounts:
            - name: mysql-storage
              mountPath: /var/lib/mysql   # MySQL 数据目录
          resources:
            requests:
              memory: "1Gi"
              cpu: "500m"
      volumes:
        - name: mysql-storage
          persistentVolumeClaim:
            claimName: mysql-data
```

### 4.3 创建 Secret 存储密码

```yaml
# mysql-secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
type: Opaque
stringData:
  root-password: "MyStr0ng!Pass"
```

### 4.4 创建 Service

```yaml
# mysql-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
spec:
  selector:
    app: mysql
  ports:
    - port: 3306
      targetPort: 3306
```

### 4.5 部署并验证

```bash
kubectl apply -f mysql-secret.yaml
kubectl apply -f mysql-pvc.yaml
kubectl apply -f mysql-deployment.yaml
kubectl apply -f mysql-service.yaml

# 等待 Pod 就绪
kubectl get pods -l app=mysql
```

验证数据持久化：

```bash
# 1. 创建测试数据
kubectl exec -it pod/mysql-xxx -- mysql -u root -pMyStr0ng!Pass -e "
  CREATE DATABASE testdb;
  USE testdb;
  CREATE TABLE users (id INT, name VARCHAR(50));
  INSERT INTO users VALUES (1, 'Alice');
"

# 2. 删除 Pod（模拟崩溃或重建）
kubectl delete pod mysql-xxx

# 3. 等待新 Pod 启动
kubectl get pods -l app=mysql

# 4. 验证数据仍在
kubectl exec -it pod/mysql-xxx -- mysql -u root -pMyStr0ng!Pass -e "
  SELECT * FROM testdb.users;
"
# 应该返回: 1 | Alice
```

---

## 五、有状态应用的存储考量

对于数据库、消息队列等有状态应用，除了 PV/PVC，还需要考虑以下几点：

### 5.1 选择合适的存储后端

| 应用类型 | 推荐存储 | 访问模式 | 原因 |
|---------|---------|---------|------|
| MySQL / PostgreSQL | 云 SSD（AWS gp3、GCP pd-ssd） | RWO | 需要低延迟、高 IOPS |
| Elasticsearch | 本地 SSD 或云 SSD | RWO | 数据分片，每个节点独立 |
| RabbitMQ / Kafka | 云普通磁盘 | RWO | 顺序写入，对 IOPS 要求低 |
| 文件共享 / NAS | NFS / CephFS | RWX | 多节点同时读写 |
| 日志 / 缓存 | emptyDir 或本地 SSD | RWO | 容忍数据丢失 |

### 5.2 性能考量

- **网络存储 vs 本地存储**：云存储（EBS、PD）是网络盘，延迟比本地盘高
- **IOPS 和吞吐量**：IOPS 密集型应用（数据库）需要高性能存储类
- **容量规划**：监控存储使用率，设置告警，使用 `allowVolumeExpansion: true` 支持在线扩容

### 5.3 备份与恢复

存储的尽头是备份。即使有 PV 持久化，也应该：

- 定期备份数据库（如使用 Velero 备份整个 Namespace）
- 使用数据库自身的备份机制（如 MySQL 的 mysqldump）
- 异地容灾：跨可用区或跨地域备份

---

## 总结

Kubernetes 的存储体系从简单到复杂分为三个层次：

| 层次 | 资源 | 适用场景 |
|------|------|---------|
| **基础 Volume** | emptyDir、hostPath | 临时缓存、容器间共享、宿主机日志采集 |
| **静态供给** | PV + PVC | 需要手动管理存储的场景 |
| **动态供给** | StorageClass + PVC | 生产环境推荐，自动化存储管理 |

**核心命令**：

```bash
# 查看存储资源
kubectl get pv
kubectl get pvc
kubectl get storageclass

# 查看详情
kubectl describe pv <name>
kubectl describe pvc <name>

# 删除 PVC（注意回收策略）
kubectl delete pvc <name>
```

下一篇文章将介绍 **StatefulSet**——专为有状态应用设计的控制器，以及它如何与 Headless Service、PV/PVC 配合，管理数据库等有状态应用的部署。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
