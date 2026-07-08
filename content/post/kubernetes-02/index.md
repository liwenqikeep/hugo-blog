---
title: "K8s（二）：使用 kubeadm 搭建 K8s 集群实战"
date: 2022-04-03
draft: false
categories: ["容器化"]
tags: ["Kubernetes", "k8s", "kubeadm", "集群搭建"]
toc: true
---

## 前言：为什么选择 kubeadm

搭建 Kubernetes 集群有多种方式，各有适用场景：

| 方式 | 适用场景 | 复杂度 |
|------|----------|--------|
| **Minikube** | 本地单节点测试 | 低 |
| **kubeadm** | 生产级多节点集群 | 中 |
| **二进制部署** | 定制化需求 | 高 |
| **托管服务**（EKS/AKS/GKE） | 云上生产环境 | 低 |

对于初学者来说，**kubeadm** 是最佳选择。它是 K8s 官方提供的集群搭建工具，既能让你理解集群的底层原理，又不需要手动下载和配置每个组件。

本文将以两台 Ubuntu 20.04 虚拟机为例，搭建一个包含 1 个 Master 节点和 1 个 Worker 节点的最小集群。

<!--more-->

## 环境准备

### 硬件要求

| 节点 | 配置要求 |
|------|----------|
| Master 节点 | 2 核 CPU、2GB 内存、20GB 磁盘 |
| Worker 节点 | 2 核 CPU、2GB 内存、20GB 磁盘 |

> **提示**：如果你是本地实验，建议使用 Vagrant + VirtualBox 或 Multipass 创建虚拟机。云上则可以直接购买 2 台 ECS/EC2 实例。

### 操作系统

本文以 **Ubuntu 20.04 LTS** 为例。其他 Linux 发行版（CentOS 7、Debian 等）步骤类似，仅包管理器命令不同。

在所有节点上执行以下操作。

### 1. 关闭 swap

Kubernetes 要求关闭 swap，否则 kubelet 无法正常启动。

```bash
sudo swapoff -a
# 永久关闭：注释 /etc/fstab 中的 swap 条目
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab
```

### 2. 配置内核参数

```bash
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter

cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sudo sysctl --system
```

### 3. 安装容器运行时

Kubernetes 从 1.24 版本开始移除了对 Docker 的直接支持，推荐使用 **containerd** 作为容器运行时。本文使用 containerd。

```bash
# 安装 containerd
sudo apt-get update
sudo apt-get install -y containerd

# 生成默认配置
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml

# 启用 SystemdCgroup
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml

# 重启 containerd
sudo systemctl restart containerd
sudo systemctl enable containerd
```

### 4. 安装 kubeadm、kubelet、kubectl

```bash
# 添加 K8s 官方 APT 源
sudo apt-get update
sudo apt-get install -y apt-transport-https ca-certificates curl
sudo curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-archive-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-archive-keyring.gpg] https://apt.kubernetes.io/ kubernetes-xenial main" | sudo tee /etc/apt/sources.list.d/kubernetes.list

# 安装指定版本（保持版本一致）
sudo apt-get update
sudo apt-get install -y kubelet=1.28.0-00 kubeadm=1.28.0-00 kubectl=1.28.0-00
sudo apt-mark hold kubelet kubeadm kubectl
```

> **注意**：国内用户如果无法访问 Google 的软件源，可以使用阿里云或中科大的镜像源。

## 初始化 Master 节点

在 **Master 节点**上执行。

```bash
# 初始化集群，指定 Pod 网段（使用 Calico 则用 192.168.0.0/16）
sudo kubeadm init \
  --pod-network-cidr=192.168.0.0/16 \
  --apiserver-advertise-address=<Master_节点_IP>
```

执行成功后，你会看到类似如下的输出：

```text
Your Kubernetes control-plane has been initialized successfully!
```
To start using your cluster, you need to run the following as a regular user:

  mkdir -p $HOME/.kube
  sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
  sudo chown $(id -u):$(id -g) $HOME/.kube/config

You can now join any number of worker nodes by running the following on each node:

kubeadm join <Master_IP>:6443 --token <token> --discovery-token-ca-cert-hash sha256:<hash>
```

### 配置 kubeconfig

按照输出提示，配置 kubectl 命令的访问权限：

```bash
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

### 安装 CNI 网络插件

集群初始化后，Pod 之间还不能通信，需要安装一个 CNI 网络插件。这里以 **Calico** 为例：

```bash
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.26.1/manifests/tigera-operator.yaml
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.26.1/manifests/custom-resources.yaml
```

等待 Calico 的 Pod 全部运行起来：

```bash
kubectl get pods -n calico-system -w
```

如果你选择 **Flannel**，可以用以下命令：

```bash
kubectl apply -f https://raw.githubusercontent.com/flannel-io/flannel/master/Documentation/kube-flannel.yml
```

## 加入 Worker 节点

在 **Worker 节点**上执行 Master 节点初始化完成后输出的 `kubeadm join` 命令：

```bash
sudo kubeadm join <Master_IP>:6443 --token <token> --discovery-token-ca-cert-hash sha256:<hash>
```

> **提示**：如果你的 token 过期了，可以在 Master 节点上重新生成：
> ```bash
> # 生成新的 token
> kubeadm token create
> # 获取 CA 证书 hash
> openssl x509 -pubkey -in /etc/kubernetes/pki/ca.crt | openssl rsa -pubin -outform der 2>/dev/null | openssl dgst -sha256 -hex | sed 's/^.* //'
> ```

## 集群验证

在 Master 节点上执行以下命令验证集群状态：

```bash
# 查看所有节点
kubectl get nodes

# 输出应类似：
# NAME          STATUS   ROLES           AGE   VERSION
# k8s-master    Ready    control-plane   5m    v1.28.0
# k8s-worker1   Ready    <none>          2m    v1.28.0

# 查看系统组件运行状态
kubectl get pods -n kube-system

# 查看集群信息
kubectl cluster-info
```

如果所有节点状态都是 `Ready`，系统组件 Pod 都在 `Running` 状态，恭喜你，集群搭建成功！

## 常见问题排查

### 1. kubelet 无法启动

```bash
# 查看 kubelet 日志
sudo journalctl -u kubelet -f

# 常见原因：swap 未关闭、容器运行时未配置好
```

### 2. 节点状态为 NotReady

```bash
# 检查节点详情
kubectl describe node <node-name>

# 常见原因：CNI 网络插件未安装或未正常运行
```

### 3. CoreDNS 处于 Pending 状态

通常是因为 CNI 网络插件没有安装。安装 Calico 或 Flannel 后会自动解决。

### 4. 证书问题

```bash
# 如果证书过期，更新证书
sudo kubeadm certs renew all
```

## 总结

本文我们使用 kubeadm 成功搭建了一个最小化的 Kubernetes 集群，包含一个 Master 节点和一个 Worker 节点。回顾整个流程：

1. **环境准备**：关闭 swap、配置内核参数、安装 containerd
2. **安装工具**：安装 kubeadm、kubelet、kubectl
3. **初始化集群**：`kubeadm init` + 安装 CNI 网络插件
4. **加入节点**：`kubeadm join`
5. **验证集群**：`kubectl get nodes`

有了一个可用的集群之后，下一篇文章我们将深入 Kubernetes 的核心概念——Pod，学习如何部署和管理容器化应用。

---

## 系列文章

- [K8s（一）：从零认识 Kubernetes — 架构与核心概念]({{< relref "post/kubernetes-01" >}})
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
