---
title: "K8s（十）：Helm 包管理器入门"
date: 2022-04-19
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "Helm", "包管理"]
toc: true
---

经过前面九篇文章的学习，你已经能够独立编写 Deployment、Service、Ingress、ConfigMap、PersistentVolume 等各种 K8s 资源 YAML 了。但一个稍复杂的应用可能需要十几甚至几十个 YAML 文件，而且每次部署环境不同（开发/测试/生产），参数也要手动修改。有没有一种方式，可以像 `apt` 或 `yum` 一样管理 K8s 应用呢？Helm 就是答案。

<!--more-->

## 前言：K8s YAML 管理的痛点

先看一个典型场景：你想部署一个 WordPress 博客，需要以下资源：

- Deployment（WordPress）
- Service（WordPress）
- ConfigMap（WordPress 配置）
- Deployment（MySQL）
- Service（MySQL）
- Secret（数据库密码）
- PersistentVolumeClaim（MySQL 数据卷）
- Ingress（外部访问）

每个环境（dev/staging/prod）的域名、数据库密码、副本数都不同。手动维护这么多 YAML 文件，容易出错且重复劳动。Helm 正是为了解决这些痛点而生。

## Helm 核心概念

Helm 是 K8s 的包管理器，类比 Linux 中的 apt/yum/homebrew。它包含三个核心概念：

### Chart（图表）

Chart 是 Helm 的软件包格式，本质是一个包含了描述 K8s 资源模板和默认配置的目录结构。一个 Chart 可以打包成一个 `.tgz` 文件分发。你可以把 Chart 类比为 apt 中的 `.deb` 包或 homebrew 中的 formula。

### Repository（仓库）

Repository 是 Chart 的存储和分发仓库，类似于 Docker Registry 或 apt 源。Helm 官方维护了一个 [Artifact Hub](https://artifacthub.io/)，上面有大量社区贡献的 Chart（如 nginx、MySQL、Prometheus 等），可以直接搜索和使用。

### Release（发布）

Release 是 Chart 在某个 K8s 集群中的一次部署实例。同一个 Chart 可以部署多次，每次部署就是一个独立的 Release。比如用 Nginx Chart 部署两个不同的网站，就会产生两个 Release：

```
helm install web-1 bitnami/nginx
helm install web-2 bitnami/nginx
```

## Helm 安装与常用命令

### 安装 Helm

Helm 是一个独立的二进制程序，用包管理器或直接下载均可：

```bash
# Windows (Chocolatey)
choco install kubernetes-helm

# macOS
brew install helm

# Linux
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

安装后验证：

```bash
helm version
```

### 常用命令速查

| 命令 | 用途 |
|------|------|
| `helm repo add <name> <url>` | 添加 Chart 仓库 |
| `helm repo update` | 更新本地仓库索引 |
| `helm search repo <keyword>` | 搜索 Chart |
| `helm install <release> <chart>` | 安装 Chart 创建 Release |
| `helm upgrade <release> <chart>` | 升级 Release |
| `helm rollback <release> <revision>` | 回滚到指定版本 |
| `helm uninstall <release>` | 卸载 Release |
| `helm list` | 查看已安装的 Release |
| `helm get values <release>` | 查看 Release 的配置值 |
| `helm pull <chart>` | 下载 Chart 到本地 |

## Chart 目录结构

一个典型的 Chart 目录结构如下：

```text
mychart/
├── Chart.yaml          # Chart 元信息（名称、版本、描述等）
├── values.yaml         # 默认配置值
├── charts/             # 子 Chart 依赖（可选）
├── templates/          # Go 模板文件目录
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   ├── _helpers.tpl    # 辅助模板（可复用片段）
│   └── NOTES.txt       # 安装后的提示信息
└── README.md           # Chart 说明文档
```

### Chart.yaml 示例

```yaml
apiVersion: v2
name: myweb
description: A simple web application
type: application
version: 0.1.0
appVersion: "1.16.0"
```

### values.yaml 示例

```yaml
replicaCount: 2

image:
  repository: nginx
  tag: "1.21"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: false
  host: myapp.example.com

resources:
  limits:
    cpu: 500m
    memory: 512Mi
  requests:
    cpu: 250m
    memory: 256Mi
```

## 模板化部署

Helm 使用 Go 模板引擎将 `values.yaml` 中的值注入到 `templates/` 下的 YAML 中，实现"一份模板，多环境部署"。

### 模板语法入门

**变量引用：**

```yaml
{{ .Values.replicaCount }}
{{ .Values.image.repository }}
{{ .Values.service.port }}
```

**流程控制：**

```yaml
# if-else
{{ if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
...
{{ end }}

# range 循环
{{ range .Values.ports }}
- name: {{ .name }}
  containerPort: {{ .containerPort }}
{{ end }}
```

**内置对象：**

| 对象 | 含义 |
|------|------|
| `.Values` | values.yaml 中的值 |
| `.Release.Name` | Release 名称 |
| `.Release.Namespace` | Release 所在的命名空间 |
| `.Chart.Name` | Chart 名称 |
| `.Files.Get` | 读取 Chart 中的文件 |

### _helpers.tpl 中的命名模板

`_helpers.tpl` 用来定义可复用的模板片段，如统一的资源名称：

```go
{{- define "myweb.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "myweb.labels" -}}
app.kubernetes.io/name: {{ include "myweb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
```

在模板文件中通过 `include` 引用：

```yaml
metadata:
  name: {{ include "myweb.name" . }}
  labels:
    {{- include "myweb.labels" . | nindent 4 }}
```

### 完整的 Deployment 模板示例

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "myweb.name" . }}
  labels:
    {{- include "myweb.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ include "myweb.name" . }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: {{ include "myweb.name" . }}
    spec:
      containers:
      - name: {{ .Chart.Name }}
        image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
        imagePullPolicy: {{ .Values.image.pullPolicy }}
        ports:
        - containerPort: {{ .Values.service.port }}
        resources:
          {{- toYaml .Values.resources | nindent 10 }}
```

## 实战：使用 Helm 部署一个应用

### 1. 添加仓库并搜索

```bash
# 添加 Bitnami 仓库
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# 搜索 nginx Chart
helm search repo bitnami/nginx
```

### 2. 查看 Chart 默认配置

```bash
# 查看 values
helm show values bitnami/nginx
```

### 3. 使用自定义 values 部署

创建一个 `my-values.yaml` 文件：

```yaml
replicaCount: 3

image:
  tag: "1.23"

service:
  type: NodePort
  port: 8080

resources:
  limits:
    cpu: 500m
    memory: 512Mi
```

执行部署：

```bash
helm install my-nginx bitnami/nginx -f my-values.yaml -n default
```

### 4. 查看 Release 状态

```bash
# 列出 Release
helm list

# 查看 Release 配置值
helm get values my-nginx

# 查看生成的 K8s 资源
helm get manifest my-nginx
```

### 5. 升级与回滚

```bash
# 升级（修改副本数）
helm upgrade my-nginx bitnami/nginx --set replicaCount=5 -n default

# 查看版本历史
helm history my-nginx

# 回滚到版本 1
helm rollback my-nginx 1
```

### 6. 创建自己的 Chart

```bash
# 创建一个名为 myapp 的 Chart
helm create myapp

# 编辑 templates 和 values.yaml 后打包
helm package myapp -d ./packages/

# 部署自己的 Chart
helm install myapp-release ./myapp -f my-values.yaml
```

## 总结

本文我们学习了：

- **Helm** 是 K8s 生态的包管理器，解决了 YAML 管理和多环境部署的痛点
- **Chart** 是 Helm 的软件包格式，包含模板、默认值和元信息
- **Repository** 是 Chart 的分发仓库，**Release** 是 Chart 的一次部署实例
- 通过 **Go 模板引擎** 和 **values.yaml**，实现模板与配置的分离
- **`helm install` / `upgrade` / `rollback` / `uninstall`** 构成了完整的生命周期管理
- 实战中从添加仓库、自定义 values 到创建自己的 Chart，走通了 Helm 的完整工作流

Helm 是 K8s 生态中不可或缺的工具，它将部署从"手写 YAML"提升到了"声明式包管理"的层面。下一篇文章将进入 K8s 安全领域，介绍 RBAC 和 ServiceAccount。

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
- [K8s（十一）：集群安全基础 — RBAC 与 ServiceAccount]({{< relref "post/kubernetes-11" >}})
- [K8s（十二）：监控与日志 — 可观测性入门]({{< relref "post/kubernetes-12" >}})
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
