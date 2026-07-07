---
title: "RabbitMQ（四）：高级特性"
date: 2019-03-08
draft: false
categories: ["消息队列"]
tags: ["RabbitMQ", "死信队列", "延迟队列", "TTL", "优先级队列", "流控"]
toc: true
---

## 前言

RabbitMQ 提供了多种高级特性来满足复杂的业务需求：**死信队列**处理消费失败的消息、**延迟队列**实现定时任务、**TTL** 控制消息存活时间，以及**优先级队列**保证重要消息优先处理。

<!--more-->

## 一、死信队列（DLX）

### 1.1 什么是死信

```
死信（Dead Letter）：无法被正常消费的消息

消息变成死信的三种情况：
1. 消息被拒绝（basicNack/basicReject）且 requeue=false
2. 消息 TTL 过期
3. 队列达到最大长度
```

### 1.2 死信队列架构

```
正常队列 → 死信转发（x-dead-letter-exchange）→ 死信队列 → 死信消费者
                     │
                     ├── TTL 过期
                     ├── 消费者拒绝且不重新入队
                     └── 队列达到最大长度
```

### 1.3 配置死信队列

```java
@Configuration
public class DLXConfig {
    
    // 1. 死信交换机（普通交换机）
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange("exchange.dlx");
    }
    
    // 2. 死信队列
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable("queue.dlx").build();
    }
    
    // 3. 死信绑定
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange()).with("dead");
    }
    
    // 4. 业务队列（配置死信转发）
    @Bean
    public Queue businessQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", "exchange.dlx");    // 死信交换机
        args.put("x-dead-letter-routing-key", "dead");         // 死信路由键
        args.put("x-max-length", 10000);                       // 队列最大长度
        return QueueBuilder.durable("queue.business")
                .withArguments(args)
                .build();
    }
    
    // 5. 业务交换机绑定
    @Bean
    public Binding businessBinding() {
        return BindingBuilder.bind(businessQueue())
                .to(new DirectExchange("exchange.business"))
                .with("business");
    }
}
```

### 1.4 死信消费者（处理失败消息）

```java
@Component
public class DeadLetterConsumer {
    
    @RabbitListener(queues = "queue.dlx")
    public void handleDeadLetter(Message message, Channel channel) {
        log.warn("收到死信: {}", new String(message.getBody()));
        
        // 从消息头获取原始信息
        MessageProperties props = message.getMessageProperties();
        log.warn("原始交换机: {}", props.getHeader("x-first-death-exchange"));
        log.warn("原始路由键: {}", props.getHeader("x-first-death-routing-key"));
        log.warn("死亡原因: {}", props.getHeader("x-first-death-reason"));
        
        // 死信处理策略：记录到数据库 + 人工介入
        deadLetterService.record(message);
        channel.basicAck(props.getDeliveryTag(), false);
    }
}
```

---

## 二、延迟队列

RabbitMQ 本身没有延迟队列功能，通过**死信队列 + TTL** 实现。

### 2.1 实现原理

```
Producer → 延迟队列（设置 TTL，不消费）→ TTL 过期 → 死信队列 → 消费者

流程：
1. 消息发送到延迟队列，消息 TTL = 延迟时间
2. 延迟队列没有消费者
3. TTL 过期后，消息变为死信
4. 死信转发到业务队列
5. 消费者从业务队列消费
```

### 2.2 通过 Spring 实现延迟队列

```java
@Configuration
public class DelayConfig {
    
    // 延迟交换机（死信交换机）
    @Bean
    public DirectExchange delayExchange() {
        return new DirectExchange("exchange.delay");
    }
    
    // 延迟队列：消息在此等待 TTL 过期
    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", "exchange.business");  // 到期后转发到业务交换机
        args.put("x-dead-letter-routing-key", "business");
        return QueueBuilder.durable("queue.delay")
                .withArguments(args)
                .build();
    }
    
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue())
                .to(delayExchange()).with("delay");
    }
    
    // 业务队列：实际消费
    @Bean
    public Queue businessQueue() {
        return QueueBuilder.durable("queue.business.delay").build();
    }
    
    @Bean
    public DirectExchange businessExchange() {
        return new DirectExchange("exchange.business");
    }
    
    @Bean
    public Binding businessBinding() {
        return BindingBuilder.bind(businessQueue())
                .to(businessExchange()).with("business");
    }
}
```

### 2.3 发送延迟消息

```java
@Service
public class DelayProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    // 发送延迟消息（通过消息 TTL）
    public void sendDelay(String message, long delayMillis) {
        MessageProperties properties = new MessageProperties();
        properties.setExpiration(String.valueOf(delayMillis));  // TTL = 延迟时间
        Message msg = MessageBuilder.withBody(message.getBytes())
                .andProperties(properties)
                .build();
        
        rabbitTemplate.send("exchange.delay", "delay", msg);
    }
    
    // 示例：30 分钟后取消订单
    public void cancelOrderAfter30Min(Long orderId) {
        sendDelay("cancel:" + orderId, 30 * 60 * 1000);
    }
}

// 消费者（处理延迟到期的消息）
@Component
public class DelayConsumer {
    
    @RabbitListener(queues = "queue.business.delay")
    public void handle(String message) {
        log.info("延迟消息到期: {}", message);
        // 处理业务
    }
}
```

### 2.4 插件方式（推荐）

```bash
# 安装延迟消息插件
rabbitmq-plugins enable rabbitmq_delayed_message_exchange
```

```java
// 使用插件后，可以直接使用延迟交换机
@Bean
public CustomExchange delayExchange() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-delayed-type", "direct");
    return new CustomExchange("exchange.delayed", "x-delayed-message", true, false, args);
}

@Service
public class DelayProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void sendDelay(String message, long delayMillis) {
        MessageProperties properties = new MessageProperties();
        properties.setDelay((int) delayMillis);  // 插件支持的延迟属性
        Message msg = MessageBuilder.withBody(message.getBytes())
                .andProperties(properties)
                .build();
        
        rabbitTemplate.send("exchange.delayed", "delay", msg);
    }
}
```

---

## 三、TTL（Time To Live）

### 3.1 消息 TTL

```java
// 方式一：队列统一设置
@Bean
public Queue queueWithTTL() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-message-ttl", 60000);  // 队列中消息 60 秒过期
    return QueueBuilder.durable("queue.ttl")
            .withArguments(args)
            .build();
}

// 方式二：消息单独设置
MessageProperties properties = new MessageProperties();
properties.setExpiration("30000");  // 此消息 30 秒过期
Message msg = MessageBuilder.withBody(body)
        .andProperties(properties)
        .build();
```

### 3.2 队列 TTL

```java
// 队列自动删除（没有消费者且没有消息后）
@Bean
public Queue autoDeleteQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-expires", 1800000);  // 30 分钟无消费者自动删除
    return QueueBuilder.durable("queue.auto")
            .withArguments(args)
            .build();
}
```

---

## 四、优先级队列

### 4.1 配置

```java
@Bean
public Queue priorityQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-max-priority", 10);  // 优先级范围 0-10
    return QueueBuilder.durable("queue.priority")
            .withArguments(args)
            .build();
}
```

### 4.2 发送优先级消息

```java
@Service
public class PriorityProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void send(String message, int priority) {
        MessageProperties properties = new MessageProperties();
        properties.setPriority(priority);  // 设置优先级
        Message msg = MessageBuilder.withBody(message.getBytes())
                .andProperties(properties)
                .build();
        
        rabbitTemplate.send("exchange.priority", "priority", msg);
    }
}

// 使用
priorityProducer.send("普通消息", 0);
priorityProducer.send("VIP消息", 10);  // VIP消息优先消费
```

---

## 五、流量控制

### 5.1 Prefetch 限制

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        prefetch: 10       # 消费者最多同时处理 10 条未确认消息
```

```java
@Bean
public SimpleRabbitListenerContainerFactory factory(
        ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = 
            new SimpleRabbitListenerContainerFactory();
    factory.setPrefetchCount(10);
    return factory;
}
```

### 5.2 队列长度限制

```java
@Bean
public Queue limitedQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-max-length", 10000);          // 最多 10000 条
    args.put("x-max-length-bytes", 10485760); // 最大 10MB
    args.put("x-overflow", "drop-head");      // 满时丢弃头部（默认）
    // args.put("x-overflow", "reject-publish"); // 满时拒绝新消息
    return QueueBuilder.durable("queue.limited")
            .withArguments(args)
            .build();
}
```

---

## 六、总结

### 高级特性速查

| 特性 | 实现方式 | 用途 |
|------|---------|------|
| 死信队列（DLX）| 队列配置 x-dead-letter-exchange | 消费失败的消息处理 |
| 延迟队列 | DLX + TTL 或插件 | 定时任务、超时处理 |
| 消息 TTL | 队列参数 x-message-ttl | 控制消息存活时间 |
| 队列 TTL | 队列参数 x-expires | 自动清理不用的队列 |
| 优先级 | 队列参数 x-max-priority | VIP 消息优先处理 |
| 队列长度限制 | x-max-length / x-max-length-bytes | 防止消息堆积 |

**上一篇：** [RabbitMQ（三）：可靠性保证]({{< relref "post/rabbitmq-reliability" >}})

**下一篇：** [RabbitMQ（五）：集群与高可用]({{< relref "post/rabbitmq-cluster" >}})
