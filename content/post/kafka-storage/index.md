---
title: "Kafka（五）：存储机制"
date: 2018-08-11
draft: false
categories: ["消息队列"]
tags: ["Kafka", "存储", "日志", "分段", "索引", "零拷贝"]
toc: true
---

## 前言

Kafka 之所以能实现极高的吞吐量，核心在于其**磁盘顺序写入**和**零拷贝读取**的存储设计。与传统的"内存快照"缓存不同，Kafka 的消息直接写入磁盘并充分利用顺序 IO 的特性。

本文深入 Kafka 的存储结构——日志分段、索引文件、消息格式的演进，以及零拷贝的实现原理。

<!--more-->

## 一、日志存储结构

### 1.1 物理存储布局

```
Kafka 的日志存储在配置的 log.dirs 目录下。

/data/kafka/logs/
  ├── orders-0/                        <-- Topic=orders, Partition=0
  │     ├── 00000000000000000000.log    <-- 消息数据（1GB 后滚动新文件）
  │     ├── 00000000000000000000.index  <-- 偏移量 → 物理位置索引
  │     ├── 00000000000000000000.timeindex  <-- 时间戳 → 偏移量索引
  │     ├── 00000000000000102435.log    <-- 第二个分段
  │     ├── 00000000000000102435.index
  │     └── 00000000000000102435.timeindex
  │
  ├── orders-1/                        <-- Topic=orders, Partition=1
  │     └── ...
  │
  ├── __consumer_offsets/              <-- 消费者位移（内部 Topic）
  │     └── ...
  │
  └── __transaction_state/             <-- 事务状态（内部 Topic）
        └── ...
```

### 1.2 LogSegment——日志分段

```
每个 Partition 的日志被切分为多个 LogSegment。

LogSegment 的构成：
├── .log 文件：存储实际的消息数据
├── .index 文件：偏移量索引（稀疏，默认每 4KB 建立一条索引）
└── .timeindex 文件：时间戳索引

滚动条件（满足任一即创建新分段）：
1. log.segment.bytes（默认 1GB）——文件达到大小上限
2. log.roll.ms / log.roll.hours——达到时间上限（默认 7 天）
```

```properties
# 分段配置
log.segment.bytes = 1073741824      # 1GB 滚动
log.roll.hours = 168                # 7 天滚动
log.index.size.max.bytes = 10485760 # 索引文件最大 10MB
log.index.interval.bytes = 4096     # 每 4KB 数据建一条索引
```

---

## 二、索引机制

### 2.1 偏移量索引（.index）

```
偏移量索引是稀疏索引——不是每条消息都建索引，而是每隔一定字节建一条。

索引结构（内存映射，MappedByteBuffer）：
┌────────────────┬────────────────┐
│ relativeOffset  │ physicalPosition │
├────────────────┼────────────────┤
│ 0              │ 0               │
│ 3              │ 5000            │
│ 7              │ 12000           │
│ 12             │ 20000           │
└────────────────┴────────────────┘

查找过程（二分查找）：
1. 在索引文件中二分查找 ≤ 目标 offset 的最近的索引项
2. 拿到 physicalPosition
3. 从该位置开始顺序扫描 .log 文件直到找到目标消息
```

```
例如：查找 offset=10 的消息

二分查找索引文件找到 relativeOffset=7 的条目
→ physicalPosition=12000
→ 从 .log 文件的 12000 位置开始顺序扫描
→ 扫描 offset 8, 9, 10 → 找到目标消息
```

### 2.2 时间戳索引（.timeindex）

```
用于按时间戳查找消息（如从某个时间点开始消费）。

时间戳索引结构：
┌──────────────────┬────────────────┐
│ timestamp         │ relativeOffset │
├──────────────────┼────────────────┤
│ 1700000000000    │ 0              │
│ 1700000010000    │ 3              │
│ 1700000020000    │ 7              │
│ 1700000030000    │ 12             │
└──────────────────┴────────────────┘

查找过程：
1. 在 timeindex 中二分查找 ≤ 目标时间戳的最近条目
2. 拿到对应的 relativeOffset
3. 再用偏移量索引找到物理位置
```

---

## 三、消息格式

### 3.1 消息格式演进

```
Kafka 的消息格式经历了多个版本的演进：

v0（0.8.x）：
  crc + magic + attributes + key + value

v1（0.10.x）：
  v0 基础上增加 timestamp

v2（0.11.x，当前主流）：
  引入 RecordBatch，消息更紧凑
  支持事务和幂等性
  压缩效率更高
```

### 3.2 v2 消息格式

```
RecordBatch（消息批次）结构：
┌─────────────────────────────────┐
│         Batch Header            │ 61 字节
│  firstOffset, length, CRC,      │
│  partitionLeaderEpoch, magic    │
├─────────────────────────────────┤
│         Record 0                │
│  长度(key+value+headers)        │
│  时间戳、偏移量增量             │
├─────────────────────────────────┤
│         Record 1                │
├─────────────────────────────────┤
│         ...                     │
└─────────────────────────────────┘

Record v2 相比 v1 的优化：
- 时间戳存储为相对值（增量编码）→ 节省空间
- 偏移量存储为相对值（增量编码）→ 节省空间
- 消息头长度更紧凑（每条消息减少 8-15 字节）
- 支持 headers（自定义键值对）
```

---

## 四、零拷贝

### 4.1 传统 IO vs 零拷贝

```
传统 IO 读取文件并发送网络：
  磁盘 → 内核缓冲区 → 用户空间 → 内核 Socket 缓冲区 → 网络
  共 4 次上下文切换 + 4 次数据拷贝

零拷贝（sendfile）：  
  磁盘 → 内核缓冲区 → （DMA）→ 网卡
  共 2 次上下文切换 + 3 次数据拷贝（省去了用户空间的拷贝）
```

```
Kafka 利用 Java 的 FileChannel.transferTo() 实现零拷贝。

消费者读取消息时的路径：
1. 从磁盘读取 .log 文件到操作系统的 PageCache（第一次）
2. 后续访问直接命中 PageCache（第二次后几乎零磁盘 IO）
3. 通过 sendfile 直接从 PageCache 发送到网卡
```

### 4.2 零拷贝的性能优势

```
传统模式：
  磁盘 → 内核缓冲区 → 应用缓冲区 → Socket 缓冲区 → 网络
  文件大小 1GB → 经过多次拷贝 → 吞吐量受限

零拷贝模式：
  磁盘 → 内核缓冲区 →（DMA）→ 网卡
  文件大小 1GB → 直接发送 → 接近网卡上限

这就是 Kafka 能支撑单机百万 TPS 的核心原因之一。
```

---

## 五、磁盘空间管理

### 5.1 日志清理策略

```properties
# 策略一：删除（delete）—— 默认
log.cleanup.policy = delete
log.retention.hours = 168           # 保留 7 天

# 删除机制：根据日志分段文件的最后修改时间
# 超过 retention 时间的分段文件被删除

# 策略二：压缩（compact）
log.cleanup.policy = compact
# 保留每个 key 的最新版本
# 适合"变更日志"类场景（如数据库的 binlog 同步）
```

### 5.2 监控磁盘使用

```bash
# 查看各 Topic 的磁盘占用
du -sh /data/kafka/logs/orders-*
du -sh /data/kafka/logs/*--*

# 查看日志删除情况（Kafka 日志）
tail -f /data/kafka/logs/server.log | grep "cleaner"

# 调整保留时间
bin/kafka-configs.sh --alter \
    --entity-type topics \
    --entity-name orders \
    --add-config retention.ms=259200000  # 3 天
```

---

## 六、高吞吐量的根源

```
Kafka 高吞吐量的六大设计：

1. 顺序写磁盘
   消息追加到日志末尾，不是随机写
   顺序 IO 速度 ≈ 内存 IO 的 1/3

2. 零拷贝（sendfile）
   读取消息时直接从 PageCache 到网卡
   省去用户空间的数据拷贝

3. 批量操作
   生产者批量发送、消费者批量拉取
   减少网络往返次数

4. 压缩
   批量压缩（gzip/snappy/lz4/zstd）
   提高网络利用率和存储效率

5. 稀疏索引
   不建每条消息的索引，减少内存消耗
   二分查找 + 顺序扫描

6. PageCache 利用
   不自己做缓存管理，充分利用操作系统 PageCache
   读写都优先命中 PageCache
```

---

## 七、总结

### 存储结构速记

```
Topic → Partition → LogSegment(1GB)
  ├── .log        — 消息数据（顺序写）
  ├── .index      — 偏移量索引（稀疏）
  └── .timeindex  — 时间戳索引
```

### 数据流路径

```
写入：Producer → Network → PageCache → 磁盘（顺序写）
读取：磁盘 → PageCache → sendfile → 网卡

核心：顺序写 + 零拷贝
```

---

**上一篇：** [Kafka（四）：可靠性保证]({{< relref "post/kafka-reliability" >}})

**系列索引：**
- [Kafka（一）：核心概念与架构]({{< relref "post/kafka-architecture" >}})
- [Kafka（二）：生产者原理]({{< relref "post/kafka-producer" >}})
- [Kafka（三）：消费者原理]({{< relref "post/kafka-consumer" >}})
- [Kafka（四）：可靠性保证]({{< relref "post/kafka-reliability" >}})
- [Kafka（五）：存储机制]({{< relref "post/kafka-storage" >}})
