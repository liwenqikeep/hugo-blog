---
title: "RabbitMQ（一）：核心概念与架构"
date: 2019-03-02
draft: false
categories: ["消息队列"]
tags: ["RabbitMQ", "消息队列", "AMQP", "Exchange", "Queue"]
toc: true
---

## 前言

RabbitMQ 是最流行的开源消息代理之一，基于 AMQP 0-9-1 协议实现。与 Kafka 的流处理定位不同，RabbitMQ 侧重于**灵活的消息路由**、**可靠的消息投递**和**多协议支持**。

本文从核心概念出发，逐步覆盖 RabbitMQ 的架构设计、通信模型和基础操作。

<!--more-->

## 一、核心概念

### 1.1 整体架构

```
Producer（生产者）
    │
    ▼
 Exchange（交换机）—— 根据绑定规则路由消息
    │
    ▼
 Queue（队列）—— 存储消息
    │
    ▼
Consumer（消费者）—— 消费消息
```

### 1.2 核心角色

| 角色 | 说明 |
|------|------|
| **Producer** | 消息生产者，发布消息到 Exchange |
| **Exchange** | 交换机，根据 Binding 规则将消息路由到 Queue |
| **Queue** | 队列，存储消息，等待消费者消费 |
| **Consumer** | 消息消费者，从 Queue 拉取或推送消息 |
| **Binding** | 绑定关系，定义 Exchange 和 Queue 之间的路由规则 |
| **Connection** | TCP 连接 |
| **Channel** | 虚拟连接，在 Connection 之上建立，实际通信单元 |

### 1.3 AMQP 协议模型

```
AMQP 0-9-1 模型：

┌────────────┐
│  Producer  │
└─────┬──────┘
      │ 发布消息
      ▼
┌────────────┐      Binding      ┌──────────┐
│  Exchange  │────────────────────────▶  Queue  │
│            │                    │          │
│ Direct     │   routing_key      │  消息存储 │
│ Fanout     │   queue_name       │   FIFO   │
│ Topic      │                    │          │
│ Headers    │                    │          │
└────────────┘                    └─────┬────┘
                                        │ 消费
                                        ▼
                                   ┌──────────┐
                                   │ Consumer  │
                                   └──────────┘
```

---

## 二、Exchange 交换机

### 2.1 四种交换机类型

| 类型 | 路由规则 | 适用场景 |
|------|---------|---------|
| **Direct** | routing_key 精确匹配 | 点对点通信 |
| **Fanout** | 广播到所有绑定的 Queue | 发布订阅 |
| **Topic** | routing_key 通配符匹配 | 按主题过滤 |
| **Headers** | 消息头属性匹配 | 复杂路由条件 |

### 2.2 Direct Exchange

```bash
# Direct：routing_key 精确匹配

# 创建交换机
rabbitmqadmin declare exchange name=direct_logs type=direct

# 创建队列并绑定
rabbitmqadmin declare queue name=info_queue
rabbitmqadmin declare queue name=error_queue

# 绑定：routing_key 精确匹配
rabbitmqadmin declare binding source=direct_logs \
    destination=info_queue routing_key=info
rabbitmqadmin declare binding source=direct_logs \
    destination=error_queue routing_key=error

# 发送消息
# routing_key=info → info_queue
# routing_key=error → error_queue
# routing_key=warn → 无匹配，消息丢弃
```

### 2.3 Fanout Exchange

```bash
# Fanout：广播到所有绑定的 Queue，忽略 routing_key

# 创建 fanout 交换机
rabbitmqadmin declare exchange name=fanout_logs type=fanout

# 绑定多个队列（队列需要先创建）
rabbitmqadmin declare queue name=q1
rabbitmqadmin declare queue name=q2
rabbitmqadmin declare queue name=q3

# 绑定
rabbitmqadmin declare binding source=fanout_logs destination=q1
rabbitmqadmin declare binding source=fanout_logs destination=q2
rabbitmqadmin declare binding source=fanout_logs destination=q3

# 发送消息 → 所有绑定的队列都会收到
```

### 2.4 Topic Exchange

```bash
# Topic：routing_key 通配符匹配
# * 匹配一个单词
# # 匹配零个或多个单词

# 示例：routing_key = "order.created"
# order.*          → 匹配（* 匹配 created）
# order.#          → 匹配（# 匹配 created）
# *.created        → 匹配
# user.*           → 不匹配

# 创建 topic 交换机
rabbitmqadmin declare exchange name=topic_logs type=topic

# 创建队列
rabbitmqadmin declare queue name=order_queue
rabbitmqadmin declare queue name=payment_queue

# 绑定
rabbitmqadmin declare binding source=topic_logs \
    destination=order_queue routing_key="order.#"
rabbitmqadmin declare binding source=topic_logs \
    destination=payment_queue routing_key="payment.#"

# routing_key=order.created → order_queue
# routing_key=payment.success → payment_queue
# routing_key=order.created → 两个队列都收到（如果绑定了多个）
```

---

## 三、Connection 与 Channel

### 3.1 关系

```
应用程序
    │
    ├── Connection（TCP 连接，一个应用通常只有一个）
    │     │
    │     ├── Channel 1（发送消息）
    │     ├── Channel 2（消费消息）
    │     └── Channel 3（声明队列）
    │
    └── Connection（可选，另一个 TCP 连接）

Connection 是 TCP 长连接（AMQP 默认端口 5672）
Channel 是虚拟连接，在 Connection 之上复用
```

### 3.2 为什么需要 Channel？

```
每个 Connection 建立和销毁的代价很高（TCP 三次握手）
Channel 在同一个 Connection 上复用，轻量级

推荐实践：
一个应用创建一个 Connection
为每个线程创建独立的 Channel
Channel 不是线程安全的，不要多线程共享
```

---

## 四、Java 客户端基础

### 4.1 依赖

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.12.0</version>
</dependency>
```

### 4.2 生产者

```java
public class Producer {
    
    private final static String QUEUE_NAME = "hello";
    
    public static void main(String[] args) throws Exception {
        // 1. 创建 ConnectionFactory
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        
        // 2. 创建 Connection 和 Channel
        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            
            // 3. 声明队列（幂等：如果队列已存在，不会重复创建）
            channel.queueDeclare(QUEUE_NAME, false, false, false, null);
            
            // 4. 发送消息
            String message = "Hello RabbitMQ!";
            channel.basicPublish("", QUEUE_NAME, null, message.getBytes());
            System.out.println("发送: " + message);
        }
    }
}
```

### 4.3 消费者

```java
public class Consumer {
    
    private final static String QUEUE_NAME = "hello";
    
    public static void main(String[] args) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        
        // 推送模式：定义回调
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println("收到: " + message);
        };
        
        // 消费消息（autoAck=true：自动确认）
        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> {});
    }
}
```

---

## 五、管理界面与命令行

### 5.1 管理界面

```bash
# 启用管理插件
rabbitmq-plugins enable rabbitmq_management

# 访问
# http://localhost:15672
# 默认账户：guest / guest
```

### 5.2 命令行

```bash
# 创建队列
rabbitmqadmin declare queue name=my_queue durable=true

# 创建交换机
rabbitmqadmin declare exchange name=my_exchange type=direct

# 绑定
rabbitmqadmin declare binding source=my_exchange \
    destination=my_queue routing_key=my_key

# 发布消息
rabbitmqadmin publish exchange=amq.default \
    routing_key=my_queue payload="Hello"

# 查看队列
rabbitmqadmin list queues vhost name messages

# 查看绑定
rabbitmqadmin list bindings
```

---

## 六、RabbitMQ vs Kafka

| 对比 | RabbitMQ | Kafka |
|------|----------|-------|
| 定位 | 消息代理（Broker）| 流处理平台 |
| 消息模型 | Exchange/Queue | Topic/Partition |
| 路由能力 | 灵活（4 种 Exchange）| 固定（Topic）|
| 消息顺序 | 队列内有序 | 分区内有序 |
| 消息保留 | 消费后删除（默认）| 持久化保留（按时间）|
| 吞吐量 | 万级/秒 | 百万级/秒 |
| 延迟 | 微秒级 | 毫秒级 |
| 协议 | AMQP / MQTT / STOMP | 自定义协议 |
| 适用场景 | 复杂路由、异步任务 | 大数据、日志收集、流处理 |

---

## 七、总结

### 核心概念速查

| 概念 | 一句话 |
|------|--------|
| Exchange | 接收消息并路由到 Queue |
| Queue | 存储消息 |
| Binding | Exchange → Queue 的路由规则 |
| Connection | TCP 连接 |
| Channel | 虚拟连接，实际通信通道 |

### 交换机类型速记

```
Direct  → 精确匹配（点对点）
Fanout  → 广播（发布订阅）
Topic   → 通配符匹配（按主题过滤）
Headers → 消息头匹配（复杂条件）
```

**下一篇：** [RabbitMQ（二）：五种消息模型]({{< relref "post/rabbitmq-message-models" >}})
