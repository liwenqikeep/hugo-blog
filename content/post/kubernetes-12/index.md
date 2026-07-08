---
title: "K8s（十二）：监控与日志 — 可观测性入门"
date: 2022-04-23
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "监控", "Prometheus", "日志"]
toc: true
---

集群搭建好了，应用也部署上去了，一切运转正常——但你怎么知道的？如果有一天凌晨三点网站挂了，你是登录服务器一个一个敲 `docker logs`，还是有一个统一的仪表盘告诉你哪里出了问题？这就是**可观测性**要解决的问题。

<!--more-->

## 前言：可观测性的三支柱

在云原生领域，可观测性通常由三个支柱构成：

| 支柱 | 英文 | 说明 | 类比 |
|------|------|------|------|
| **指标** | Metrics | 聚合的、时序化的数值数据 | 汽车的仪表盘（速度、油量） |
| **日志** | Logs | 离散的、带时间戳的文本记录 | 行车记录仪（事件详情） |
| **追踪** | Traces | 一次请求跨多个服务的调用链路 | 包裹追踪（从发货到签收） |

对于入门阶段，本文重点覆盖**指标**和**日志**——这是日常运维中最常用到的能力。

## 资源监控：Metrics Server 与 kubectl top

想快速查看 Pod 的 CPU 和内存使用情况，`kubectl top` 是最直观的命令。但这条命令依赖 **Metrics Server**，而 Metrics Server 默认不会随集群安装。

### 安装 Metrics Server

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

如果 kubeadm 安装的集群证书不是标准 CA 签发的，可能需要加 `--kubelet-insecure-tls` 参数：

```bash
# 编辑 Deployment
kubectl edit deployment metrics-server -n kube-system
```

在 `spec.template.spec.containers[0].args` 中添加：

```yaml
- --kubelet-insecure-tls
```

### 使用 kubectl top

安装 Metrics Server 后，`top` 命令就能用了：

```bash
# 查看节点资源使用
kubectl top node

# 查看 Pod 资源使用（默认当前 Namespace）
kubectl top pod

# 查看所有 Namespace 的 Pod
kubectl top pod --all-namespaces

# 查看带标签筛选的 Pod
kubectl top pod -l app=nginx

# 查看容器级别资源
kubectl top pod my-pod --containers
```

示例输出：

```text
NAME       CPU(cores)   MEMORY(bytes)
node-1     482m         2045Mi
node-2     356m         1890Mi
node-3     278m         1765Mi
```

`m` 代表毫核（千分之一核），`482m` 约等于半个 CPU 核心。

### 使用 kubectl top 排查问题

```bash
# 发现某个 Pod CPU 异常高
kubectl top pod -n production | sort -k2 -nr | head -5

# 对比资源请求和实际使用
kubectl get pod my-pod -o json | jq '.spec.containers[0].resources.requests'
kubectl top pod my-pod
```

## 日志查看：kubectl logs

### 基本用法

```bash
# 查看 Pod 日志
kubectl logs my-pod

# 实时追踪日志（类似 tail -f）
kubectl logs -f my-pod

# 查看多容器 Pod 中的指定容器
kubectl logs my-pod -c my-container

# 查看最近 N 条日志
kubectl logs --tail=100 my-pod

# 查看最近一段时间内的日志
kubectl logs --since=1h my-pod
```

### 查看崩溃前日志

这是最实用的功能之一。如果 Pod 不断崩溃重启，当前 Pod 可能还没有日志输出，但之前的实例中可能记录了错误信息：

```bash
kubectl logs my-pod --previous
```

`--previous` 会读取上一个 Pod 实例的标准输出，让你看到导致崩溃的原因。

### 多 Pod 日志聚合

```bash
# 查看 Deployment 下所有 Pod 的日志
kubectl logs deployment/my-deployment

# 通过标签选择多个 Pod
kubectl logs -l app=nginx --tail=100

# 结合 -f 追踪多个 Pod（Ctrl+C 退出）
kubectl logs -f -l app=nginx
```

### 日志输出格式

默认日志是纯文本。如果容器输出的是 JSON 格式日志（很多应用框架都支持），可以结合 `jq` 等工具处理：

```bash
# 应用输出 JSON 格式日志
kubectl logs my-app | jq '.message'
```

## 事件查看：kubectl get events

事件（Events）记录了集群中发生的重要状态变化——Pod 被调度、拉取镜像失败、健康检查不通过等。事件比日志更偏"系统侧"，是排查问题的第一站。

```bash
# 查看当前 Namespace 事件
kubectl get events

# 按时间排序（最新在最前）
kubectl get events --sort-by='.lastTimestamp'

# 实时监控事件（类似 watch）
kubectl get events --sort-by='.lastTimestamp' -w

# 查看特定资源的事件
kubectl describe pod my-pod
```

示例输出：

```text
LAST SEEN   TYPE      REASON             OBJECT                 MESSAGE
5m          Normal    Scheduled          pod/my-pod             Successfully assigned...
3m          Normal    Pulled             pod/my-pod             Container image "nginx" already present
2m          Normal    Created            pod/my-pod             Created container nginx
2m          Normal    Started            pod/my-pod             Started container nginx
1m          Warning   Unhealthy          pod/my-pod             Readiness probe failed: ...
```

### 常用事件排查场景

```bash
# 查找所有 Warning 级别事件
kubectl get events --field-selector type=Warning

# 查找特定 Pod 相关的事件
kubectl get events --field-selector involvedObject.name=my-pod

# 查找拉取镜像失败的事件
kubectl get events --field-selector reason=FailedToPullImage
```

## Prometheus + Grafana 生态简介

`kubectl top` 和 `kubectl logs` 适合应急排查，但要构建系统化的可观测性体系，业界标准方案是 **Prometheus + Grafana**。

### Prometheus

Prometheus 是一个开源的监控和告警系统，其核心是**拉取（Pull）模型**——定期从目标（Target）抓取指标数据，并存储在时序数据库中。

在 K8s 中，Prometheus 通过 **ServiceMonitor** 或 **PodMonitor** 自动发现目标，无需手动配置每个 Pod 的地址。

### Grafana

Grafana 是数据可视化平台，支持 Prometheus 等多种数据源。通过仪表盘（Dashboard），你可以将各种指标以图表的形式呈现。

### 部署 Prometheus Stack（kube-prometheus-stack）

社区提供了一个完整的 Helm Chart，一键部署 Prometheus + Grafana + AlertManager + 节点监控：

```bash
# 添加 Prometheus 社区仓库
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# 安装 kube-prometheus-stack
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring --create-namespace
```

部署成功后包含以下组件：

| 组件 | 用途 |
|------|------|
| prometheus-server | 指标采集与存储 |
| grafana | 可视化仪表盘 |
| alertmanager | 告警管理 |
| node-exporter | 节点资源指标 |
| kube-state-metrics | 集群对象状态指标 |

### 访问 Grafana

```bash
# 获取初始 admin 密码
kubectl get secret prometheus-grafana -n monitoring -o jsonpath="{.data.admin-password}" | base64 -d

# 端口转发
kubectl port-forward -n monitoring svc/prometheus-grafana 3000:80
```

浏览器打开 `http://localhost:3000`，用户名 `admin`，密码就是上面获取的值。

Grafana 默认预置了多个仪表盘：
- **Kubernetes / Compute Resources / Namespace (Pods)**：查看各 Namespace 的资源使用
- **Kubernetes / API Server**：API Server 性能监控
- **Node Exporter / Full**：节点级别资源监控

### 常见告警规则示例

Prometheus 的告警规则在 `prometheus-alert-rules` ConfigMap 中配置：

```yaml
groups:
- name: kubernetes-pods
  rules:
  - alert: PodCrashLooping
    expr: rate(kube_pod_container_status_restarts_total[5m]) > 0
    for: 1m
    labels:
      severity: critical
    annotations:
      summary: "Pod {{ $labels.pod }} is crash looping"
```

当 Pod 在 5 分钟内重启速率 > 0 时触发告警，AlertManager 会将告警推送到钉钉、邮件、Slack 等渠道。

## 总结

本文我们学习了：

- 可观测性的**三支柱**：指标（Metrics）、日志（Logs）、追踪（Traces）
- **Metrics Server** 提供节点和 Pod 级别的资源监控，配合 `kubectl top` 快速查看资源使用
- **kubectl logs** 查看容器日志，`--previous` 查看崩溃前日志，`-f` 实时追踪
- **kubectl get events** 查看集群事件，是排查问题的第一站
- **Prometheus + Grafana** 是 K8s 可观测性的事实标准，`kube-prometheus-stack` 一键部署
- 告警规则让异常能够被及时发现和通知

有了这些工具，你就可以从"被动等待用户报 Bug"转变为"主动发现异常"。下一篇文章也是本系列的收官之作，我们将汇总生产环境中的最佳实践。

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
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
