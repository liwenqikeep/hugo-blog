---
title: "支付系统架构设计（四）：支付幂等性与一致性"
date: 2021-10-16
draft: false
categories: ["支付系统"]
tags: ["支付", "幂等性", "分布式事务", "TCC", "Saga", "最终一致性"]
toc: true
---

## 前言

在分布式支付系统中，**网络是注定不可靠的**。超时、重试、消息重复是常态——这意味着我们需要在系统设计的每一层都做好安全保障。

本文系统地梳理支付系统中的幂等性和一致性保障方案。幂等性解决"同一个操作执行多次"的问题，一致性解决"多个系统之间的数据同步"的问题。两者结合，构成了支付系统的**资金安全防线**。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - 本文：支付幂等性与一致性
> - [支付系统架构设计（五）：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})

<!--more-->

## 一、幂等性：同一个操作执行多次的结果相同

### 1.1 支付系统中需要幂等的场景

```
场景 1：支付回调重复通知
  微信支付成功回调 → 网络抖动 → 微信重发回调
  → 如果重复入账，商户收到两笔钱 ❌

场景 2：前端重复提交
  用户支付时点击"确认"按钮没反应 → 又点了一次
  → 发起两笔支付请求 ❌

场景 3：MQ 消息重复消费
  支付成功消息 → 消费者处理 → 超时 → MQ 重投
  → 通知商户两次，生成两条出账记录 ❌

场景 4：退款重试
  退款接口超时 → 定时任务重试
  → 发起两次退款 ❌
```

### 1.2 幂等性分级

| 级别 | 方案 | 适用场景 | 性能影响 |
|------|------|---------|---------|
| 最强 | 数据库唯一键约束 | 插入类操作（创建支付单、记账流水） | 低 |
| 中 | 状态机前置校验 | 更新类操作（支付成功回调） | 极低 |
| 中 | 去重表（Redis + DB） | MQ 消息消费 | 低 |
| 弱 | Token 机制（前端兜底） | 前端重复提交 | 极低（一次额外请求） |

---

## 二、幂等方案一：数据库唯一键约束

### 2.1 原理

对可能重复插入的数据，建立唯一键约束。重复插入时由数据库层面拦截。

```sql
-- 支付流水表：一个 payment_order_id + channel + channel_transaction_id 唯一确定一条流水
CREATE TABLE `pay_transaction` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `transaction_id` varchar(32) NOT NULL COMMENT '流水号',
    `payment_order_id` varchar(32) NOT NULL COMMENT '支付单号',
    `channel` varchar(20) NOT NULL COMMENT '支付渠道',
    `channel_transaction_id` varchar(64) DEFAULT NULL COMMENT '渠道交易号',
    `status` varchar(20) NOT NULL COMMENT '流水状态',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_order_channel_tx` (`payment_order_id`, `channel`, `channel_transaction_id`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 记账流水表：一个流水号 + 账目类型唯一确定一条记账记录
CREATE TABLE `account_journal` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `journal_no` varchar(32) NOT NULL COMMENT '流水号',
    `transaction_id` varchar(32) NOT NULL COMMENT '支付流水号',
    `account_no` varchar(32) NOT NULL COMMENT '账号',
    `amount` bigint NOT NULL COMMENT '金额（分）',
    `journal_type` varchar(20) NOT NULL COMMENT '类型：CREDIT/DEBIT',
    `status` varchar(20) NOT NULL COMMENT '状态',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tx_journal_type` (`transaction_id`, `journal_type`),
    KEY `idx_account_no` (`account_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.2 Java 实现

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountJournalService {

    private final AccountJournalMapper journalMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 入账（幂等）
     * 利用 UNIQUE KEY 保证同一个流水不会重复入账
     */
    public JournalResult credit(String transactionId, String accountNo, Money amount) {
        // 生成记账流水号
        String journalNo = "J" + transactionId;

        try {
            AccountJournal journal = new AccountJournal();
            journal.setJournalNo(journalNo);
            journal.setTransactionId(transactionId);
            journal.setAccountNo(accountNo);
            journal.setAmount(amount.getAmount());
            journal.setJournalType("CREDIT");
            journal.setStatus("SUCCESS");

            journalMapper.insert(journal);
            log.info("入账成功: transactionId={}, amount={}", transactionId, amount.toYuanString());
            return JournalResult.success(journal);

        } catch (DuplicateKeyException e) {
            // 唯一键冲突 → 已入账，直接返回成功
            log.info("入账幂等命中: transactionId={}", transactionId);
            return JournalResult.duplicated();
        }
    }
}
```

---

## 三、幂等方案二：状态机前置校验

支付单状态机本身就是最强力的一道幂等防线。

```
支付成功回调处理流程：

1. 根据 transactionId 找到 Transaction
2. 根据 Transaction 找到 PaymentOrder
3. 检查 PaymentOrder.status：
   ├── SUCCESS → 已处理，直接返回成功（幂等命中）
   ├── PAYING  → 首次处理，继续后续
   └── 其他     → 状态异常，拒绝处理
4. 执行入账、发事件等操作
5. 调用状态机: stateMachine.sendEvent(PAY_SUCCESS)
```

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class PayCallbackService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final PayOrderStateMachineService stateMachineService;
    private final AccountJournalService accountJournalService;
    private final DomainEventPublisher eventPublisher;

    /**
     * 处理支付成功回调（幂等）
     */
    @Transactional
    public PaymentOrder handlePayCallback(String transactionId, String channelResult) {
        // 1. 查询流水
        Transaction tx = findTransaction(transactionId);
        PaymentOrder order = paymentOrderRepository.findById(tx.getPaymentOrderId())
                .orElseThrow(() -> new RuntimeException("支付单不存在"));

        // 2. 幂等校验：已经成功的不再处理
        if ("SUCCESS".equals(order.getStatus())) {
            log.info("幂等回调: 支付单已成功, orderId={}", order.getOrderId());
            return order;
        }

        // 3. 只处理 PAYING 状态的订单
        if (!"PAYING".equals(order.getStatus())) {
            throw PaymentException.paramError("支付单状态异常: " + order.getStatus());
        }

        // 4. 更新流水
        tx.markSuccess(channelResult);
        paymentOrderRepository.updateTransaction(tx);

        // 5. 入账（幂等，利用唯一键）
        JournalResult result = accountJournalService.credit(
                transactionId, order.getMerchantId(), order.getAmount());
        if (result.isDuplicated()) {
            log.warn("入账幂等命中，但支付单状态仍需更新: {}", order.getOrderId());
        }

        // 6. 状态机：驱动 PAYING → SUCCESS
        boolean accepted = stateMachineService.sendEvent(order.getOrderId(), PayOrderEvent.PAY_SUCCESS);
        if (!accepted) {
            throw PaymentException.systemError("状态机拒绝状态转换");
        }

        // 7. 发布领域事件
        eventPublisher.publish(new PaymentSucceededEvent(
                order.getOrderId(), order.getBizOrderNo(), order.getAmount(), order.getChannel()));

        return order;
    }
}
```

---

## 四、幂等方案三：去重表

### 4.1 原理：Redis 去重 + DB 兜底

大部分支付业务不需要强一致性去重，用 Redis 做第一道防线，DB 唯一键做第二道防线：

```
请求到达时：
           ┌──────────┐
           │  Redis    │
           │ SETNX key │
           └────┬─────┘
                │
         ┌──────┴──────┐
         │             │
     成功(1)         失败(0)
         │             │
         ▼             ▼
   继续处理        已处理过
         │          直接返回
         ▼
    DB 写入
    (唯一键兜底)
```

### 4.2 实现

```java
@Service
@Slf4j
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final String IDEMPOTENT_KEY_PREFIX = "idempotent:";
    private static final long KEY_TTL_SECONDS = 86400L; // 24小时

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public <T> T tryProcess(String idempotentKey) {
        String key = IDEMPOTENT_KEY_PREFIX + idempotentKey;
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            // 已有结果，反序列化返回
            log.debug("幂等命中: key={}", idempotentKey);
            return JsonUtils.parse(value, null);
        }
        return null;
    }

    @Override
    public void saveResult(String idempotentKey, Object result) {
        String key = IDEMPOTENT_KEY_PREFIX + idempotentKey;
        String value = JsonUtils.toJson(result);
        redisTemplate.opsForValue().set(key, value, KEY_TTL_SECONDS, TimeUnit.SECONDS);
    }
}
```

---

## 五、幂等方案四：Token 机制（前端兜底）

### 5.1 原理

每次进入支付页面时，先向后端申请一个 token。提交支付时必须携带该 token，后端验证 token 有效后才处理。

```
1. 用户进入支付页面 ──▶ 后端生成 token，存入 Redis ──▶ 返回给前端
2. 用户提交支付 ──────▶ 携带 token ──▶ 后端验证 token
                           │
                     ┌─────┴─────┐
                     │           │
                 token 有效    token 无效/已用
                     │           │
                     ▼           ▼
                继续处理      拒绝处理（重复提交）
                删除 token
```

### 5.2 实现

```java
@Service
public class PaymentTokenService {

    private static final String TOKEN_PREFIX = "pay_token:";
    private static final long TOKEN_TTL = 300L; // 5 分钟

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 生成支付 token
     */
    public String generateToken(String bizOrderNo) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                TOKEN_PREFIX + token, bizOrderNo, TOKEN_TTL, TimeUnit.SECONDS);
        return token;
    }

    /**
     * 验证并消费 token（幂等）
     * @return true: token 有效且未被使用
     */
    public boolean consumeToken(String token) {
        String key = TOKEN_PREFIX + token;
        // DEL 返回 >0 表示 key 存在且被删除
        Long result = redisTemplate.delete(key);
        return result != null && result > 0;
    }
}
```

---

## 六、分布式事务方案

幂等性解决的是"同一个操作"的重复问题。但支付系统的另一个核心问题是"多个不同操作之间"的一致性——比如扣款和入账需要保持一致。

### 6.1 支付系统的一致性要求

```
支付成功的完整链路涉及多个独立系统：

用户账户 ─(扣款)──▶ 支付渠道 ─(回调)──▶ 支付系统 ─(入账)──▶ 商户账户

┌─────────────────────────────────────────────────────────┐
│  需要保证一致性的操作组合：                                │
│                                                         │
│  组合 1：支付单状态 SUCCESS + 商户入账                     │
│  组合 2：支付单状态 FAIL    + 用户资金解冻                  │
│  组合 3：退款单状态 SUCCESS + 商户扣款 + 用户入账           │
└─────────────────────────────────────────────────────────┘
```

### 6.2 TCC 模式（Try-Confirm-Cancel）

TCC 是支付系统中最常用的事务模式之一。它通过业务层面的补偿，保证跨系统的数据一致性。

```
TCC 三个阶段：

Try 阶段：
   冻结用户账户中的支付金额（不扣款，只是锁定）
   创建一条状态为 INIT 的支付单

Confirm 阶段：
   从冻结资金中扣款
   将支付单状态更新为 SUCCESS
   解冻剩余资金（如果有）

Cancel 阶段：
   解冻被冻结的资金
   将支付单状态更新为 FAIL
```

**为什么要 Try？** 如果不 Try 直接扣款，Confirm 失败后需要逆向操作：

```
❌ 没有 Try：直接扣款
    扣款成功 → Confirm 入账失败 → 需要退款（逆向操作复杂）

✅ 有 Try：先冻结
    冻结成功 → Confirm 入账 → 解冻
    Confirm 失败 → Cancel 解冻（不涉及资金变动）
```

### 6.3 使用 Seata TCC 实现

```java
package com.payment.core.tcc;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * TCC 支付接口
 */
@LocalTCC
public interface PaymentTccAction {

    /**
     * Try：冻结资金
     *
     * @param actionContext Seata 上下文
     * @param orderId       支付单号
     * @param amount        金额（分）
     * @return true: 冻结成功；false: 冻结失败
     */
    @TwoPhaseBusinessAction(name = "paymentTccAction",
            commitMethod = "confirm", rollbackMethod = "cancel")
    boolean prepare(BusinessActionContext actionContext,
                    String orderId, long amount);

    /**
     * Confirm：确认扣款
     */
    boolean confirm(BusinessActionContext actionContext);

    /**
     * Cancel：解冻资金
     */
    boolean cancel(BusinessActionContext actionContext);
}
```

```java
package com.payment.core.tcc;

import io.seata.rm.tcc.api.BusinessActionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TCC 支付实现 - 资金冻结与扣款
 */
@Slf4j
@Component
public class PaymentTccActionImpl implements PaymentTccAction {

    @Autowired
    private AccountFreezeMapper freezeMapper;

    @Override
    @Transactional
    public boolean prepare(BusinessActionContext ctx, String orderId, long amount) {
        String userId = ctx.getActionContext("userId", String.class);

        // 冻结资金：插入冻结记录
        AccountFreeze freeze = new AccountFreeze();
        freeze.setOrderId(orderId);
        freeze.setUserId(userId);
        freeze.setAmount(amount);
        freeze.setStatus("FROZEN");

        int rows = freezeMapper.insert(freeze);
        log.info("Try 冻结资金: orderId={}, userId={}, amount={}", orderId, userId, amount);
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean confirm(BusinessActionContext ctx) {
        String orderId = ctx.getActionContext("orderId", String.class);

        // 确认扣款：删除冻结记录（表示资金已扣除）
        freezeMapper.deleteByOrderId(orderId);
        log.info("Confirm 确认扣款: orderId={}", orderId);
        return true;
    }

    @Override
    @Transactional
    public boolean cancel(BusinessActionContext ctx) {
        String orderId = ctx.getActionContext("orderId", String.class);

        // 取消：删除冻结记录（资金回流）
        freezeMapper.deleteByOrderId(orderId);
        log.info("Cancel 解冻资金: orderId={}", orderId);
        return true;
    }
}
```

### 6.4 Saga 模式

相对于 TCC 需要业务方实现 Try/Confirm/Cancel，Saga 模式更轻量——它通过**本地事务 + 补偿操作**来实现分布式事务。

```
Saga 执行流程：

正向操作（执行本地事务）：
  Step 1：创建支付单（本地事务）
  Step 2：调用渠道扣款（外部操作）
  Step 3：商户入账（本地事务）

如果 Step 2 失败：
  执行逆序补偿：
  Step 3 补偿：无（尚未执行）
  Step 2 补偿：调用退款接口退钱
  Step 1 补偿：将支付单置为 FAIL
```

**Saga vs TCC 选择：**

| 维度 | TCC | Saga |
|------|-----|------|
| 一致性级别 | 强一致性（两阶段） | 最终一致性 |
| 业务侵入 | 高：需实现 Try/Confirm/Cancel | 低：只需正向 + 补偿 |
| 性能 | 低：两阶段提交，锁定资源 | 高：一阶段提交 |
| 适用场景 | 核心资金操作 | 非核心链路 |
| 典型支付场景 | 扣款 + 入账强一致 | 支付成功后的积分、短信等 |

### 6.5 基于 MQ 的最终一致性方案

对于非核心链路（通知商户、发送短信、更新统计等），使用 MQ 最终一致性即可满足要求。

```
┌────────────┐       ┌────────────┐       ┌────────────┐
│  支付成功    │       │   消息队列   │       │   消费者    │
│             │       │  (RocketMQ) │       │            │
│  1. 本地事务  │       │            │       │            │
│    更新支付单 ├──────▶│  持久化消息  ├──────▶│  幂等消费   │
│    发送消息   │       │            │       │  入账/通知  │
│    合并操作   │       │            │       │            │
└────────────┘       └────────────┘       └────────────┘
```

```java
// 事务消息 - RocketMQ 示例
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentTransactionalMessageService {

    private final RocketMQTemplate rocketMQTemplate;
    private final PaymentOrderRepository paymentOrderRepository;

    /**
     * 本地事务 + 发送消息
     */
    @Transactional
    public void processPaymentSuccess(PaymentOrder order) {
        // 1. 本地事务：更新支付单状态
        order.complete();
        paymentOrderRepository.save(order);

        // 2. 发送事务消息（RocketMQ 事务消息）
        // 如果发送失败，本地事务回滚
        Message<PaymentSucceededEvent> message = MessageBuilder
                .withPayload(new PaymentSucceededEvent(
                        order.getOrderId(), order.getBizOrderNo(),
                        order.getAmount(), order.getChannel()))
                .build();

        rocketMQTemplate.sendMessageInTransaction(
                "payment-topic",
                message,
                order.getOrderId()
        );
    }
}
```

---

## 七、对账：一致性的最终防线

无论设计多完善的分布式事务方案，都不能 100% 保证所有情况下都不出问题。**对账是保障资金一致性的最终防线。**

### 7.1 对账体系

```
对账的三层防线：

第一层：实时对账（T+0）
   每次支付完成后，对比：
     └── 支付系统记录 vs 渠道返回结果

第二层：每日对账（T+1）
   每日凌晨，对比：
     └── 支付系统流水 vs 渠道结算文件

第三层：资金对账（T+1）
   每日，对比：
     └── 账户余额 vs 实际入账金额
```

### 7.2 对账差异处理

```
对账差异类型与处理方案：

┌──────────────────────────────────────────────────────┐
│  我方有，渠道无  →  渠道漏单 → 查询渠道确认 → 补单     │
│                                                      │
│  我方无，渠道有  →  我方漏记 → 主动补记                 │
│                                                      │
│  金额不一致      →  手续费/优惠 → 调整差额            │
│                                                      │
│  状态不一致      →  查询渠道 → 修正我方状态             │
│                                                      │
│  长款（渠道多收） →  发起退款                          │
│                                                      │
│  短款（渠道少付） →  向渠道发起争议                     │
└──────────────────────────────────────────────────────┘
```

---

## 八、支付系统的安全防护

### 8.1 幂等防重放

```java
/**
 * 支付回调幂等防重放
 * 利用 payment_order_id + callback_time 双重判断
 */
public class CallbackIdempotencyFilter {

    /** 最近 N 秒内的相同回调，认为是重放 */
    private static final long REPLAY_WINDOW_SECONDS = 5;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 检查是否是重放攻击
     */
    public boolean isReplayAttack(String orderId, String channelTxId, LocalDateTime callbackTime) {
        String key = "callback:" + orderId + ":" + channelTxId;
        String lastTime = redisTemplate.opsForValue().get(key);

        if (lastTime != null) {
            LocalDateTime lastCallback = LocalDateTime.parse(lastTime);
            // 5 秒内的相同回调，认为是重放
            if (Duration.between(lastCallback, callbackTime).getSeconds() < REPLAY_WINDOW_SECONDS) {
                log.warn("检测到回调重放攻击: orderId={}, channelTxId={}", orderId, channelTxId);
                return true;
            }
        }

        redisTemplate.opsForValue().set(key, callbackTime.toString(), 1, TimeUnit.HOURS);
        return false;
    }
}
```

### 8.2 幂等设计的"黄金法则"

```
支付幂等设计五条黄金法则：

1. 唯一键是根基
   任何可重复的操作，必须有唯一的业务键
   └── 支付单号、流水号、渠道交易号

2. 状态机是第一道防线
   状态机天然幂等：同一事件不会重复生效
   └── SUCCESS 状态的支付单，不会再次被成功事件驱动

3. 读-写分离判断
   先读状态判断是否已处理，再写数据
   └── 读校验比重复写更轻量

4. 补偿机制兜底
   所有幂等方案都有失效的可能
   └── 对账是最的最终保障

5. 记录日志审计
   每一次幂等命中都要记录日志
   └── 用于事后审计和问题排查
```

---

## 九、总结

本文系统地梳理了支付系统幂等性和一致性的设计方案：

### 幂等方案矩阵

| 场景 | 推荐方案 | 关键依赖 |
|------|---------|---------|
| 创建支付单/流水 | 唯一键约束 | DB UNIQUE KEY |
| 支付回调处理 | 状态机 + 唯一键 | 乐观锁 |
| MQ 消息消费 | Redis 去重 + DB 兜底 | SETNX |
| 前端重复提交 | Token 机制 | Redis DEL |
| 退款处理 | 状态机 + 退款单唯一键 | 状态校验 |

### 分布式事务选择

| 场景 | 方案 | 一致性程度 |
|------|------|-----------|
| 扣款 + 入账 | TCC（Seata） | 强一致性 |
| 非核心链路通知 | MQ 最终一致性 | 最终一致性 |
| 跨系统资金操作 | Saga 模式 | 最终一致性 |
| 兜底保障 | 对账系统 | 最终一致性 |

### 设计哲学

```
支付系统一致性设计的核心理念：
"不信任任何一次调用，为每个操作都做好重试和补偿的准备。"

幂等性让你安全地重试。
分布式事务让你安全地跨系统操作。
对账让你在出错后能及时发现和修复。

三者结合，才是完整的资金安全保障体系。
```

---

> **📚 系列文章导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - 本文：支付幂等性与一致性
> - [下一篇：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})
> - [对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - [资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})
> - [支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - [支付可观测性]({{< relref "post/payment-architecture-10" >}})
