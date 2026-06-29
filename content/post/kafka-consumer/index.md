---
title: "Kafka（三）：消费者原理"
date: 2018-08-07
draft: false
categories: ["消息队列"]
tags: ["Kafka", "消费者", "Consumer Group", "Rebalance", "位移提交"]
toc: true
---

## 前言

消费者（Consumer）从 Kafka 拉取消息进行处理。相比生产者，消费者的机制更复杂——**消费者组**的协调、**Rebalance** 的触发、**位移提交**的方式，这些机制直接影响消息消费的正确性和性能。

<!--more-->

## 一、消费者与消费者组

### 1.1 消费者组的概念

```
同一个 Consumer Group 中的消费者共同消费 Topic 的消息。
每个分区只能被组内的一个消费者消费。

消费者数 ≤ 分区数（否则有消费者闲置）

┌─────────────────────────────────────────────┐
│  Topic: orders (6 个分区)                     │
│                                              │
│  Consumer Group A (3 个消费者)               │
│  ├── Consumer-1 → Partition 0, 1            │
│  ├── Consumer-2 → Partition 2, 3            │
│  └── Consumer-3 → Partition 4, 5            │
│                                              │
│  Consumer Group B (2 个消费者)               │
│  ├── Consumer-1 → Partition 0, 1, 2         │
│  └── Consumer-2 → Partition 3, 4, 5         │
└─────────────────────────────────────────────┘
```

### 1.2 Java API 消费者

```java
import org.apache.kafka.clients.consumer.*;

public class KafkaConsumerExample {
    
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "my-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                  "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                  "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        
        // 订阅 Topic
        consumer.subscribe(List.of("my-topic"));
        
        try {
            while (true) {
                // 拉取消息（超时 1 秒）
                ConsumerRecords<String, String> records = 
                        consumer.poll(Duration.ofMillis(1000));
                
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("partition=%d, offset=%d, key=%s, value=%s%n",
                            record.partition(), record.offset(),
                            record.key(), record.value());
                }
                
                // 手动提交位移（等处理完了再提交）
                consumer.commitSync();
            }
        } finally {
            consumer.close();
        }
    }
}
```

---

## 二、消费模式

### 2.1 subscribe（自动分配分区）

```java
// 自动分配：消费者组自动协调，分区由协调器分配
consumer.subscribe(List.of("topic-a", "topic-b"));

// 适用于：弹性伸缩的场景（动态增减消费者）
```

### 2.2 assign（手动指定分区）

```java
// 手动指定：直接分配特定分区，不参与消费者组协调
consumer.assign(List.of(
        new TopicPartition("topic-a", 0),
        new TopicPartition("topic-a", 1)
));

// 适用于：需要精确控制分区分配的场景
// 注意：assign 模式不会自动 Rebalance
```

---

## 三、位移提交

### 3.1 自动提交 vs 手动提交

```java
// 自动提交（默认，不推荐生产）
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "5000");
// 每 5 秒自动提交一次位移
// 问题：可能重复消费（提交间隔内宕机）或丢失消息

// 手动提交（推荐）
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

// 手动提交方式：
// 1. 同步提交
consumer.commitSync();      // 阻塞直到提交成功，会重试

// 2. 异步提交
consumer.commitAsync();     // 不阻塞，有回调

// 3. 混合使用
try {
    while (true) {
        ConsumerRecords<String, String> records = consumer.poll(1000);
        for (...) { process(record); }
        
        consumer.commitAsync();  // 异步提交（快速）
    }
} finally {
    consumer.commitSync();   // 关闭前同步提交最后一次
}
```

### 3.2 按分区提交

```java
// 处理完每个分区后单独提交位移
ConsumerRecords<String, String> records = consumer.poll(1000);

for (TopicPartition partition : records.partitions()) {
    List<ConsumerRecord<String, String>> partitionRecords = 
            records.records(partition);
    
    for (ConsumerRecord<String, String> record : partitionRecords) {
        process(record);
    }
    
    // 提交该分区的最后位移
    long lastOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(lastOffset + 1)));
}
```

---

## 四、Rebalance 机制

### 4.1 触发条件

```
触发 Rebalance 的场景：
1. 消费者组内加入新消费者
2. 消费者组内消费者宕机或超时
3. Topic 分区数发生变化
4. 订阅的 Topic 被删除

Rebalance 期间：
- 所有消费者停止消费（Stop The World）
- 重新分配分区
- 重新开始消费
```

### 4.2 分区分配策略

```java
// 可以通过 partition.assignment.strategy 配置策略
props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
          RangeAssignor.class.getName());

// RangeAssignor（默认）：按 Topic 范围分配
//   每个 Topic 独立分配：总分区数 / 消费者数
//   可能导致分配不均

// RoundRobinAssignor：轮询分配
//   所有 Topic 的分区一起轮询
//   分配相对均匀

// StickyAssignor（推荐）：粘性分配
//   尽量保持已有分配，减少 Rebalance 的分区移动
//   减少 Rebalance 的开销

// CooperativeStickyAssignor（2.4+）：协作式 Rebalance
//   增量式 Rebalance，不 Stop The World
```

### 4.3 Rebalance 监听器

```java
consumer.subscribe(List.of("topic"), new ConsumerRebalanceListener() {
    
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // 在 Rebalance 开始前调用
        // 在失去分区前，提交位移
        System.out.println("即将失去分区: " + partitions);
        consumer.commitSync(currentOffsets);
    }
    
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        // 在 Rebalance 完成后调用
        // 在新分配分区后，可以初始化状态
        System.out.println("新分配分区: " + partitions);
    }
});
```

---

## 五、消费者的关键参数

### 5.1 重要配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `group.id` | null | 消费者组 ID（必须指定）|
| `enable.auto.commit` | true | 是否自动提交位移 |
| `auto.commit.interval.ms` | 5000 | 自动提交间隔 |
| `auto.offset.reset` | latest | 无位移时的策略（earliest/latest/none）|
| `max.poll.records` | 500 | 每次 poll 的最大记录数 |
| `max.poll.interval.ms` | 300000 | 两次 poll 的最大间隔（5 分钟）|
| `session.timeout.ms` | 45000 | 消费者会话超时 |
| `heartbeat.interval.ms` | 3000 | 心跳间隔 |
| `fetch.min.bytes` | 1 | 拉取最小字节数 |
| `fetch.max.wait.ms` | 500 | 拉取最大等待时间 |
| `isolation.level` | read_uncommitted | 事务隔离级别 |

### 5.2 auto.offset.reset 详解

```java
// auto.offset.reset = earliest
//   从最早的消息开始消费（从头消费）
//   适用于：新消费者需要全量数据

// auto.offset.reset = latest（默认）
//   从最新的消息开始消费
//   适用于：只关心新消息

// auto.offset.reset = none
//   如果没找到位移，直接报错

// 注意：这个参数只在消费者组首次消费或位移过期时生效
// 已有位移的情况下，按已有位移继续消费
```

### 5.3 消费性能调优

```java
// 提高吞吐量的配置
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024 * 1024);      // 1MB
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);             // 500ms
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);             // 每次 1000 条

// 提高处理能力的配置
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);      // 10 分钟

// 实际经验：
// max.poll.interval.ms 要大于单次处理所有消息的时间
// 否则会触发 Rebalance
```

---

## 六、消费者的多线程模型

```java
// 方式一：每个分区一个处理线程（推荐）
public class PartitionProcessor {
    
    private final KafkaConsumer<String, String> consumer;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    
    public void consume() {
        consumer.subscribe(List.of("my-topic"));
        
        while (true) {
            ConsumerRecords<String, String> records = consumer.poll(100);
            
            for (ConsumerRecord<String, String> record : records) {
                // 异步处理，不阻塞 poll 循环
                executor.submit(() -> process(record));
            }
            
            // 注意：需要所有异步任务完成后才提交位移
            // 否则可能丢失位移
        }
    }
}

// 方式二：多个消费者（每个线程一个消费者实例）
// 每个消费者独立 poll 和提交位移
// 消费者数 = 分区数（避免闲置）
```

---

## 七、总结

### 消费者关键配置推荐

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "my-service");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

// 可靠性
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

// 性能
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 65536);  // 64KB
props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

// Rebalance
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
```

### 消费者原理速记

```
消费流程：subscribe → poll → 处理消息 → commit
位移提交：auto 自动（可能丢消息） vs manual 手动（推荐）
Rebalance：消费者增减/分区变化 → 重新分配 → 短暂停摆
```

---

**上一篇：** [Kafka（二）：生产者原理]({{< relref "post/kafka-producer" >}})

**下一篇：** [Kafka（四）：可靠性保证]({{< relref "post/kafka-reliability" >}})
