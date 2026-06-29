---
title: "Kafka（二）：生产者原理"
date: 2018-08-05
draft: false
categories: ["消息队列"]
tags: ["Kafka", "生产者", "分区", "幂等性", "事务", "发送流程"]
toc: true
---

## 前言

生产者（Producer）是 Kafka 消息的入口。理解生产者的工作原理——包括**发送流程**、**分区策略**、**拦截器**、**序列化器**以及**幂等性与事务**——能帮助我们更好地保证消息的正确性和性能。

<!--more-->

## 一、生产者发送流程

### 1.1 整体架构

```
Producer 发送消息的完整流程：

Producer.createRecord(topic, key, value)
    │
    ├── 1. 拦截器（Interceptor）— 发送前拦截/修改
    │
    ├── 2. 序列化器（Serializer）— key/value 序列化为字节
    │
    ├── 3. 分区器（Partitioner）— 确定发送到哪个分区
    │
    ├── 4. RecordAccumulator（消息累加器）
    │     └── 消息暂存到双端队列中，等待发送
    │
    ├── 5. Sender 线程批量发送
    │     └── 从 RecordAccumulator 拉取消息
    │     └── 构建请求，发送到 Broker
    │
    └── 6. Broker 处理并响应
          ├── acks=0：不等待确认
          ├── acks=1：等待 Leader 写入成功
          └── acks=all：等待 ISR 全部写入成功
```

### 1.2 Java API 生产者

```java
import org.apache.kafka.clients.producer.*;

public class KafkaProducerExample {
    
    public static void main(String[] args) {
        // 1. 配置生产者
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
                  "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                  "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.ACKS_CONFIG, "all");          // 最可靠
        props.put(ProducerConfig.RETRIES_CONFIG, 3);            // 重试次数
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);     // 批量大小
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);          // 等待时间
        
        // 2. 创建生产者
        KafkaProducer<String, String> producer = 
                new KafkaProducer<>(props);
        
        // 3. 发送消息（三种方式）
        // 方式一：发后即忘（不关心结果）
        producer.send(new ProducerRecord<>("my-topic", "key1", "value1"));
        
        // 方式二：同步发送
        try {
            RecordMetadata metadata = producer.send(
                    new ProducerRecord<>("my-topic", "key2", "value2")).get();
            System.out.printf("发送成功: partition=%d, offset=%d%n",
                    metadata.partition(), metadata.offset());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 方式三：异步发送（回调）
        producer.send(new ProducerRecord<>("my-topic", "key3", "value3"),
                (metadata, exception) -> {
                    if (exception != null) {
                        exception.printStackTrace();
                    } else {
                        System.out.printf("发送成功: partition=%d, offset=%d%n",
                                metadata.partition(), metadata.offset());
                    }
                });
        
        // 4. 关闭生产者
        producer.close();
    }
}
```

---

## 二、分区策略

### 2.1 默认分区器

```java
// 默认分区逻辑（DefaultPartitioner）：
// 1. key 不为 null → 对 key 哈希取模（相同 key 到同一分区）
// 2. key 为 null → 轮询（Round Robin）或粘性分区

// 粘性分区（Sticky Partitioner，2.4+）：
// 为了批量发送优化，将一批消息发送到同一个分区
// 性能比轮询更高（减少了分区切换次数）

// 自定义分区器
props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, 
          CustomPartitioner.class.getName());
```

### 2.2 自定义分区器

```java
public class OrderPartitioner implements Partitioner {
    
    @Override
    public int partition(String topic, Object key, byte[] keyBytes,
                         Object value, byte[] valueBytes, Cluster cluster) {
        // 获取该 Topic 的分区数
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        int numPartitions = partitions.size();
        
        // 按订单号末尾数字分区
        String orderId = (String) key;
        int lastDigit = Integer.parseInt(orderId.substring(orderId.length() - 1));
        
        return lastDigit % numPartitions;
    }
    
    @Override
    public void close() {}
    
    @Override
    public void configure(Map<String, ?> configs) {}
}
```

---

## 三、发送参数详解

### 3.1 关键配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `acks` | 1 | 确认级别：0/1/all |
| `retries` | Integer.MAX | 重试次数 |
| `batch.size` | 16384(16KB) | 批量发送大小 |
| `linger.ms` | 0 | 批量等待时间 |
| `buffer.memory` | 33554432(32MB) | 缓冲区大小 |
| `max.request.size` | 1048576(1MB) | 最大请求大小 |
| `compression.type` | none | 压缩类型（gzip/snappy/lz4/zstd）|
| `enable.idempotence` | false | 是否开启幂等性 |

### 3.2 acks 详解

```java
// acks = 0：不等待 Broker 确认
//   最快，可能丢失消息
//   适用场景：日志、监控等可丢失数据

// acks = 1：Leader 写入成功后确认
//   保证 Leader 不丢，但 Leader 宕机时可能丢失
//   默认配置

// acks = all 或 -1：ISR 全部写入成功后确认
//   最可靠，不丢数据
//   推荐生产环境使用
props.put(ProducerConfig.ACKS_CONFIG, "all");
```

### 3.3 重试与幂等性

```java
// 开启幂等性（enable.idempotence=true 时，生产者自动设置）
// 原理：每个生产者有唯一的 Producer ID（PID）
//       每条消息有序列号（Sequence Number）
//       Broker 根据 PID + 序列号去重

// 幂等性 + acks=all + retries 高 → 精确一次发送（Exactly Once）
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
```

---

## 四、消息序列化

### 4.1 自定义序列化器

```java
public class UserSerializer implements Serializer<User> {
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public byte[] serialize(String topic, User data) {
        if (data == null) return null;
        try {
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new SerializationException("序列化 User 失败", e);
        }
    }
}

// 使用 Avro（推荐，兼容性好）
// 使用 Protobuf（高性能）
// 生产环境建议使用 Avro / Protobuf / JSON
```

---

## 五、消息发送原理

### 5.1 RecordAccumulator

```
RecordAccumulator 是生产者的核心缓冲结构：

每个 TopicPartition 对应一个双端队列（Deque<ProducerBatch>）

ProducerBatch（消息批次）：
┌────────────────────────────────────────────┐
│ magic  │ crc   │ attributes│ timestamp │    │
├────────────────────────────────────────────┤
│ msg1  │ msg2  │ msg3  │ ...（多个消息）    │
├────────────────────────────────────────────┤
│ 压缩后（如果开启压缩）                      │
└────────────────────────────────────────────┘

Sender 线程不断从 RecordAccumulator 拉取 ProducerBatch，
组装为 ProduceRequest，发送到对应的 Broker。
```

### 5.2 批量发送条件

```
Sender 线程发送一批消息的条件（满足任一即发送）：
1. 消息大小达到 batch.size（默认 16KB）
2. 时间达到 linger.ms（默认 0ms）
3. 缓冲区满了
4. 生产者关闭

调参建议：
- 提高 batch.size + 增大 linger.ms → 提高吞吐量，增加延迟
- 降低 batch.size + 减小 linger.ms → 降低延迟，减少吞吐量
```

---

## 六、生产者事务

### 6.1 事务 API

```java
// 开启事务需要配置
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "tx-id-001");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

KafkaProducer<String, String> producer = new KafkaProducer<>(props);

// 初始化事务
producer.initTransactions();

try {
    // 开始事务
    producer.beginTransaction();
    
    // 发送多条消息（跨 Topic 跨分区）
    producer.send(new ProducerRecord<>("topic-a", "key1", "value1"));
    producer.send(new ProducerRecord<>("topic-b", "key2", "value2"));
    
    // 提交事务
    producer.commitTransaction();
} catch (Exception e) {
    // 回滚事务
    producer.abortTransaction();
}
```

### 6.2 事务原理

```
事务通过 Transaction Coordinator 协调：
1. Producer 向 Transaction Coordinator 注册事务
2. 写入数据到目标分区
3. 提交/回滚时，在 __transaction_state 中记录
4. 消费者通过 isolation.level 控制是否读取事务消息

isolation.level = read_committed  → 只读已提交的事务消息（推荐）
isolation.level = read_uncommitted → 读所有消息（默认）
```

---

## 七、总结

### 生产者配置推荐

```java
// 生产环境推荐配置
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

// 可靠性
props.put(ProducerConfig.ACKS_CONFIG, "all");
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
props.put(ProducerConfig.RETRIES_CONFIG, 3);

// 性能
props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);    // 32KB
props.put(ProducerConfig.LINGER_MS_CONFIG, 10);          // 10ms
props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 67108864); // 64MB
```

### 发送路径速记

```
ProducerRecord → Interceptor → Serializer → Partitioner
→ RecordAccumulator → Sender → Network → Broker
```

---

**上一篇：** [Kafka（一）：核心概念与架构]({{< relref "post/kafka-architecture" >}})

**下一篇：** [Kafka（三）：消费者原理]({{< relref "post/kafka-consumer" >}})
