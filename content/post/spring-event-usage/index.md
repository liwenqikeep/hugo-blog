---
title: "Spring 事件（一）：@EventListener 使用与异步事件"
date: 2018-06-12
draft: false
categories: ["Java"]
tags: ["Spring", "事件机制", "ApplicationEvent", "@EventListener", "@TransactionalEventListener", "异步事件"]
toc: true
---

## 前言

Spring 事件机制是观察者模式在 Spring 框架中的实现。它允许组件之间进行**松耦合通信**——一个组件发布事件，其他组件监听并响应，发布者和监听者之间不需要直接依赖。

从 `ApplicationListener` 接口到 `@EventListener` 注解的演进，再到 `@TransactionalEventListener` 的事务绑定事件，Spring 事件机制越来越灵活。本文将覆盖它的使用方式和进阶技巧。

<!--more-->

## 一、事件机制核心概念

### 1.1 三个核心角色

```
事件：ApplicationEvent
  └── 代表"发生了什么"，是一个普通的 Java 对象
        
发布者：ApplicationEventPublisher
  └── 负责发布事件，任何组件都可以注入并调用 publishEvent()
        
监听者：@EventListener / ApplicationListener
  └── 监听并处理事件，与发布者完全解耦
```

### 1.2 执行流程

```
【同步执行（默认）】
发布者.publishEvent(event)
    │
    ▼
ApplicationEventMulticaster.multicastEvent(event)
    │
    ▼
遍历所有匹配的监听器，逐个调用
    │
    ├── 监听器1.onApplicationEvent(event)  ← 同步
    ├── 监听器2.onApplicationEvent(event)  ← 同步
    └── ...
    │
    ▼
发布者在所有监听器执行完后继续执行

【异步执行（@Async）】
发布者.publishEvent(event)
    │
    ▼
ApplicationEventMulticaster.multicastEvent(event)
    │
    ▼
如果监听器标注了 @Async，提交到线程池执行
    │
    ▼
发布者立即返回（不等待监听器执行完）
```

## 二、自定义事件

### 2.1 创建事件类

```java
// 1. 继承 ApplicationEvent（旧方式）
public class OrderCreatedEvent extends ApplicationEvent {
    
    private final Long orderId;
    private final String orderNo;
    
    public OrderCreatedEvent(Object source, Long orderId, String orderNo) {
        super(source);  // source 通常是发布者对象
        this.orderId = orderId;
        this.orderNo = orderNo;
    }
    
    // getter 方法
}


// 2. 不继承 ApplicationEvent（推荐，Spring 4.2+）
// Spring 会自动包装为 PayloadApplicationEvent
public class OrderCreatedEvent {
    
    private final Long orderId;
    private final String orderNo;
    
    public OrderCreatedEvent(Long orderId, String orderNo) {
        this.orderId = orderId;
        this.orderNo = orderNo;
    }
    
    // getter 方法
}
```

### 2.2 发布事件

```java
@Service
public class OrderService {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;  // 注入发布器
    
    public Order createOrder(OrderRequest request) {
        // 1. 业务逻辑
        Order order = saveOrder(request);
        
        // 2. ★ 发布事件
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), order.getOrderNo()));
        
        return order;
    }
}
```

### 2.3 监听事件——@EventListener

```java
@Component
public class OrderEventListener {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    // 监听 OrderCreatedEvent
    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("订单创建事件: orderId={}, orderNo={}", 
                 event.getOrderId(), event.getOrderNo());
        // 处理逻辑：发送通知、更新统计等
    }
    
    // 同时监听多个事件
    @EventListener({OrderCreatedEvent.class, OrderPaidEvent.class})
    public void handleOrderEvents(Object event) {
        // 两种事件的通用处理
    }
}
```

## 三、@EventListener 详解

### 3.1 SpEL 条件过滤

```java
@Component
public class OrderEventListener {
    
    // 只处理订单金额大于 1000 的事件
    @EventListener(condition = "#event.totalAmount > 1000")
    public void handleLargeOrder(OrderCreatedEvent event) {
        // 大额订单特殊处理
    }
    
    // 使用 SpEL 表达式判断事件来源
    @EventListener(condition = "#event.source == 'import'")
    public void handleImportedOrder(OrderCreatedEvent event) {
        // 只处理导入的订单
    }
}
```

### 3.2 监听多个事件类型

```java
@Component
public class OrderLifecycleListener {
    
    // 监听所有 ApplicationEvent 子类（不推荐，会收到大量无关事件）
    @EventListener
    public void handleAllEvents(Object event) {
        // 任何事件都会被调用
    }
    
    // 监听特定的多个事件
    @EventListener
    public void handleOrderStatusChange(OrderCreatedEvent event) {
        // 订单创建
    }
    
    @EventListener
    public void handleOrderStatusChange(OrderPaidEvent event) {
        // 订单支付
    }
    
    @EventListener
    public void handleOrderStatusChange(OrderShippedEvent event) {
        // 订单发货
    }
}
```

### 3.3 监听器的执行顺序

```java
@Component
public class EmailNotificationListener {
    
    @EventListener
    @Order(1)  // 值越小，优先级越高
    public void sendEmail(OrderCreatedEvent event) {
        // 第一个执行
    }
}

@Component
public class SmsNotificationListener {
    
    @EventListener
    @Order(2)
    public void sendSms(OrderCreatedEvent event) {
        // 第二个执行
    }
}
```

## 四、@TransactionalEventListener——事务绑定事件

### 4.1 问题场景

```java
@Service
public class OrderService {
    
    @Transactional
    public Order createOrder(OrderRequest request) {
        Order order = orderRepository.save(request);
        
        // 发布事件
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), order.getOrderNo()));
        
        return order;
    }
}

@Component
public class EmailListener {
    
    @EventListener
    public void handle(OrderCreatedEvent event) {
        // 问题：此时事务可能还没提交！
        // 如果这里查数据库，可能查不到刚刚插入的数据
        // 如果这里抛异常，事务会回滚（同步监听时）
    }
}
```

### 4.2 @TransactionalEventListener 解决

```java
@Component
public class SafeEventListener {
    
    // 默认：事务提交后才执行
    @TransactionalEventListener
    public void handleAfterCommit(OrderCreatedEvent event) {
        // ★ 此时事务已经提交，数据已持久化
        // 安全地发送邮件、MQ 消息等
    }
    
    // 指定事务阶段
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)     // 提交后（默认）
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)    // 回滚后
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)  // 完成后（提交或回滚）
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)     // 提交前
    
    // 也可以加条件
    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT,
        condition = "#event.totalAmount > 1000"
    )
    public void handleLargeOrderAfterCommit(OrderCreatedEvent event) {
        // 大额订单事务提交后的处理
    }
}
```

**注意：** `@TransactionalEventListener` 要求发布事件的方法必须在事务中执行，否则默认不执行。可以通过设置 `fallbackExecution = true` 让没有事务时也执行：

```java
@TransactionalEventListener(fallbackExecution = true)  // 没有事务时也执行
public void handleWithFallback(OrderCreatedEvent event) {
    // ...
}
```

## 五、异步事件

### 5.1 @Async + @EventListener

```java
@Component
@EnableAsync  // 需要在配置类或启动类上开启异步支持
public class AsyncEventListener {
    
    @Async          // ★ 异步执行
    @EventListener  // 监听事件
    public void handleAsync(OrderCreatedEvent event) {
        // 这个方法在单独的线程中执行
        // 发布者不会等待此方法执行完
        Thread currentThread = Thread.currentThread();
        System.out.println("异步处理: " + currentThread.getName());
        
        // 发送邮件、推送通知等耗时操作
    }
}
```

### 5.2 异步事件与事务

```java
@Service
public class OrderService {
    
    @Transactional
    public void createOrder() {
        // 事务内发布事件
        eventPublisher.publishEvent(new OrderCreatedEvent(...));
        // 事件监听如果是 @Async + @TransactionalEventListener
        // 注意：异步后不再受原事务管理
    }
}

@Component
public class OrderListener {
    
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCreatedEvent event) {
        // 在独立的线程中执行
        // 此时原始事务已提交
        // 如果这里也有 @Transactional，会开启新事务
    }
}
```

## 六、事件机制的最佳实践

### 6.1 事件 vs 消息队列

| 场景 | Spring 事件 | 消息队列（MQ）|
|------|-----------|-------------|
| 适用 | 同进程内解耦 | 跨进程/跨服务 |
| 可靠性 | 进程内可靠（重启丢失）| 持久化保证 |
| 顺序 | JVM 内保证顺序 | 需额外配置顺序 |
| 复杂度 | 低 | 高 |
| 典型场景 | 发邮件、记录日志、更新缓存 | 跨服务通知、数据同步 |

### 6.2 事件类设计

```java
// ✅ 推荐：POJO 类，不继承 ApplicationEvent
public class OrderCreatedEvent {
    private final Long orderId;
    private final String orderNo;
    private final LocalDateTime createdAt;
    
    public OrderCreatedEvent(Long orderId, String orderNo) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.createdAt = LocalDateTime.now();
    }
    
    // getter
}

// ❌ 旧方式：继承 ApplicationEvent
// 需要传 source 参数，序列化不方便
```

### 6.3 不要过度使用事件

```java
// ✅ 适合用事件的场景：跨模块、跨组件、不需要返回结果
// 订单创建 → 发邮件、发短信、记录审计日志

// ❌ 不适合用事件的场景：需要返回结果、强依赖
// 用户注册 → 需要知道是否发送成功（用接口调用）
```

### 6.4 事件监听注意事项

```java
@EventListener
public void handle(OrderCreatedEvent event) {
    try {
        // ★ 在监听器中抛异常会影响发布者
        // 如果同步监听，异常会传播到发布者
        // 如果有事务，会导致事务回滚
        doSomething();
    } catch (Exception e) {
        // ★ 建议：在监听器内部 catch 异常
        // 或者使用 @Async 异步执行
        log.error("事件处理异常", e);
    }
}
```

## 七、总结

### 核心注解速查

| 注解 | 作用 |
|------|------|
| `@EventListener` | 声明事件监听方法 |
| `@TransactionalEventListener` | 事务绑定的事件监听 |
| `@Async` + `@EventListener` | 异步事件监听 |
| `@Order` | 监听器执行顺序 |

### 事件阶段速查

| TransactionPhase | 说明 |
|-----------------|------|
| `AFTER_COMMIT` | 事务提交后（默认）|
| `AFTER_ROLLBACK` | 事务回滚后 |
| `AFTER_COMPLETION` | 事务完成后（提交或回滚）|
| `BEFORE_COMMIT` | 事务提交前 |

### 最佳实践清单

1. **事件类用 POJO**，不强制继承 `ApplicationEvent`
2. **事务绑定用 `@TransactionalEventListener`**，避免事务未提交就触发
3. **耗时操作异步处理**，用 `@Async` 避免阻塞发布者
4. **监听器自己处理异常**，避免影响发布者的事务
5. **同进程解耦用事件**，跨进程用 MQ

---

**下一篇：** [Spring 事件（二）：事件发布与监听源码深度解析]({{< relref "post/spring-event-source-code" >}})
