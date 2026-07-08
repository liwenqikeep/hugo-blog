---
title: "K8s（一）：从零认识 Kubernetes — 架构与核心概念"
date: 2022-04-01
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "架构", "容器编排"]
toc: true
---

## 前言：K8s 是什么、为什么需要它

在传统的应用部署时代，我们通常把应用直接跑在一台物理服务器或虚拟机上。随着业务增长，这种方式逐渐暴露出几个痛点：

- **资源利用率低**：一台机器跑一个应用，CPU 和内存大量闲置。
- **扩缩容困难**：流量高峰期需要手动加机器，流程繁琐。
- **运维成本高**：服务之间依赖关系复杂，部署、升级、回滚都需要人工介入。
- **环境不一致**：开发环境能跑，测试环境出问题，生产环境又挂了。

**Kubernetes**（简称 K8s，因为 K 和 s 之间有 8 个字母）应运而生。它是一个开源的容器编排平台，能够自动化地完成容器的部署、伸缩、服务发现和负载均衡等工作。

简单来说，K8s 就像一个"云操作系统"——你告诉它"我要跑 3 个 Nginx 容器，每个分配 256MB 内存"，它会自动帮你找到合适的机器去运行，并保证这些容器一直处于预期的状态。

<!--more-->

## 发展历史：从 Borg 到 Kubernetes，CNCF 生态

Kubernetes 的故事要从 Google 内部的两套系统说起：

- **Borg**：Google 内部使用了十几年的集群管理系统，管理着数十亿个容器。
- **Omega**：Borg 的升级版，引入了更灵活的调度机制。

2014 年，Google 基于 Borg 的经验，开源了 Kubernetes 项目。2015 年，Google 联合 Linux 基金会成立了 **CNCF（Cloud Native Computing Foundation，云原生计算基金会）**，并将 Kubernetes 作为首个孵化项目捐赠给 CNCF。

如今，CNCF 生态已经极为庞大，涵盖了容器运行时（containerd）、服务网格（Istio）、监控（Prometheus）、日志（Fluentd）等数百个云原生项目，而 Kubernetes 处于这个生态的核心位置。

## 核心架构

一个标准的 Kubernetes 集群由 **Master 节点**（控制平面）和 **Worker 节点**（工作节点）组成。

### Master 节点组件

Master 节点负责集群的管理和控制，通常部署 3 个以实现高可用。

| 组件 | 作用 |
|------|------|
| **API Server** | 集群的入口，所有操作都通过 REST API 调用 API Server。它是集群的"前端"。 |
| **etcd** | 分布式键值存储，保存集群的所有状态信息（配置、数据等）。 |
| **Scheduler** | 调度器，负责将新创建的 Pod 分配到合适的 Worker 节点上。 |
| **Controller Manager** | 控制器管理器，运行各类控制器（如 Node Controller、Deployment Controller），确保集群的实际状态与期望状态一致。 |

### Worker 节点组件

Worker 节点是实际运行应用容器的机器。

| 组件 | 作用 |
|------|------|
| **kubelet** | 每个节点上的"代理"，负责管理 Pod 和容器的生命周期，与 API Server 通信。 |
| **kube-proxy** | 负责网络代理和负载均衡，维护节点上的网络规则。 |
| **容器运行时** | 实际运行容器的软件，如 containerd、CRI-O、Docker。 |

## 核心概念

### Pod

Pod 是 Kubernetes 中**最小的调度单元**。一个 Pod 可以包含一个或多个容器，这些容器共享网络和存储资源。你可以把 Pod 想象成一组"紧密耦合的应用进程"。

### Node

Node 就是集群中的一台机器（物理机或虚拟机），可以是 Master 节点或 Worker 节点。每个 Node 由 kubelet 管理。

### Namespace

Namespace（命名空间）用于将集群资源进行逻辑隔离。例如，你可以创建 `dev`、`staging`、`prod` 三个命名空间来隔离不同环境的资源。K8s 默认提供 `default`、`kube-system` 等命名空间。

### Cluster

Cluster（集群）是由一组 Master 节点和 Worker 节点组成的整体，是 K8s 运行的完整环境。

## 工作流程

当用户运行 `kubectl apply -f pod.yaml` 创建一个 Pod 时，背后经历了以下步骤：

1. **用户提交请求**：`kubectl` 将 YAML 文件转换为 API 请求，发送给 API Server。
2. **API Server 验证**：API Server 验证请求的合法性（权限、格式等），然后将 Pod 的信息写入 etcd。
3. **Scheduler 调度**：Scheduler 监听到一个新的 Pod（尚未绑定到 Node），根据资源需求、亲和性等策略，选择一个合适的 Worker 节点。
4. **节点上的 kubelet 执行**：被选中的 Worker 节点上的 kubelet 收到调度结果，通知容器运行时拉取镜像并启动容器。
5. **状态上报**：kubelet 持续监控 Pod 的状态，并定期上报给 API Server，更新到 etcd 中。

整个过程是**声明式**的——你只管描述"想要什么"，K8s 自己搞定"怎么做到"。

## 基础命令

K8s 的命令行工具是 `kubectl`，以下是一些最常用的命令：

```bash
# 查看集群节点
kubectl get nodes

# 查看所有 Pod（默认 default 命名空间）
kubectl get pods

# 查看所有命名空间
kubectl get namespaces

# 查看 kube-system 命名空间下的 Pod
kubectl get pods -n kube-system

# 查看集群信息
kubectl cluster-info
```

运行 `kubectl get nodes` 可以看到集群中有几个节点以及它们的状态。如果节点状态为 `Ready`，说明集群正常工作。

## 总结

本文我们从零认识了 Kubernetes 是什么、为什么需要它，了解了它的发展历史和核心架构。Kubernetes 的核心思想是**声明式管理**：你描述期望状态，系统自动维持这个状态。

关键要点回顾：

- **K8s** = 容器编排平台 = "云操作系统"
- **Master 节点**：API Server、etcd、Scheduler、Controller Manager
- **Worker 节点**：kubelet、kube-proxy、容器运行时
- **核心概念**：Pod（最小调度单元）、Node（集群节点）、Namespace（逻辑隔离）、Cluster（集群）
- **声明式工作流**：你描述期望状态，K8s 自动实现

下一篇文章，我们将动手实战——使用 kubeadm 搭建一个真实的 K8s 集群。

---

## 系列文章

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
- [K8s（十三）：生产实践与系列总结]({{< relref "post/kubernetes-13" >}})
