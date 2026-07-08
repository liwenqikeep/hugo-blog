---
title: "K8s（六）：Ingress 与集群外部流量接入"
date: 2022-04-11
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Ingress", "网关", "流量管理"]
toc: true
---

## 前言

上一篇文章我们介绍了 Service，它提供了 Pod 间的服务发现和负载均衡。当需要将服务暴露到集群外部时，我们提到了 NodePort 和 LoadBalancer。但这两种方式都存在一些局限性：

| 方式 | 问题 |
|------|------|
| **NodePort** | 端口范围有限（30000-32767）；每个服务一个端口，难以管理；只能做四层（TCP/UDP）转发 |
| **LoadBalancer** | 需要云平台支持；每个服务一个 LB，成本高昂 |

试想一个场景：你有三个 Web 服务（`blog.example.com`、`shop.example.com`、`api.example.com`），如果使用 NodePort 或 LoadBalancer，每个服务都需要一个独立端口或 LB，既不优雅也不经济。

**Ingress** 就是来解决这个问题的——作为集群的"智能网关"，它支持七层（HTTP/HTTPS）路由，可以根据域名、路径等规则将流量转发到不同的 Service。

<!--more-->

## 一、Ingress 架构概览

Ingress 实际上包含两个部分：

1. **Ingress Controller**：实际处理流量的反向代理/负载均衡器（如 Nginx、Traefik）
2. **Ingress 资源**：描述路由规则的 Kubernetes API 对象

```text
  ┌──────────┐
  │  客户端    │
  └─────┬────┘
        │ http://blog.example.com/api
        ▼
  ┌─────────────────────┐
  │  Ingress Controller  │  ← 实际的代理（Nginx/Traefik）
  │  (运行在集群中)       │
  └──┬──────────┬───────┘
     │          │
     ▼          ▼
  ┌──────┐  ┌──────┐
  │Service│  │Service│
  │ blog │  │ api  │
  └──────┘  └──────┘
```

**关键理解**：

- Ingress **不是**一种 Service 类型，而是一组路由规则的集合
- Ingress **必须配合 Ingress Controller** 才能工作——仅仅创建 Ingress 资源不会产生任何效果
- Ingress Controller 本身通常以 Deployment 形式运行，并对外暴露（如 NodePort 或 LoadBalancer Service）

---

## 二、安装 Nginx Ingress Controller

目前社区最流行的是 **Nginx Ingress Controller**（注意：这不是 Nginx 官方，而是 Kubernetes 社区的实现）。

使用 Helm 安装是最简洁的方式：

```bash
# 添加 Helm 仓库
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

# 安装到 ingress-nginx 命名空间
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace
```

验证安装：

```bash
kubectl get pods -n ingress-nginx
```

```text
NAME                                        READY   STATUS    RESTARTS   AGE
ingress-nginx-controller-7c5c7c7b6f-abcde   1/1     Running   0          2m
```

查看 Ingress Controller 对外暴露的方式：

```bash
kubectl get svc -n ingress-nginx
```

```text
NAME                                 TYPE           CLUSTER-IP      EXTERNAL-IP   PORT(S)
ingress-nginx-controller             LoadBalancer   10.96.100.100   localhost     80:30080/TCP,443:30443/TCP
ingress-nginx-controller-admission   ClusterIP      10.96.100.101   <none>        443/TCP
```

在本地 Minikube 或 kind 环境中，`EXTERNAL-IP` 通常显示为 `localhost`。在生产环境中，它会自动配置云平台的负载均衡器。

---

## 三、Ingress 资源详解

### 3.1 基本 Ingress 资源

```yaml
# basic-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: minimal-ingress
spec:
  ingressClassName: nginx     # 指定 Ingress Controller（K8s 1.18+）
  rules:
    - host: blog.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: blog-service
                port:
                  number: 80
```

### 3.2 pathType 说明

| pathType | 行为 | 示例 |
|----------|------|------|
| `Prefix` | 前缀匹配 | `/api` 匹配 `/api`、`/api/v1`、`/api/v2/users` |
| `Exact` | 精确匹配 | `/api` 只匹配 `/api`，不匹配 `/api/` |
| `ImplementationSpecific` | 由 Ingress Controller 自行决定（Nginx 行为等同 Prefix） |

### 3.3 多路由规则

一个 Ingress 可以定义多个 host 和 path：

```yaml
# multi-route-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: multi-route-ingress
spec:
  ingressClassName: nginx
  rules:
    - host: blog.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: blog-service
                port:
                  number: 80
    - host: api.example.com
      http:
        paths:
          - path: /v1
            pathType: Prefix
            backend:
              service:
                name: api-v1-service
                port:
                  number: 8080
          - path: /v2
            pathType: Prefix
            backend:
              service:
                name: api-v2-service
                port:
                  number: 8080
```

---

## 四、实战一：基于域名的路由

### 4.1 准备两个服务

```yaml
# demo-services.yaml
apiVersion: v1
kind: Service
metadata:
  name: coffee-service
spec:
  selector:
    app: coffee
  ports:
    - port: 80
      targetPort: 80
---
apiVersion: v1
kind: Service
metadata:
  name: tea-service
spec:
  selector:
    app: tea
  ports:
    - port: 80
      targetPort: 80
```

```yaml
# demo-deployments.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: coffee
spec:
  replicas: 1
  selector:
    matchLabels:
      app: coffee
  template:
    metadata:
      labels:
        app: coffee
    spec:
      containers:
        - name: coffee
          image: nginx:1.21-alpine
          ports:
            - containerPort: 80
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tea
spec:
  replicas: 1
  selector:
    matchLabels:
      app: tea
  template:
    metadata:
      labels:
        app: tea
    spec:
      containers:
        - name: tea
          image: nginx:1.21-alpine
          ports:
            - containerPort: 80
```

```bash
kubectl apply -f demo-services.yaml
kubectl apply -f demo-deployments.yaml
```

### 4.2 创建 Ingress

```yaml
# cafe-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: cafe-ingress
spec:
  ingressClassName: nginx
  rules:
    - host: coffee.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: coffee-service
                port:
                  number: 80
    - host: tea.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: tea-service
                port:
                  number: 80
```

```bash
kubectl apply -f cafe-ingress.yaml
```

### 4.3 验证路由

```bash
# 查看 Ingress
kubectl get ingress
```

```text
NAME           CLASS   HOSTS                            ADDRESS        PORTS   AGE
cafe-ingress   nginx   coffee.example.com,tea.example.com   localhost   80      1m
```

在本地测试（需要配置 hosts 或使用 `--resolve`）：

```bash
# 测试 coffee 路由
curl -H "Host: coffee.example.com" http://localhost

# 测试 tea 路由
curl -H "Host: tea.example.com" http://localhost
```

两个请求会被分别路由到不同的后端 Service。

---

## 五、实战二：基于路径的路由

同一域名下，根据路径转发到不同服务：

```yaml
# blog-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: blog-ingress
spec:
  ingressClassName: nginx
  rules:
    - host: www.example.com
      http:
        paths:
          - path: /blog
            pathType: Prefix
            backend:
              service:
                name: blog-service
                port:
                  number: 80
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: api-service
                port:
                  number: 8080
          - path: /static
            pathType: Prefix
            backend:
              service:
                name: static-service
                port:
                  number: 80
```

访问规则：

| 请求 URL | 目标 Service |
|----------|-------------|
| `http://www.example.com/blog` | blog-service:80 |
| `http://www.example.com/api/users` | api-service:8080 |
| `http://www.example.com/static/logo.png` | static-service:80 |

---

## 六、TLS 配置

Ingress 原生支持 HTTPS。我们需要先创建一个包含证书和私钥的 Secret。

### 6.1 生成自签名证书

```bash
# 生成私钥
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout tls.key -out tls.crt \
  -subj "/CN=example.com/O=example"
```

### 6.2 创建 TLS Secret

```bash
kubectl create secret tls example-tls \
  --key tls.key \
  --cert tls.crt
```

### 6.3 配置 Ingress 使用 TLS

```yaml
# tls-ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: tls-ingress
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - www.example.com
      secretName: example-tls    # 引用上面创建的 Secret
  rules:
    - host: www.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: blog-service
                port:
                  number: 80
```

```bash
kubectl apply -f tls-ingress.yaml
```

验证：

```bash
curl -k https://www.example.com
# -k 忽略自签名证书警告
```

如果你使用的是 Let's Encrypt 等合法 CA 签发的证书，可以借助 **cert-manager** 实现证书的自动申请和续期，这是生产环境的推荐做法。

---

## 七、Ingress 常用注解

Ingress Controller 支持通过 annotation 自定义行为：

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: annotated-ingress
  annotations:
    # 限制请求体大小
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    # 启用 CORS
    nginx.ingress.kubernetes.io/enable-cors: "true"
    # URL 重写（将 /api/v1/xxx 重写为 /xxx）
    nginx.ingress.kubernetes.io/rewrite-target: /$2
spec:
  ingressClassName: nginx
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /api/v1(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: api-service
                port:
                  number: 8080
```

常用注解一览：

| 注解 | 作用 |
|------|------|
| `nginx.ingress.kubernetes.io/rewrite-target` | URL 重写 |
| `nginx.ingress.kubernetes.io/proxy-body-size` | 限制请求体大小 |
| `nginx.ingress.kubernetes.io/enable-cors` | 跨域支持 |
| `nginx.ingress.kubernetes.io/ssl-redirect` | HTTP → HTTPS 重定向 |
| `nginx.ingress.kubernetes.io/limit-rps` | 限制每秒请求数 |

---

## 总结

Ingress 是 Kubernetes 集群对外暴露服务的标准方案，它解决了 NodePort 和 LoadBalancer 的诸多局限。

| 概念 | 一句话 |
|------|--------|
| **Ingress Controller** | 实际处理流量的反向代理（Nginx、Traefik 等） |
| **Ingress 资源** | 描述路由规则的 YAML 定义 |
| **pathType** | Prefix（前缀匹配）、Exact（精确匹配） |
| **TLS** | 通过 Secret 绑定证书，启用 HTTPS |

**核心命令**：

```bash
# 查看 Ingress
kubectl get ingress
kubectl describe ingress <name>

# 创建 TLS Secret
kubectl create secret tls <name> --key tls.key --cert tls.crt
```

**何时选择哪种方案**？

| 场景 | 推荐方案 |
|------|---------|
| 开发调试、快速暴露一个服务 | NodePort |
| 公有云、单个服务对外暴露 | LoadBalancer |
| 多个域名/路径、需要 TLS 终结 | Ingress |
| 精细化流量管理（限流、重写、认证） | Ingress + 注解 |

下一篇文章将介绍 **ConfigMap 与 Secret**——Kubernetes 中管理配置和敏感信息的必备工具。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
- [K8s（二）：使用 kubeadm 搭建 K8s 集群实战]({{< relref "post/kubernetes-02" >}})
- [K8s（三）：Pod 核心概念与容器编排基础]({{< relref "post/kubernetes-03" >}})
- [K8s（四）：Deployment 与 ReplicaSet — 应用部署与扩缩容]({{< relref "post/kubernetes-04" >}})
- [K8s（五）：Service 与集群内服务发现]({{< relref "post/kubernetes-05" >}})
- [K8s（七）：ConfigMap 与 Secret — 配置管理]({{< relref "post/kubernetes-07" >}})
- [K8s（八）：Volume 与持久化存储]({{< relref "post/kubernetes-08" >}})
- [K8s（九）：StatefulSet 与有状态应用]({{< relref "post/kubernetes-09" >}})
- [K8s（十）：Helm 包管理器入门]({{< relref "post/kubernetes-10" >}})
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
