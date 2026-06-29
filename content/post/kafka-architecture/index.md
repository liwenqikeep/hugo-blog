---
title: "Kafka（一）：核心概念与架构"
date: 2018-08-03
draft: false
categories: ["消息队列"]
tags: ["Kafka", "消息队列", "架构", "Topic", "Partition", "Broker"]
toc: true
---

## 前言

Kafka 是 Apache 基金会旗下的分布式消息流平台，最初由 LinkedIn 开发，于 2011 年开源。与传统的消息队列不同，Kafka 在设计上更侧重于**高吞吐量**、**持久化存储**和**流式处理**。

本文从最基础的架构出发，逐步覆盖 Kafka 的核心概念和组件。

<!--more-->

## 一、Kafka 的整体架构

```
                    Producers（生产者）
                      │    │    │
                      ▼    ▼    ▼
              ┌───────────────────────┐
              │      Kafka Cluster     │
              │  ┌─────┐ ┌─────┐      │
              │  │Broker│ │Broker│ ...  │
              │  │      │ │      │      │
              │  │P0 P3│ │P1 P4│      │
              │  │  (Leader/Replica)   │
              │  └─────┘ └─────┘      │
              └───────────────────────┘
                      │    │    │
                      ▼    ▼    ▼
                    ┌──────────────┐
                    │   Zookeeper   │
                    │  (元数据管理)  │
                    └──────────────┘
                      │    │    │
                      ▼    ▼    ▼
                    Consumers（消费者）
                    Consumer Group
```

### 1.1 核心组件

| 组件 | 说明 |
|------|------|
| **Broker** | Kafka 服务器节点，一个集群由多个 Broker 组成 |
| **Topic** | 消息的主题类别，生产者向 Topic 发消息，消费者从 Topic 拉消息 |
| **Partition** | Topic 的分区，一个 Topic 有多个分区，每个分区有序 |
| **Producer** | 消息生产者 |
| **Consumer** | 消息消费者 |
| **Consumer Group** | 消费者组，组内消费者共同消费 Topic 的消息 |
| **ZooKeeper** | 负责集群元数据管理、Leader 选举（2.8+ 逐步移除）|

---

## 二、Topic 与 Partition

### 2.1 分区结构

```
Topic: orders
    │
    ├── Partition 0（分区 0）
    │     ┌────┬────┬────┬────┬────┬────┐
    │     │ msg0│ msg1│ msg2│ msg3│ msg4│    → 追加写入
    │     │offset│offset│offset│offset│offset│
    │     │ 0  │ 1  │ 2  │ 3  │ 4  │
    │     └────┴────┴────┴────┴────┴────┘
    │
    ├── Partition 1
    │     ┌────┬────┬────┬────┐
    │     │msg0│ msg1│ msg2│ msg3│
    │     │off0│off1│off2│off3│
    │     └────┴────┴────┴────┘
    │
    └── Partition 2
          ┌────┬────┬────┐
          │msg0│ msg1│ msg2│
          │off0│off1│off2│
          └────┴────┴────┘

每个 Partition 是：
- 有序的、不可变的消息序列
- 每个消息用 offset（偏移量）唯一标识
- 新消息追加到末尾（顺序写，高性能）
- 消息保留一定时间后自动删除（默认 7 天）
```

### 2.2 分区数设计

```bash
# 创建 Topic 时指定分区数
bin/kafka-topics.sh --create \
    --topic orders \
    --bootstrap-server localhost:9092 \
    --partitions 6 \
    --replication-factor 3

# 修改分区数（只能增加，不能减少）
bin/kafka-topics.sh --alter \
    --topic orders \
    --bootstrap-server localhost:9092 \
    --partitions 12

# 查看 Topic 详情
bin/kafka-topics.sh --describe \
    --topic orders \
    --bootstrap-server localhost:9092
```

**分区数选择建议：**

```
分区数 = max(生产者并行度, 消费者并行度)

参考：
- 分区数太少 → 吞吐量上不去（并行度不够）
- 分区数太多 → 文件句柄占用多、Leader 选举慢
- 建议：初期设 6-12，后续按需增加
- 注意：分区数只能增加不能减少
```

### 2.3 分区与消费者组

```
Topic (orders, 6 个分区)

同一消费者组内：
├── Group A (3 个消费者)
│     ├── Consumer A1 → Partition 0, 1
│     ├── Consumer A2 → Partition 2, 3
│     └── Consumer A3 → Partition 4, 5
│
│  每个分区只能被组内的一个消费者消费
│  消费者数 ≤ 分区数

不同消费者组之间独立消费：
├── Group A (3 个消费者) ← 消费全部 6 个分区
└── Group B (2 个消费者) ← 也消费全部 6 个分区
```

---

## 三、消息模型

### 3.1 点对点模式 vs 发布订阅模式

```
点对点（Queue）：同一消费者组内
  Producer → Topic → Partition → 组内一个消费者消费
  消息被消费后不会再次被同组消费

发布订阅（Pub/Sub）：不同消费者组
  Producer → Topic → Partition → GroupA 消费 → 也到 GroupB 消费
  每个消费者组都能收到完整的消息
```

### 3.2 消息结构

```java
// Kafka 消息的核心字段
public class ProducerRecord<K, V> {
    String topic;          // 主题
    Integer partition;     // 分区（可选，由分区器决定）
    K key;                 // 键（用于分区路由）
    V value;               // 值（消息体）
    Long timestamp;        // 时间戳
    Headers headers;       // 消息头
}
```

---

## 四、集群架构

### 4.1 Broker 与 Controller

```
在 Kafka 集群中，一个 Broker 被选为 Controller。
Controller 负责管理集群的元数据：
├── 管理每个 Partition 的 Leader/Replica 列表
├── 处理 Broker 上下线
├── 触发 Partition 的 Leader 选举
└── 更新元数据后通知其他 Broker
```

### 4.2 Leader 与 Replica

```
Topic: orders, Partition 0

Broker 1 (Leader)    ── 读写请求都经过 Leader
  └── 同步复制 ──▶  Broker 2 (Replica)
  └── 同步复制 ──▶  Broker 3 (Replica)

ISR（In-Sync Replica）：与 Leader 保持同步的副本集合
当 Leader 宕机时，从 ISR 中选举新的 Leader
```

### 4.3 ZooKeeper 的角色

```
Kafka 依赖 ZooKeeper 存储集群元数据：
├── Broker 注册 / 发现
├── Controller 选举
├── Topic 元数据
├── Consumer Group 的 offset（0.9+ 偏移量已存储在 Kafka 内部 Topic）
└── 集群配置

# Kafka 2.8+ 开始支持 KRaft 模式，逐步移除 ZooKeeper 依赖
```

---

## 五、基础操作

### 5.1 命令行操作

```bash
# 启动 ZooKeeper
bin/zookeeper-server-start.sh config/zookeeper.properties

# 启动 Kafka Broker
bin/kafka-server-start.sh config/server.properties

# 创建 Topic
bin/kafka-topics.sh --create \
    --topic test \
    --bootstrap-server localhost:9092 \
    --partitions 3 \
    --replication-factor 1

# 控制台生产者
bin/kafka-console-producer.sh \
    --topic test \
    --bootstrap-server localhost:9092

# 控制台消费者
bin/kafka-console-consumer.sh \
    --topic test \
    --bootstrap-server localhost:9092 \
    --from-beginning
```

### 5.2 Server 配置

```properties
# server.properties
broker.id=0
listeners=PLAINTEXT://0.0.0.0:9092
log.dirs=/data/kafka/logs
num.partitions=3
default.replication.factor=2
log.retention.hours=168
log.segment.bytes=1073741824
zookeeper.connect=localhost:2181
```

---

## 六、总结

### Kafka 核心特性

| 特性 | 说明 |
|------|------|
| 高吞吐量 | 顺序写磁盘、零拷贝、批量发送 |
| 持久化 | 消息落盘，可配置保留时间 |
| 高可用 | Partition 多副本，自动 Leader 选举 |
| 可扩展 | 在线增加 Broker、Topic 分区 |
| 顺序保证 | 单个 Partition 内消息有序 |
| 流处理 | 配合 Kafka Streams / ksqlDB |

### 基础概念速查

| 概念 | 一句话 |
|------|--------|
| Broker | Kafka 服务器 |
| Topic | 消息的分类 |
| Partition | Topic 的分片，有序消息序列 |
| Offset | 分区内消息的唯一标识 |
| Producer | 消息发送者 |
| Consumer | 消息接收者 |
| Consumer Group | 消费者组，组内协作消费 |

---

**下一篇：** [Kafka（二）：生产者原理]({{< relref "post/kafka-producer" >}})
