---
title: "K8s（五）：Service 与集群内服务发现"
date: 2022-04-09
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Service", "服务发现", "网络"]
toc: true
---

## 前言

在前几篇文章中，我们学习了如何通过 Deployment 管理 Pod 的部署和扩缩容。但这里有一个核心问题：**Pod 的 IP 地址是不固定的**。

当你删除一个 Pod，ReplicaSet 会重新创建一个新 Pod，但新 Pod 的 IP 与旧 Pod 完全不同。如果我们直接通过 Pod IP 访问服务，一旦 Pod 重启或调度到其他节点，客户端就会断连。

此外，多个副本之间需要负载均衡——当一个 Deployment 有 3 个副本时，客户端应该访问哪一个？

Kubernetes 用 **Service** 完美解决了这些问题。

<!--more-->

## 一、Service 的工作原理

### 1.1 为什么需要 Service？

```text
           ┌──────────────────────────┐
           │        客户端             │
           └──────────┬───────────────┘
                      │
                      ▼
              ┌───────────────┐
              │   Service     │  ← 稳定的虚拟 IP（ClusterIP）
              │   (10.96.0.1) │
              └───┬───┬───┬──┘
                  │   │   │
      ┌───────────┘   │   └───────────┐
      ▼               ▼               ▼
  ┌─────────┐   ┌─────────┐   ┌─────────┐
  │ Pod A   │   │ Pod B   │   │ Pod C   │
  │10.1.0.3 │   │10.1.0.5 │   │10.1.0.7 │
  └─────────┘   └─────────┘   └─────────┘
```

Service 提供 **稳定的虚拟 IP（ClusterIP）和 DNS 名称**，客户端只需访问 Service，Kubernetes 会自动将请求转发到后端的某个 Pod。后端 Pod 的增删对客户端完全透明。

### 1.2 标签选择器（Label Selector）

Service 通过 **标签选择器** 找到后端 Pod：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-service
spec:
  selector:          # 标签选择器
    app: nginx       # 匹配所有带有 app=nginx 标签的 Pod
  ports:
    - protocol: TCP
      port: 80       # Service 暴露的端口
      targetPort: 80 # Pod 上的目标端口
```

当一个 Pod 的标签与 `selector` 匹配时，它就会被自动加入 Service 的端点列表。如果 Pod 标签发生变化（不再匹配），它也会自动从列表中移除。

### 1.3 Endpoints 对象

Kubernetes 会自动为每个 Service 创建一个同名的 Endpoints 对象，记录所有匹配 Pod 的 IP:Port：

```bash
kubectl get endpoints my-service
```

```text
NAME         ENDPOINTS                     AGE
my-service   10.1.0.3:80,10.1.0.5:80,10.1.0.7:80   10m
```

你也可以手动创建 Endpoints（当需要将 Service 指向集群外部的服务时，例如一个外部的数据库）。

---

## 二、Service 类型

Kubernetes 支持四种 Service 类型，适用于不同的场景：

### 2.1 ClusterIP（默认）

```text
类型：ClusterIP
访问范围：集群内部
外部访问：❌ 不支持
适用场景：内部服务间调用
```

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-service
spec:
  type: ClusterIP       # 可省略，默认值
  selector:
    app: nginx
  ports:
    - port: 80
      targetPort: 80
```

Kubernetes 会为 Service 分配一个虚拟 IP（ClusterIP），仅在集群内部可达。

### 2.2 NodePort

```text
类型：NodePort
访问范围：集群外部
外部访问：✅ 通过节点 IP:端口访问
适用场景：开发调试、直接暴露服务
```

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-service
spec:
  type: NodePort
  selector:
    app: nginx
  ports:
    - port: 80
      targetPort: 80
      nodePort: 30080    # 可选，不指定则随机分配（30000-32767）
```

NodePort 会在**每个 Node** 上开放一个端口（30080），外部可以通过 `任何节点IP:30080` 访问服务。

```text
  ┌─────────┐
  │ 客户端    │
  └────┬────┘
       │ http://NodeIP:30080
       ▼
┌──────────────┐
│  Node (30080) │
│  ┌──────────┐ │
│  │ Service   │ │
│  │ ClusterIP │ │
│  └────┬─────┘ │
│       ▼       │
│  ┌──────────┐ │
│  │ Pod:80   │ │
│  └──────────┘ │
└──────────────┘
```

### 2.3 LoadBalancer

```text
类型：LoadBalancer
访问范围：集群外部
外部访问：✅ 通过云负载均衡器 IP 访问
适用场景：公有云环境（AWS、GCP、Azure）
```

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-service
spec:
  type: LoadBalancer
  selector:
    app: nginx
  ports:
    - port: 80
      targetPort: 80
```

LoadBalancer 在 NodePort 的基础上，自动调用云平台（如 AWS ELB、GCP TCP LB）创建一个外部负载均衡器，将外部流量分发到集群的所有 NodePort 上。

### 2.4 ExternalName

```text
类型：ExternalName
访问范围：集群内部
外部访问：✅ 将 Service 映射到外部 DNS
适用场景：将集群外部的服务包装成内部服务
```

```yaml
apiVersion: v1
kind: Service
metadata:
  name: external-db
spec:
  type: ExternalName
  externalName: my-database.example.com   # 外部 DNS 名称
```

ExternalName 没有 selector，也不定义端口。它直接将 Service 的 DNS 解析为 `externalName` 的 CNAME。应用无需关心数据库在集群内还是集群外，访问 `external-db` 即可。

---

## 三、服务发现机制

Pod 如何找到 Service 的地址？Kubernetes 提供了两种机制：

### 3.1 环境变量方式

当 Pod 创建时，kubelet 会将当前 Namespace 中所有 Service 的信息注入为环境变量：

```bash
# 在 Pod 中查看
env | grep SERVICE
```

```text
MY_SERVICE_SERVICE_HOST=10.96.0.1
MY_SERVICE_SERVICE_PORT=80
MY_SERVICE_PORT=tcp://10.96.0.1:80
```

**限制**：环境变量只在 Pod **创建时**注入。如果一个 Service 在 Pod 创建之后才创建，Pod 不会自动获得新的环境变量。

### 3.2 CoreDNS——推荐的方案

Kubernetes 集群默认安装 CoreDNS，它为 Service 提供 DNS 解析：

```
Service 名称 → 完整域名

<service>.<namespace>.svc.cluster.local
```

在同一个 Namespace 中，可以直接用 Service 名称访问：

```bash
# 在 Pod 内访问
curl http://my-service:80
```

跨 Namespace 访问：

```bash
curl http://my-service.default.svc.cluster.local:80
```

**推荐**：始终使用 DNS 名称代替环境变量。DNS 方式更灵活，不受 Pod 创建顺序影响。

---

## 四、实战：创建一个 ClusterIP 服务

### 4.1 创建 Deployment

首先创建一个 Nginx Deployment：

```yaml
# nginx-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
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
          image: nginx:1.21-alpine
          ports:
            - containerPort: 80
```

```bash
kubectl apply -f nginx-deployment.yaml
kubectl get pods -l app=nginx
```

```text
NAME                                READY   STATUS    RESTARTS   AGE
nginx-deployment-7c5c7c7b6f-2xqk9   1/1     Running   0          1m
nginx-deployment-7c5c7c7b6f-4b7n3   1/1     Running   0          1m
nginx-deployment-7c5c7c7b6f-9p2s1   1/1     Running   0          1m
```

### 4.2 创建 Service

```yaml
# nginx-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: nginx-service
spec:
  type: ClusterIP
  selector:
    app: nginx
  ports:
    - port: 80
      targetPort: 80
```

```bash
kubectl apply -f nginx-service.yaml
```

### 4.3 验证 Service

```bash
# 查看 Service
kubectl get svc nginx-service
```

```text
NAME            TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)   AGE
nginx-service   ClusterIP   10.96.123.45   <none>        80/TCP    1m
```

```bash
# 查看 Endpoints
kubectl get endpoints nginx-service
```

```text
NAME            ENDPOINTS                                               AGE
nginx-service   10.1.0.3:80,10.1.0.5:80,10.1.0.7:80                   1m
```

### 4.4 在集群内验证

启动一个临时 Pod 来测试 Service 访问：

```bash
kubectl run test-pod --image=busybox -it --rm --restart=Never -- sh
```

在 Pod 内执行：

```bash
# 通过 ClusterIP 访问
wget -qO- http://10.96.123.45:80

# 通过 Service 名称访问（DNS）
wget -qO- http://nginx-service:80
```

两种方式都应该返回 Nginx 的默认欢迎页面。

---

## 五、无头服务（Headless Service）

有时你不需要负载均衡，也不需要虚拟 IP——你希望客户端直接访问到所有 Pod 的真实 IP。这就是 **Headless Service** 的用途。

Headless Service 将 `clusterIP` 设为 `None`：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: headless-nginx
spec:
  clusterIP: None        # 关键字段
  selector:
    app: nginx
  ports:
    - port: 80
      targetPort: 80
```

Headless Service 的特点：

```text
| 特性 | 普通 Service | Headless Service |
|------|-------------|-----------------|
| ClusterIP | 有（虚拟 IP） | 无（None） |
| 负载均衡 | 自动轮询 | 无（由客户端处理） |
| DNS 解析 | 返回单个虚拟 IP | 返回所有 Pod 的 IP 列表 |
| 适用场景 | 大部分无状态应用 | 有状态应用（StatefulSet）、需要直连 Pod 的场景 |
```

DNS 解析示例：

```bash
# 普通 Service DNS 查询
nslookup nginx-service
# 返回: 10.96.123.45

# Headless Service DNS 查询
nslookup headless-nginx
# 返回:
# 10.1.0.3
# 10.1.0.5
# 10.1.0.7
```

Headless Service 在 StatefulSet、Kafka、Elasticsearch 等有状态应用中广泛使用，我们将在后续文章中详细展开。

---

## 总结

Service 是 Kubernetes 网络模型的核心组件，它解决了 Pod IP 不固定和服务发现两大难题。

| 概念 | 一句话 |
|------|--------|
| **ClusterIP** | 默认类型，提供集群内部的虚拟 IP |
| **NodePort** | 在每个节点上开放端口，供外部访问 |
| **LoadBalancer** | 结合云平台 LB 对外暴露服务 |
| **ExternalName** | 将外部 DNS 映射为内部 Service |
| **CoreDNS** | 集群内的 DNS 服务，提供基于名称的服务发现 |
| **Headless Service** | 不提供虚拟 IP，直接返回 Pod IP 列表 |

**核心命令**：

```bash
kubectl expose deployment nginx --port=80 --type=ClusterIP --name=my-service
kubectl get svc
kubectl get endpoints
kubectl describe svc my-service
```

下一篇文章我们将介绍 **Ingress**——如何通过域名和路径规则，将集群外部流量精细化地路由到不同的 Service。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（六）：Ingress 与集群外部流量接入]({{< relref "post/kubernetes-06" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
