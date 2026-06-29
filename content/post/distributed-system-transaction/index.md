---
title: "分布式系统设计（七）：分布式事务"
date: 2018-09-04
draft: false
categories: ["分布式"]
tags: ["分布式事务", "Saga", "TCC", "幂等性", "最终一致性"]
toc: true
---

## 前言

在单体应用中，事务由数据库保证 ACID。在微服务架构中，一个业务操作可能跨多个服务、多个数据库，传统的本地事务无法满足需求。

**没有万能的分布式事务方案**，只有针对具体场景的权衡选择。

<!--more-->

## 一、分布式事务的场景

```
下单业务示例（跨三个服务）：

订单服务（Order Service）— 创建订单
  │
  ├── 支付服务（Payment Service）— 扣减余额
  │
  └── 库存服务（Inventory Service）— 扣减库存

任何一个环节失败，都需要回滚之前的操作。
这就是分布式事务要解决的问题。
```

---

## 二、常见方案

### 2.1 方案对比

| 方案 | 一致性 | 性能 | 复杂度 | 适用场景 |
|------|:------:|:----:|:------:|---------|
| 两阶段提交（2PC）| 强一致 | 低 | 中 | 短事务、性能不敏感 |
| TCC | 强一致 | 中 | 高 | 短事务、一致性要求高 |
| Saga | 最终一致 | 高 | 中 | 长事务、异步场景 |
| 本地消息表 | 最终一致 | 高 | 低 | 轻量级、简单场景 |
| 事务消息（RocketMQ）| 最终一致 | 高 | 中 | 可靠消息场景 |

### 2.2 两阶段提交（2PC）

```
协调者（Coordinator）负责协调所有参与者。

第一阶段：准备（Prepare）
  Coordinator → 所有 Participant：预备提交？
  Participant：写入 undo/redo log，准备就绪后回复 YES

第二阶段：提交（Commit）
  所有 Participant 回复 YES → Coordinator 发送 Commit
  有 Participant 回复 NO → Coordinator 发送 Rollback

问题：
- 同步阻塞：第二阶段完成前，参与者锁定资源
- 单点问题：Coordinator 宕机
- 数据不一致：部分参与者收不到 Commit/Rollback
```

### 2.3 TCC（Try-Confirm-Cancel）

TCC 将事务的每个操作拆分为三个阶段：

```
Try（预留）：
  检查资源是否足够，预留资源
  如：冻结库存、冻结余额

Confirm（确认）：
  真正执行业务
  如：扣减冻结库存、完成扣款

Cancel（取消）：
  回滚 Try 阶段的操作
  如：释放冻结库存、解冻余额

示例——下单：
Try：
  订单：创建待支付订单
  库存：冻结库存 10 件
  支付：冻结金额 100元

Confirm：
  订单：更新为已支付
  库存：正式扣减 10 件
  支付：完成扣款 100元

Cancel：
  订单：取消订单
  库存：释放冻结 10 件
  支付：解冻金额 100元
```

**TCC 的挑战：**
- 业务侵入性强：每个操作都需要实现 Try/Confirm/Cancel
- 需要考虑空回滚和悬挂问题

### 2.4 Saga 模式

Saga 将一个长事务拆分为多个本地事务，每个本地事务都有对应的补偿事务。

```
正常流程：
  订单创建 → 扣减库存 → 扣减余额 → 订单完成
    T1          T2         T3         T4

回滚流程：
  订单创建失败 → 结束
  扣减库存成功 → 创建订单失败 → 补偿：释放库存
  扣减余额成功 → 扣库存失败 → 补偿：释放库存 + 退款
```

**Saga 的两种实现方式：**

```
编排模式（Choreography）：
  每个服务完成后发消息触发下一个服务
  失败时发消息触发补偿
  优点：去中心化
  缺点：依赖消息队列，复杂流程难以管理

协调模式（Orchestrator）：
  一个协调者负责调用每个服务
  失败时协调者调用补偿
  优点：流程可控
  缺点：协调者是单点
```

```java
// 协调模式示例
@Component
public class CreateOrderSaga {

    @Autowired
    private OrderService orderService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private PaymentService paymentService;

    @Transactional
    public void execute(CreateOrderRequest request) {
        try {
            // 1. 创建订单
            Long orderId = orderService.create(request);
            
            // 2. 扣减库存
            inventoryService.deduct(request.getSkuId(), request.getCount());
            
            // 3. 扣减余额
            paymentService.debit(request.getUserId(), request.getAmount());
            
        } catch (Exception e) {
            // 补偿：按逆序取消已成功的操作
            paymentService.compensateDebit(request.getUserId(), request.getAmount());
            inventoryService.compensateDeduct(request.getSkuId(), request.getCount());
            orderService.cancel(orderId);
            
            throw new SagaException("下单失败", e);
        }
    }
}
```

### 2.5 本地消息表

```
核心思想：将分布式事务转换为本地事务 + 消息重试。

步骤：
1. 在业务数据库中创建消息表
2. 将业务操作和消息插入放在同一个本地事务中
3. 定时任务轮询消息表，发送到 MQ
4. 消费者处理成功后确认消费

业务数据库                    MQ                    下游服务
┌──────────┐              ┌──────────┐           ┌──────────┐
│ 订单表    │              │ 消息队列   │           │ 库存服务  │
│ 消息表    │──本地事务──▶ │          │──发送──▶ │          │
│ 同一事务  │   轮询发送   │          │           │          │
└──────────┘              └──────────┘           └──────────┘
```

---

## 三、幂等性——分布式事务的基础

所有涉及补偿或重试的操作都必须是幂等的。

```java
// 幂等的支付回调处理
public void handlePayCallback(String orderNo, BigDecimal amount) {
    String key = "paid:" + orderNo;
    
    // 1. 幂等性检查（Redis 去重）
    Boolean first = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", Duration.ofDays(3));
    if (!Boolean.TRUE.equals(first)) {
        log.info("订单 {} 已处理，重复回调忽略", orderNo);
        return;
    }
    
    // 2. 业务处理
    orderService.markPaid(orderNo);
}
```

---

## 四、方案选型

### 4.1 选型决策树

```
业务是否需要强一致性？
  ├── 是 → 数据量小？→ 2PC / TCC
  │       └── 数据量大？→ TCC（性能更好）
  │
  └── 否 → 允许回滚？
        ├── 是 → Saga
        └── 否 → 事务消息 / 本地消息表
```

### 4.2 场景推荐

| 场景 | 方案 | 说明 |
|------|------|------|
| 支付扣款 | TCC | 资金操作需要强一致 |
| 下单减库存 | Saga / 事务消息 | 可接受最终一致 |
| 用户注册送积分 | 本地消息表 | 轻量级 |
| 跨行转账 | TCC | 资金操作 |
| 订单取消退款 | Saga | 长流程 |

---

## 五、总结

### 分布式事务核心方案

| 方案 | 核心思想 | 代价 |
|------|---------|------|
| 2PC | 准备 + 提交 | 同步阻塞 |
| TCC | Try + Confirm + Cancel | 业务侵入性强 |
| Saga | 本地事务 + 补偿 | 最终一致 |
| 本地消息表 | 本地事务 + 消息重试 | 需要轮询 |
| 事务消息 | MQ 半消息 + 回调 | 依赖 MQ 能力 |

### 设计建议

```
能用最终一致解决的问题，不要用强一致性方案。

1. 尽量减少跨服务事务的调用次数
2. 先考虑是否真的需要分布式事务
3. 优先选择 Saga 或事务消息（最终一致）
4. TCC 适合资金等强一致场景
5. 所有操作做好幂等性
```

**相关阅读：**
- [分布式系统设计（六）：微服务架构设计]({{< relref "post/distributed-system-microservice" >}})
- [分布式系统设计（八）：事件驱动与 CQRS]({{< relref "post/distributed-system-event-driven-cqrs" >}})
