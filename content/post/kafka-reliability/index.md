---
title: "Kafka（四）：可靠性保证"
date: 2018-08-09
draft: false
categories: ["消息队列"]
tags: ["Kafka", "可靠性", "ISR", "ACK", "幂等性", "事务", "消息不丢失"]
toc: true
---

## 前言

消息可靠性是使用消息队列时最关心的问题。Kafka 提供了多层次的可靠性保证——从生产端到消费端，从副本机制到幂等性事务。理解每个环节的可靠性保证，才能设计出符合业务需求的方案。

本文从端到端的视角，分析消息从**发送 → 存储 → 消费**全链路的可靠性保证。

<!--more-->

## 一、消息丢失的可能性

```
消息丢失可能发生在三个环节：

1. 生产者 → Broker（发送阶段）
   ├── 网络故障导致消息未到达
   └── acks 配置不当（acks=0 或 acks=1 时 Leader 宕机）

2. Broker 端（存储阶段）
   ├── 副本未同步时 Leader 宕机
   └── 日志因磁盘故障被删除

3. Broker → 消费者（消费阶段）
   ├── 自动提交位移后消费者宕机（消息未处理完）
   └── 消费者处理异常但位移已提交
```

---

## 二、生产者端可靠性

### 2.1 acks 与重试

```java
// ★ 最可靠的发送配置
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
```

```
acks=all 时：
1. Producer 发送消息到 Leader
2. Leader 等待 ISR 中的所有副本写入成功
3. 所有 ISR 副本确认后，Leader 返回 ACK 给 Producer

ISR（In-Sync Replicas）：
- 与 Leader 保持同步的副本集合
- 由 replica.lag.time.max.ms（默认 30秒）控制
- 同步超时的副本被踢出 ISR
```

### 2.2 幂等性发送

```java
// 开启幂等性
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

// 幂等性的原理：
// 每个 Producer 有一个唯一的 Producer ID（PID）
// 每条消息有一个递增的 Sequence Number
// Broker 端根据 PID + Sequence Number 去重
// 即使同一条消息发送多次，Broker 只会保存一次

// 注意：幂等性只能保证单分区内的消息不重复
// 跨分区的 Exactly Once 需要事务
```

### 2.3 发送确认的三种模式

```java
// 方式一：发后即忘（不推荐）
producer.send(new ProducerRecord<>("topic", "value"));

// 方式二：同步等待确认（最可靠）
RecordMetadata metadata = producer.send(record).get();

// 方式三：异步回调（推荐）
producer.send(record, (metadata, exception) -> {
    if (exception != null) {
        // 记录失败消息，后续补偿
        log.error("发送失败", exception);
        saveToRetryQueue(record);
    }
});
```

### 2.4 不丢消息的生产者配置

```properties
# 生产端不丢消息配置
acks=all
enable.idempotence=true
retries=Integer.MAX_VALUE
max.in.flight.requests.per.connection=5
```

---

## 三、Broker 端可靠性

### 3.1 副本机制

```
Topic: orders, Partition 0

Broker 1 (Leader)     ← 读写都走 Leader
  │
  ├── Broker 2 (Replica, ISR)
  └── Broker 3 (Replica, ISR)

replication.factor = 3（副本数）
min.insync.replicas = 2（最小 ISR）
```

**关键配置：**

```properties
# Broker 端可靠性配置
default.replication.factor = 3       # 副本数（至少 3）
min.insync.replicas = 2              # 最小同步副本数
unclean.leader.election.enable = false  # 不允许非 ISR 副本成为 Leader
```

### 3.2 副本同步机制

```
同步过程：
1. Producer 发送消息到 Leader
2. Leader 写入本地日志
3. Follower 从 Leader 拉取消息（fetch 请求）
4. Follower 写入本地日志
5. Follower 向 Leader 发送确认
6. Leader 更新 HW（High Watermark）

HW（High Watermark）：
- 当前 ISR 中所有副本都同步到的位移
- 消费者只能消费 HW 之前的消息（确保消息已被所有 ISR 副本确认）
- 当 Leader 宕机时，新 Leader 从 HW 位置开始恢复

LEO（Log End Offset）：
- 每个副本的日志最新写入位置
- HW ≤ LEO
- 消费者 max(HW) = 所有 ISR 副本中最小的 LEO
```

### 3.3 Leader 选举

```
正常情况：Leader 从 ISR 中选举
  数据完整，不会丢失

非正常情况（unclean.leader.election.enable=true）：
  ISR 为空，从非 ISR 中选举
  数据可能丢失

生产环境建议：
  unclean.leader.election.enable=false
  宁可等待 ISR 中的副本恢复，也不接受数据丢失
```

---

## 四、消费端可靠性

### 4.1 手动提交位移

```java
// ★ 消费端不丢消息的关键

props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(100);
    
    for (ConsumerRecord<String, String> record : records) {
        try {
            process(record);  // ★ 先处理业务逻辑
            // 处理成功后才提交位移
        } catch (Exception e) {
            // 记录异常，稍后重试
            saveToRetryQueue(record);
        }
    }
    
    // ★ 所有消息处理完成后再提交位移
    consumer.commitSync();
}
```

### 4.2 幂等消费

消息重复消费是常态，业务处理需要保证幂等性：

```java
public void process(ConsumerRecord<String, String> record) {
    String messageId = record.key();  // 使用消息唯一 ID
    
    // 方案一：数据库唯一键去重
    String sql = "INSERT INTO processed_messages(id, content) VALUES(?, ?) " +
                 "ON DUPLICATE KEY UPDATE content=content";
    jdbcTemplate.update(sql, messageId, record.value());
    
    // 方案二：Redis 去重
    Boolean success = redisTemplate.opsForValue()
            .setIfAbsent("msg:" + messageId, "1", Duration.ofDays(7));
    if (Boolean.TRUE.equals(success)) {
        doProcess(record);  // 首次处理
    }
}
```

### 4.3 消费端的 Exactly Once

```java
// 事务消息消费（isolation.level = read_committed）
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

// 消费者 + 生产者的事务
// 常用于"消费-处理-发送"场景（如从 Kafka 读取后写入数据库）
consumer.subscribe(List.of("source-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(100);
    
    producer.beginTransaction();
    try {
        for (ConsumerRecord<String, String> record : records) {
            // 处理消息
            String result = process(record);
            // 发送到下游 Topic
            producer.send(new ProducerRecord<>("result-topic", result));
        }
        
        // 提交生产者和消费者的事务位移
        producer.sendOffsetsToTransaction(
                consumer.poll(0).position(record.partition()),
                consumer.groupMetadata());
        producer.commitTransaction();
    } catch (Exception e) {
        producer.abortTransaction();
    }
}
```

---

## 五、消息语义强度对比

| 语义 | 含义 | 配置 |
|------|------|------|
| At Most Once | 最多一次（可能丢消息）| acks=0 / 自动提交位移 |
| At Least Once | 至少一次（可能重复，不丢）| acks=all + 手动提交（推荐）|
| Exactly Once | 精确一次（不丢不重）| 幂等性 + 事务 + 手动提交 |

**生产环境推荐：至少一次（At Least Once）+ 业务幂等性**。

> 精确一次（Exactly Once）成本较高，除非业务对重复极端敏感（如金融交易），否则不推荐。

---

## 六、日志清理与数据安全

### 6.1 日志保留策略

```properties
# 保留策略（满足任一即清理）
log.retention.hours = 168              # 保留 7 天（默认）
log.retention.minutes = 10080          # 或按分钟（覆盖小时）
log.retention.bytes = -1               # 按总大小（-1 为不限制）
log.segment.bytes = 1073741824         # 单个分段文件大小（1GB）
log.segment.delete.delay.ms = 60000    # 删除延迟

# 清理策略
log.cleanup.policy = delete    # 删除（默认）
# log.cleanup.policy = compact  # 压缩（保留最新的 key）
```

### 6.2 压缩策略（Compact）

```properties
# 按 key 压缩：保留每个 key 的最新值
log.cleanup.policy = compact
min.cleanable.dirty.ratio = 0.5   # 压缩触发比例
```

```
Compaction 示例：
原始日志：
key=user1, value=Tom
key=user2, value=Jerry
key=user1, value=Tommy    ← 更新 user1

Compaction 后：
key=user2, value=Jerry
key=user1, value=Tommy    ← 旧值被清理，只保留最新值
```

---

## 七、总结

### 端到端可靠性配置

```java
// 生产者端——不丢消息
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.RETRIES_CONFIG, 3);

// Broker 端——不丢数据
// replication.factor = 3
// min.insync.replicas = 2
// unclean.leader.election.enable = false

// 消费者端——不丢消息
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
// 处理完消息后手动提交位移
```

### 各环节可靠性速查

| 环节 | 风险 | 对策 |
|------|------|------|
| 发送 | 网络故障丢失 | acks=all + 重试 + 幂等性 |
| 存储 | 磁盘故障丢失 | 多副本 + ISR 同步 |
| 消费 | 处理异常导致丢失 | 手动提交 + 幂等消费 |

---

**上一篇：** [Kafka（三）：消费者原理]({{< relref "post/kafka-consumer" >}})

**下一篇：** [Kafka（五）：存储机制]({{< relref "post/kafka-storage" >}})
