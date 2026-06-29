---
title: "分布式系统设计（八）：事件驱动与 CQRS"
date: 2018-09-06
draft: false
categories: ["分布式"]
tags: ["分布式系统", "事件驱动", "CQRS", "事件溯源", "消息驱动"]
toc: true
---

## 前言

事件驱动架构和 CQRS 是微服务架构中重要的设计模式。事件驱动让服务之间通过事件异步通信，CQRS 将读和写操作分离到不同的模型。

<!--more-->

## 一、事件驱动架构

### 1.1 核心思想

```
事件驱动架构（Event-Driven Architecture, EDA）的核心：

服务不直接调用其他服务，而是发布事件。
其他服务订阅感兴趣的事件并进行处理。

发布者（Publisher）：发布事件，不知道谁在处理
订阅者（Subscriber）：订阅事件，不知道谁发布的
事件总线（Event Bus）：消息队列或事件流平台

优点：
- 松耦合：发布者和订阅者互不感知
- 扩展性：可以随时添加新的订阅者
- 可追溯：事件日志记录了系统的所有变更
```

### 1.2 事件 vs 命令

```
命令（Command）：
  明确告诉系统要做什么
  "创建订单"、"扣减库存"
  通常同步执行，等待结果

事件（Event）：
  通知系统已经发生了什么
  "订单已创建"、"库存已扣减"
  通常异步执行，不等待结果

命令是请求（Request），事件是事实（Fact）
```

### 1.3 事件设计

```java
// 事件的结构
public class DomainEvent {
    private String eventId;        // 事件唯一 ID
    private String eventType;      // 事件类型
    private String aggregateId;    // 聚合 ID
    private Map<String, Object> data;  // 事件数据
    private long timestamp;        // 发生时间
    private int version;           // 事件版本
}

// 事件命名规范：{实体}_{动词语态}
OrderCreated       — 订单已创建
OrderPaid          — 订单已支付
InventoryReduced   — 库存已扣减
PaymentRefunded    — 已退款
```

### 1.4 Spring 事件驱动示例

```java
// 1. 事件
public class OrderCreatedEvent {
    private final Long orderId;
    private final Long userId;
    private final BigDecimal amount;
}

// 2. 发布事件
@Service
public class OrderService {
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Transactional
    public void createOrder(OrderRequest request) {
        Order order = orderRepository.save(request.toOrder());
        eventPublisher.publishEvent(
                new OrderCreatedEvent(order.getId(), 
                                      order.getUserId(), 
                                      order.getAmount()));
    }
}

// 3. 订阅事件
@Component
public class OrderEventListener {
    
    @EventListener
    @Async
    public void onOrderCreated(OrderCreatedEvent event) {
        // 发送短信通知
        smsService.sendOrderConfirmation(event.getUserId(), event.getOrderId());
    }
}

// 4. 另一个订阅者
@Component
public class InventoryListener {
    
    @EventListener
    @Async
    public void onOrderCreated(OrderCreatedEvent event) {
        // 扣减库存
        inventoryService.deduct(event.getOrderId());
    }
}
```

---

## 二、CQRS

### 2.1 读写分离

```
CQRS（Command Query Responsibility Segregation）：
将读操作和写操作分离到不同的模型。

传统 CRUD：
  同一个 Model 既处理读（Query）也处理写（Command）

CQRS：
  Command（写）：使用 Command Model
    └── INSERT / UPDATE / DELETE
    └── 返回成功/失败，不返回查询数据

  Query（读）：使用 Query Model
    └── SELECT
    └── 返回查询结果，不修改数据
```

### 2.2 最简单的 CQRS

```java
// Command（写）
public class OrderCommandService {
    
    @Transactional
    public void createOrder(CreateOrderCommand command) {
        Order order = new Order();
        order.setUserId(command.getUserId());
        order.setAmount(command.getAmount());
        orderRepository.save(order);
        
        eventPublisher.publishEvent(new OrderCreatedEvent(order));
    }
}

// Query（读）
public class OrderQueryService {
    
    // 使用专门的查询模型（可以是视图表、缓存、ES）
    public OrderVO findOrder(Long orderId) {
        return orderQueryRepository.findById(orderId);
    }
    
    public Page<OrderVO> findUserOrders(Long userId, Pageable pageable) {
        return orderQueryRepository.findByUserId(userId, pageable);
    }
}
```

### 2.3 分离存储

```
写入库（Command DB）：用于事务操作
  └── MySQL（3NF 规范化）
  └── 只为写入服务优化

读取库（Query DB）：用于查询
  └── Elasticsearch（全文搜索）
  └── Redis（缓存）
  └── MongoDB（反范式化）
  └── MySQL（宽表 + 冗余）

数据同步：
  Command DB → 事件 → Event Bus → 同步到 Query DB

优点：
├── 读写互不干扰（读负载不影响写性能）
├── 读模型可以为特定查询优化
└── 读写可以独立扩展
```

### 2.4 完整 CQRS + 事件驱动架构

```
客户端
  │
  ├── Command（写请求）→ Command Service → Command DB
  │                                            │
  │                                            ▼
  │                                          Event（领域事件）
  │                                            │
  │                                            ▼
  │                                        Event Bus
  │                                            │
  │                    ┌───────────────────────┤
  │                    │                       │
  │                    ▼                       ▼
  │              Event Handler            Event Handler
  │                    │                       │
  │                    ▼                       ▼
  │              Query DB（读）          Other Service
  │                    │
  │                    ▼
  ├── Query（读请求） → Query Service → Query DB
```

---

## 三、事件溯源（Event Sourcing）

### 3.1 传统 vs 事件溯源

```
传统方式（记录当前状态）：
  UPDATE users SET balance = balance - 100 WHERE id = 1
  当前余额 = 900

事件溯源（记录所有变更事件）：
  {"event": "AccountCreated", "balance": 1000}
  {"event": "MoneyWithdrawn", "amount": 100}
  {"event": "MoneyDeposited", "amount": 500}
  当前余额 = 1000 - 100 + 500 = 1400（通过重放事件计算）
```

### 3.2 事件溯源的好处

```
1. 完整审计
   所有状态变更记录在案
   可追溯谁在什么时间做了什么

2. 时间旅行
   可以恢复到任意历史状态
   排查问题时回放到出问题的时间点

3. 事件驱动友好
   事件本身就是发布给其他服务的消息
```

### 3.3 事件溯源的挑战

```
1. 存储量
   事件日志会持续增长
   需要定期创建快照（Snapshot）

2. 事件版本管理
   事件结构会随着业务变化而变更
   需要兼容新旧版本的事件

3. 学习曲线
   与传统的 CRUD 思维不同
   团队需要时间适应

4. 查询困难
   通过重放事件来读取当前状态效率低
   需要配合 CQRS（单独维护读取模型）
```

---

## 四、DDD 与事件驱动

### 4.1 限界上下文

```
DDD 中的限界上下文（Bounded Context）天然对应微服务的边界。

每个限界上下文：
├── 有独立的领域模型
├── 通过事件与其他上下文通信
└── 有自己的数据库

限界上下文之间的通信方式：
├── 事件（推荐）：发布事件，异步通知
├── API：同步调用，请求/响应
└── 共享内核：共享部分模型（谨慎使用）
```

### 4.2 聚合与事件

```
聚合（Aggregate）是 DDD 中的一致性边界。

聚合规则：
1. 一个事务只修改一个聚合
2. 聚合内部保证强一致
3. 聚合之间通过事件实现最终一致

订单聚合：
├── Order（聚合根）
├── OrderItem（实体）
└── OrderAddress（值对象）

修改 Order 聚合时触发的事件：
OrderCreated → Inventory.deduct()
OrderCreated → Payment.debit()
```

---

## 五、总结

### 模式对比

| 模式 | 核心思想 | 适用场景 |
|------|---------|---------|
| 事件驱动 | 异步事件解耦 | 跨服务通信 |
| CQRS | 读写分离 | 读写负载差异大 |
| 事件溯源 | 记录事件而非状态 | 审计、追溯 |
| DDD | 限界上下文 | 复杂业务建模 |

### 推荐路径

```
1. 从事件驱动开始（最简单的解耦方式）
2. 当查询复杂时引入 CQRS（读写分离）
3. 当需要审计追溯时引入事件溯源（记录事件）
4. 使用 DDD 指导服务拆分（限界上下文）
```

**相关阅读：**
- [分布式系统设计（七）：分布式事务]({{< relref "post/distributed-system-transaction" >}})
- [分布式系统设计（一）：设计思想与方法论]({{< relref "post/distributed-system-design-principles" >}})
