---
title: "分布式系统设计（三）：一致性算法 Paxos/Raft"
date: 2018-08-27
draft: false
categories: ["分布式"]
tags: ["分布式系统", "Paxos", "Raft", "一致性算法", "领导者选举"]
toc: true
---

## 前言

一致性算法是分布式系统的基石。它们解决的核心问题是：**在多个节点之间，如何就某个值达成一致？** 即使其中部分节点故障或网络异常。

Paxos 和 Raft 是最著名的两个一致性算法。Paxos 更理论化，Raft 更工程化。

<!--more-->

## 一、一致性问题

### 1.1 问题定义

```
三个节点 A、B、C，需要就一个值达成一致。

挑战：
├── 节点可能宕机
├── 网络可能延迟或中断
└── 消息可能乱序或丢失

一致性算法的要求：
├── 安全性（Safety）：所有节点最终达成相同的值
└── 活性（Liveness）：最终一定能达成一致
```

---

## 二、Paxos

Paxos 是 Leslie Lamport 在 1990 年提出的一致性算法，被公认为最难理解的算法之一。

### 2.1 角色

```
Proposer（提议者）：提出提案
Acceptor（接受者）：对提案进行投票
Learner（学习者）：学习已达成一致的提案

一个节点可以同时扮演多个角色。
```

### 2.2 Basic Paxos 的两阶段

```
第一阶段：Prepare（准备）
    Proposer → 所有 Acceptor：Prepare(N)
    Acceptor → Proposer：Promise(N, 已接受的提案号)

第二阶段：Accept（接受）  
    Proposer → 所有 Acceptor：Accept(N, Value)
    Acceptor → Proposer：Accepted(N, Value)

Proposer
    │
    ├── Phase 1a: Prepare(N=1) ──────────▶ Acceptors
    │◀── Phase 1b: Promise(N=1) ──────────│
    │                                      │
    ├── Phase 2a: Accept(1, V1) ──────────▶ Acceptors
    │◀── Phase 2b: Accepted(1, V1) ───────│
    │                                      │
    ▼ 达成一致
```

**冲突场景：**

```
两个 Proposer 同时提案：

P1: Prepare(1) → A,B,C → 获得多数 Promise(1)
P2: Prepare(2) → A,B,C → A,B 已 Promise(1)，C Promise(2)
P1: Accept(1, V1) → A,B 接受，C 拒绝（已 Promise(2)）→ 未达成多数
P2: Accept(2, V2) → A,B,C → 多数接受 V2

结论：高编号的提案胜出。
```

### 2.3 Multi Paxos

Basic Paxos 每次提案需要两轮 RTT，效率低。Multi Paxos 通过选举一个 Leader 来优化：

```
选举出 Leader 后：
Leader 发起提案只需要一轮 Accept（省略 Prepare）
因为 Leader 知道没有比自己编号更高的提案

Role：只有一个 Proposer（Leader）活跃
```

---

## 三、Raft

Raft 是 Diego Ongaro 在 2014 年提出的，目标是**易于理解**。它将一致性问题分解为三个子问题：**领导者选举**、**日志复制**、**安全性**。

### 3.1 角色

```
Leader（领导者）：处理所有客户端请求，管理日志复制
Follower（跟随者）：被动接受 Leader 的日志
Candidate（候选者）：选举过程中的过渡角色

状态机：
                         超时后发起选举
    ┌──────────┐  ─────────────────────▶ ┌──────────┐
    │ Follower  │                         │ Candidate │
    │           │◀─────────────────────── │           │
    └──────────┘  发现 Leader 或新任期    └──────────┘
         ▲                                    │
         │       获得多数选票                  │
         └────────────────────────────────────┘
```

### 3.2 领导者选举

```
Raft 使用任期（Term）和心跳超时进行选举。

选举过程：
1. Follower 在 election timeout 内未收到 Leader 心跳
2. Follower 转为 Candidate，term +1
3. Candidate 给自己投票，并向其他节点请求投票
4. Candidate 获得多数（N/2+1）选票 → 成为 Leader
5. 新 Leader 开始发送心跳，维持 Leader 地位

心跳超时：
默认 150-300ms（随机值，防止多个节点同时发起选举）
```

### 3.3 日志复制

```
客户端请求到达 Leader：
1. Leader 将请求追加到自己的日志中
2. Leader 并行发送 AppendEntries 到所有 Follower
3. Follower 追加日志后回复 Leader
4. Leader 收到多数确认后，提交日志
5. Leader 通知 Follower 该日志已提交

日志结构：
Term 1: [x←3] [y←5]
Term 2: [z←7] [x←8]
         ↑已提交   ↑未提交
```

### 3.4 安全性保证

```
Raft 的安全性通过以下机制保证：

1. 选举限制
   Candidate 的日志必须至少和多数节点一样新才能当选

2. 日志匹配
   AppendEntries 包含前一条日志的 term 和 index
   Follower 只追加匹配的日志

3. 提交规则
   Leader 只提交当前任期的日志
   之前的日志通过当前任期日志的提交间接提交
```

### 3.5 Raft 动画演示（推荐）

```
推荐在线演示：https://raft.github.io/
可以通过动画直观理解选举和日志复制的过程。
```

---

## 四、Paxos vs Raft

| 对比 | Paxos | Raft |
|------|-------|------|
| 理解难度 | 极高 | 中等 |
| 角色 | Proposer/Acceptor/Learner | Leader/Follower/Candidate |
| 选举 | 复杂（Multi Paxos） | 直观（任期 + 心跳超时）|
| 日志管理 | 无明确机制 | 日志复制 + 提交规则 |
| 工程实现 | 极少直接实现 | ZooKeeper(Zab)、etcd(Raft) |
| 应用 | Chubby（Google）| etcd、Consul、TiKV |

---

## 五、一致性算法的工程应用

| 系统 | 算法 | 用途 |
|------|------|------|
| ZooKeeper | Zab（类似 Paxos）| 配置管理、服务发现 |
| etcd | Raft | Kubernetes 数据存储 |
| Consul | Raft | 服务发现 + 配置 |
| TiKV | Raft | 分布式 KV 存储 |
| Chubby | Paxos | Google 分布式锁服务 |

### 工程实现的关键细节

```
1. 磁盘持久化
   日志必须落盘后才回复确认
   重启后从日志恢复状态

2. 快照（Snapshot）
   日志不能无限增长
   定期打快照，截断日志

3. 成员变更
   增删节点需要特殊处理
   使用联合共识（Joint Consensus）或单节点变更

4. 预投票（Pre-Vote）
   防止网络分区恢复后的"脑裂"选主
```

---

## 六、总结

### 一致性算法核心概念

| 算法 | 核心思想 | 工程代表 |
|------|---------|---------|
| Paxos | 两阶段 + 多数投票 | Chubby |
| Raft | 选举 + 日志复制 + 多数提交 | etcd、Consul |

### 多数派（Quorum）

```
所有一致性算法的核心：多数派（N/2 + 1）

多数派的含义：
├── 任意两个多数派必然有交集
├── 保证只有一个 Leader 被选出
└── 保证只有一条日志被提交
```

**相关阅读：**
- [分布式系统设计（二）：分布式理论 CAP/BASE/一致性]({{< relref "post/distributed-system-cap-base" >}})
- [分布式系统设计（四）：分布式组件]({{< relref "post/distributed-system-components" >}})
