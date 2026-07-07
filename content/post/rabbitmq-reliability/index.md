---
title: "RabbitMQ（三）：可靠性保证"
date: 2019-03-06
draft: false
categories: ["消息队列"]
tags: ["RabbitMQ", "可靠性", "消息确认", "Publisher Confirm", "持久化"]
toc: true
---

## 前言

消息可靠性是使用消息队列时最关注的问题之一。RabbitMQ 提供了从**生产端**到**消费端**的全链路可靠性保障机制：生产者确认、消息持久化、消费者确认。

<!--more-->

## 一、消息丢失的可能环节

```
消息丢失可能发生在三个环节：

1. 生产者 → Broker（发送阶段）
   ├── 网络故障导致消息未到达
   └── Exchange 路由不到 Queue（消息丢失）

2. Broker 端（存储阶段）
   ├── 消息未持久化，宕机后丢失
   └── 队列未持久化，重启后消失

3. Broker → 消费者（消费阶段）
   ├── 消费者自动确认后宕机
   └── 消费者处理异常但已确认
```

---

## 二、生产者端可靠性

### 2.1 Publisher Confirm

```yaml
# 开启 Publisher Confirm
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

```java
@Configuration
public class RabbitConfig {
    
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        
        // ★ 开启 Confirm 回调
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息发送成功: {}", correlationData.getId());
            } else {
                log.error("消息发送失败: {}, cause: {}", 
                          correlationData.getId(), cause);
                // 重试或记录到数据库
            }
        });
        
        return template;
    }
}

// 发送时携带 CorrelationData
@Service
public class OrderService {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void sendOrder(Order order) {
        CorrelationData cd = new CorrelationData(order.getOrderNo());
        rabbitTemplate.convertAndSend("order.exchange", "order.created", 
                                       order, cd);
    }
}
```

### 2.2 Publisher Returns——消息不可路由时的回调

```yaml
spring:
  rabbitmq:
    publisher-returns: true
    template:
      mandatory: true   # 消息无法路由时返回给生产者
```

```java
@Bean
public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    
    // ★ 消息不可路由回调
    template.setReturnsCallback(returned -> {
        log.error("消息路由失败: exchange={}, routingKey={}, replyText={}",
                  returned.getExchange(), returned.getRoutingKey(),
                  returned.getReplyText());
        // 记录失败消息，后续补偿
        saveFailedMessage(returned);
    });
    
    return template;
}
```

### 2.3 完整的可靠发送方案

```java
// 1. 消息入库 + 状态标记
// 2. 定时任务扫描未确认的消息
// 3. 重试发送

@Service
public class ReliableProducer {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Autowired
    private MessageRepository messageRepository;
    
    public void sendAndSave(String exchange, String routingKey, Object message) {
        // 1. 先保存消息到数据库（状态：PENDING）
        MessageRecord record = messageRepository.save(
                new MessageRecord(exchange, routingKey, message));
        
        // 2. 发送消息
        CorrelationData cd = new CorrelationData(record.getId());
        rabbitTemplate.convertAndSend(exchange, routingKey, message, cd);
    }
    
    @Scheduled(fixedDelay = 30000)
    public void retryPendingMessages() {
        // 3. 定时任务：扫描超过 30 秒仍未确认的消息
        List<MessageRecord> pending = messageRepository
                .findByStatusAndCreateTimeBefore("PENDING", 
                        LocalDateTime.now().minusSeconds(30));
        
        for (MessageRecord record : pending) {
            rabbitTemplate.convertAndSend(
                    record.getExchange(), 
                    record.getRoutingKey(), 
                    record.getMessage(),
                    new CorrelationData(record.getId()));
        }
    }
}
```

---

## 三、Broker 端可靠性

### 3.1 持久化

RabbitMQ 的持久化需要三个层面都设置：

```java
// 1. 交换机持久化
@Bean
public DirectExchange exchange() {
    return ExchangeBuilder.directExchange("exchange.name")
            .durable(true)    // 持久化
            .build();
}

// 2. 队列持久化
@Bean
public Queue queue() {
    return QueueBuilder.durable("queue.name")  // 持久化
            .build();
}

// 3. 消息持久化（默认，消息投递模式设为 PERSISTENT）
// Spring RabbitTemplate 默认就是持久化的
```

```bash
# 命令行验证持久化
rabbitmqadmin list queues name durable
rabbitmqadmin list exchanges name durable
```

### 3.2 惰性队列

```java
// 惰性队列（Lazy Queue）：所有消息直接写到磁盘
// 适合消息堆积、队列很长的场景

@Bean
public Queue lazyQueue() {
    return QueueBuilder.durable("lazy.queue")
            .lazy()                // 惰性队列
            .build();
}

// 设置方式二：通过参数
Map<String, Object> args = new HashMap<>();
args.put("x-queue-mode", "lazy");
new Queue("lazy.queue", true, false, false, args);
```

---

## 四、消费者端可靠性

### 4.1 手动确认

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: manual   # 手动确认
```

```java
@Component
public class ReliableConsumer {
    
    @RabbitListener(queues = "order.queue")
    public void handle(Order order, 
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            // 1. 处理业务
            processOrder(order);
            
            // 2. 手动确认
            channel.basicAck(tag, false);
            
        } catch (BusinessException e) {
            // 业务异常，确认消息但记录（不重试）
            channel.basicAck(tag, false);
            log.error("处理失败: {}", e.getMessage());
            
        } catch (Exception e) {
            // 系统异常，拒绝消息并重新入队
            channel.basicNack(tag, false, true);
        }
    }
}
```

### 4.2 三种确认方式

```java
// 1. 确认（ack）：处理成功
channel.basicAck(deliveryTag, false);
// multiple=false：只确认当前消息
// multiple=true：确认当前及之前所有未确认的消息

// 2. 拒绝（nack + requeue）：处理失败，重新入队
channel.basicNack(deliveryTag, false, true);
// requeue=true：重新入队（可能被同一个消费者再次消费）
// requeue=false：丢弃或进入死信队列

// 3. 拒绝（nack + 不重新入队）：处理失败，进入死信队列
channel.basicNack(deliveryTag, false, false);
```

### 4.3 重试策略

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true          # 开启重试
          max-attempts: 3        # 最大重试次数
          initial-interval: 1000 # 初始间隔
          multiplier: 2          # 间隔倍数（1s → 2s → 4s）
          max-interval: 10000    # 最大间隔
```

```java
// 重试耗尽后的处理（进入死信队列）
@Bean
public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = 
            new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(new Jackson2JsonMessageConverter());
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    
    // 重试策略
    factory.setAdviceChain(RetryInterceptorBuilder.stateless()
            .maxAttempts(3)
            .backOffOptions(1000, 2, 10000)
            .recoverer(new RejectAndDontRequeueRecoverer())  // 重试耗尽后拒绝入队
            .build());
    
    return factory;
}
```

---

## 五、全链路可靠性配置

```yaml
# 生产者端
spring:
  rabbitmq:
    publisher-confirm-type: correlated    # 确认回调
    publisher-returns: true               # 不可路由回调
    template:
      mandatory: true

# 消费者端
    listener:
      simple:
        acknowledge-mode: manual          # 手动确认
        prefetch: 1                       # 每次取一条
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000
          multiplier: 2
```

---

## 六、总结

### 可靠性三层面

| 层面 | 措施 | 解决的问题 |
|------|------|-----------|
| 生产者 | Publisher Confirm + Returns | 消息是否到达 Broker |
| Broker | 持久化（Exchange + Queue + Message）| 宕机后数据不丢失 |
| Broker | 镜像队列 / Quorum 队列 | 节点故障不丢失 |
| 消费者 | 手动 ACK + 重试 | 消息是否处理成功 |

### 最佳实践

```
1. 生产端：Confirm + Returns + 本地消息表
2. Broker：持久化队列 + 镜像队列
3. 消费端：手动 ACK + 重试 + 死信队列
```

**上一篇：** [RabbitMQ（二）：五种消息模型]({{< relref "post/rabbitmq-message-models" >}})

**下一篇：** [RabbitMQ（四）：高级特性]({{< relref "post/rabbitmq-advanced-features" >}})
