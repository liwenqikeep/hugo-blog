---
title: "RabbitMQ（二）：五种消息模型"
date: 2019-03-04
draft: false
categories: ["消息队列"]
tags: ["RabbitMQ", "消息模型", "Direct", "Fanout", "Topic", "RPC"]
toc: true
---

## 前言

RabbitMQ 支持多种消息模型，从简单的点对点通信到复杂的发布订阅和 RPC。理解这些模型的适用场景，是设计 RabbitMQ 应用的基礎。

本文通过 Spring Boot 代码示例，覆盖 RabbitMQ 五种最常见的消息模型。

<!--more-->

## 一、准备工作

### 1.1 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

### 1.2 配置文件

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
    # 生产者确认（后续可靠消息会详述）
    publisher-confirm-type: correlated
    publisher-returns: true
```

### 1.3 配置类

```java
@Configuration
public class RabbitConfig {
    
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        return template;
    }
    
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = 
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new Jackson2JsonMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
```

---

## 二、模型一：点对点（Work Queue）

### 2.1 说明

```
最简单的模型：一个 Producer → 一个 Queue → 一个 Consumer
或者多个 Consumer 竞争消费（Work Queue）

默认情况下，消息轮询分发（Round Robin）
可以设置 prefetch=1 改为公平分发（能者多劳）
```

### 2.2 代码实现

```java
// 配置
@Configuration
public class WorkQueueConfig {
    
    @Bean
    public Queue workQueue() {
        return QueueBuilder.durable("work.queue").build();
    }
}

// 生产者
@Service
public class WorkQueueProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void send(String message) {
        rabbitTemplate.convertAndSend("work.queue", message);
    }
}

// 消费者
@Component
public class WorkQueueConsumer {
    
    @RabbitListener(queues = "work.queue", concurrency = "3")
    public void handleMessage(String message, 
                               @Header(AmqpHeaders.DELIVERY_TAG) long tag,
                               Channel channel) throws IOException {
        try {
            System.out.println("收到: " + message);
            channel.basicAck(tag, false);  // 手动确认
        } catch (Exception e) {
            channel.basicNack(tag, false, true);  // 重试
        }
    }
}
```

---

## 三、模型二：发布订阅（Pub/Sub）

### 3.1 说明

```
Producer → Fanout Exchange → 多个 Queue → 多个 Consumer
每条消息都会广播到所有绑定的队列

消息不直接发送到 Queue，而是发送到 Exchange
Exchange 将消息分发到所有绑定的 Queue
```

### 3.2 代码实现

```java
@Configuration
public class PubSubConfig {
    
    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange("exchange.fanout");
    }
    
    @Bean
    public Queue queue1() {
        return QueueBuilder.durable("pubsub.queue1").build();
    }
    
    @Bean
    public Queue queue2() {
        return QueueBuilder.durable("pubsub.queue2").build();
    }
    
    @Bean
    public Binding binding1() {
        return BindingBuilder.bind(queue1()).to(fanoutExchange());
    }
    
    @Bean
    public Binding binding2() {
        return BindingBuilder.bind(queue2()).to(fanoutExchange());
    }
}

// 生产者
@Service
public class PubSubProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void send(String message) {
        rabbitTemplate.convertAndSend("exchange.fanout", "", message);
    }
}

// 消费者1
@Component
public class PubSubConsumer1 {
    
    @RabbitListener(queues = "pubsub.queue1")
    public void handle(String message) {
        System.out.println("消费者1收到: " + message);
    }
}

// 消费者2
@Component
public class PubSubConsumer2 {
    
    @RabbitListener(queues = "pubsub.queue2")
    public void handle(String message) {
        System.out.println("消费者2收到: " + message);
    }
}
```

---

## 四、模型三：路由（Routing）

### 4.1 说明

```
Producer → Direct Exchange → 按 routing_key 分发到不同 Queue
routing_key 精确匹配

场景：日志系统
error 日志 → 发送到 error_queue（专人处理）
info/warn 日志 → 发送到 log_queue（普通记录）
```

### 4.2 代码实现

```java
@Configuration
public class RoutingConfig {
    
    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange("exchange.direct");
    }
    
    @Bean
    public Queue errorQueue() {
        return QueueBuilder.durable("error.queue").build();
    }
    
    @Bean
    public Queue logQueue() {
        return QueueBuilder.durable("log.queue").build();
    }
    
    @Bean
    public Binding errorBinding() {
        return BindingBuilder.bind(errorQueue())
                .to(directExchange()).with("error");
    }
    
    @Bean
    public Binding logBinding() {
        return BindingBuilder.bind(logQueue())
                .to(directExchange()).with("log");
    }
}

// 生产者
@Service
public class RoutingProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void send(String level, String message) {
        rabbitTemplate.convertAndSend("exchange.direct", level, message);
    }
}

// 错误消费者（只处理 error 级别）
@Component
public class ErrorConsumer {
    
    @RabbitListener(queues = "error.queue")
    public void handle(String message) {
        System.out.println("错误处理: " + message);
        // 发送告警短信、邮件等
    }
}
```

---

## 五、模型四：主题（Topic）

### 5.1 说明

```
Producer → Topic Exchange → 按 routing_key 通配符匹配分发

通配符：
* 匹配一个单词
# 匹配零个或多个单词

场景：多个维度的消息分类
order.created → 订单创建
order.paid    → 订单支付
user.registered → 用户注册
```

### 5.2 代码实现

```java
@Configuration
public class TopicConfig {
    
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange("exchange.topic");
    }
    
    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable("order.queue").build();
    }
    
    @Bean
    public Queue paymentQueue() {
        return QueueBuilder.durable("payment.queue").build();
    }
    
    // 所有订单相关消息
    @Bean
    public Binding orderBinding() {
        return BindingBuilder.bind(orderQueue())
                .to(topicExchange()).with("order.#");
    }
    
    // 所有支付相关消息
    @Bean
    public Binding paymentBinding() {
        return BindingBuilder.bind(paymentQueue())
                .to(topicExchange()).with("payment.#");
    }
}

// 生产者
@Service
public class TopicProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void sendOrderCreated(Long orderId) {
        rabbitTemplate.convertAndSend("exchange.topic", 
                "order.created", orderId);
    }
    
    public void sendOrderPaid(Long orderId) {
        rabbitTemplate.convertAndSend("exchange.topic", 
                "order.paid", orderId);
    }
}
```

---

## 六、模型五：RPC（远程过程调用）

### 6.1 说明

```
RabbitMQ 也可以实现 RPC 模式：
1. Producer 发送消息到 request_queue
2. 同时在消息中设置 reply_to（回调队列）和 correlation_id（关联ID）
3. Consumer 处理请求后，将结果发送到 reply_to 队列
4. Producer 从回调队列接收响应

应用场景：需要服务端处理并返回结果的场景
```

### 6.2 代码实现

```java
// 配置
@Configuration
public class RpcConfig {
    
    @Bean
    public Queue rpcRequestQueue() {
        return QueueBuilder.durable("rpc.request.queue").build();
    }
}

// RPC 客户端
@Service
public class RpcClient {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public Object call(String message) {
        // convertSendAndReceive 内部自动处理 reply_to 和 correlation_id
        Object result = rabbitTemplate.convertSendAndReceive(
                "rpc.request.queue", message);
        return result;
    }
}

// RPC 服务端
@Component
public class RpcServer {
    
    @RabbitListener(queues = "rpc.request.queue")
    public String handle(String message) {
        System.out.println("收到 RPC 请求: " + message);
        // 处理业务
        return "处理结果: " + message.toUpperCase();
    }
}
```

---

## 七、消息模型对比

| 模型 | Exchange 类型 | 路由方式 | 使用场景 |
|------|-------------|---------|---------|
| Work Queue | 无（默认 Exchange）| 队列名 | 异步任务处理 |
| Pub/Sub | Fanout | 广播 | 事件通知 |
| Routing | Direct | 精确匹配 | 日志分级 |
| Topic | Topic | 通配符 | 多维度分类 |
| RPC | Direct | 精确匹配 | 远程调用 |

---

**上一篇：** [RabbitMQ（一）：核心概念与架构]({{< relref "post/rabbitmq-architecture" >}})

**下一篇：** [RabbitMQ（三）：可靠性保证]({{< relref "post/rabbitmq-reliability" >}})
