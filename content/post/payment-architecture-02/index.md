---
title: "支付系统架构设计（二）：支付核心领域建模（DDD）"
date: 2021-10-12
draft: false
categories: ["支付系统"]
tags: ["支付", "DDD", "领域驱动设计", "聚合根", "领域事件"]
toc: true
---

## 前言

第一篇文章我们从宏观层面拆解了支付系统的整体分层架构。但架构的落地始于**领域模型**——只有把业务概念正确地映射为代码模型，系统的后续建设才不会走偏。

本文将使用**领域驱动设计（DDD）** 的方法论，对支付核心领域进行建模。我们将定义限界上下文、识别聚合根、设计领域事件，并最终产出一套可落地的 Java 领域模型。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - 本文：支付核心领域建模（DDD）
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - [支付系统架构设计（四）：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - [支付系统架构设计（五）：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})

<!--more-->

## 一、为什么支付系统需要 DDD？

支付系统本质上是一个**资金流转的规则引擎**，其复杂性体现在：

- **多领域交织**：支付、退款、对账、风控、清算、账户……每个领域都有独立的业务规则
- **状态变更复杂**：一笔支付可能经历多次重试、部分退款、分期等操作
- **跨团队协作**：不同团队负责不同领域，需要清晰的领域边界

DDD 恰好能解决这些问题：

| DDD 要素 | 在支付系统中的应用 |
|---------|-----------------|
| **限界上下文** | 界定支付、账户、风控等领域的边界 |
| **聚合根** | 以支付单、账户、流水为核心管控一致性 |
| **领域事件** | 支付成功、退款完成等事件驱动上下游 |
| **仓储** | 屏蔽持久化细节，领域层只关心模型 |

---

## 二、限界上下文（Bounded Context）

整个支付系统可以拆分为以下几个限界上下文：

```
┌─────────────────────────────────────────────────────────────┐
│                  支付系统 - 限界上下文地图                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────────┐      ┌───────────────────┐          │
│  │   支付上下文       │      │   退款上下文        │          │
│  │  (Payment Context) │      │  (Refund Context)  │          │
│  │                   │      │                   │          │
│  │ 核心概念：         │      │ 核心概念：         │          │
│  │  - 支付单(Payment) │◄────►│  - 退款单(Refund)  │          │
│  │  - 交易流水(Tx)    │      │  - 退款流水        │          │
│  │  - 渠道路由        │      │  - 退款策略        │          │
│  └───────────────────┘      └───────────────────┘          │
│           │                          │                      │
│           ▼                          ▼                      │
│  ┌───────────────────┐      ┌───────────────────┐          │
│  │   账户上下文       │      │   对账上下文        │          │
│  │  (Account Context) │      │ (Reconciliation)   │          │
│  │                   │      │                   │          │
│  │ 核心概念：         │      │ 核心概念：         │          │
│  │  - 账户(Account)  │      │  - 对账单          │          │
│  │  - 流水(Journal)  │      │  - 差异记录         │          │
│  │  - 余额(Balance)  │      │  - 差错处理         │          │
│  └───────────────────┘      └───────────────────┘          │
│           │                          │                      │
│           ▼                          ▼                      │
│  ┌───────────────────┐      ┌───────────────────┐          │
│  │   风控上下文       │      │   通知上下文        │          │
│  │   (Risk Context)  │      │ (Notification)     │          │
│  └───────────────────┘      └───────────────────┘          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 各上下文核心职责

**支付上下文** — 本文的核心焦点
- 负责支付单的创建、状态流转、支付策略执行
- 依赖：渠道上下文（调起支付）、账户上下文（入账）
- 发布事件：`PaymentSucceededEvent`、`PaymentFailedEvent`

**退款上下文**
- 负责退款单的创建和审批、退款策略执行（原路退回/现金退款）
- 依赖：支付上下文（获取原支付单信息）
- 发布事件：`RefundCompletedEvent`

**账户上下文**
- 负责账户管理、余额变更、复式记账
- 依赖：支付上下文（接收入账指令）
- 发布事件：`AccountChangedEvent`

**对账上下文**
- 负责每日对账、差异处理、差错处理
- 独立运行，依赖其他上下文的流水数据

**风控上下文**
- 负责交易风险评估、规则引擎执行
- 被支付上下文调用，返回风控结果

---

## 三、支付上下文 - 核心聚合

我们将支付上下文中的核心领域对象建模为以下聚合：

### 3.1 支付单（PaymentOrder）— 聚合根

支付单是支付上下文中的**聚合根**。用户视角下的"一笔支付"对应一个支付单。

```
┌─────────────────────────────────────────────────────┐
│                   PaymentOrder                      │
│                    （聚合根）                         │
├─────────────────────────────────────────────────────┤
│  - orderId: String        全局唯一订单号              │
│  - bizOrderNo: String     商户订单号                  │
│  - amount: Money          支付金额                    │
│  - status: PayOrderStatus 状态                       │
│  - channel: PayChannel    支付渠道                    │
│  - userId: String         用户 ID                    │
│  - merchantId: String     商户 ID                    │
├─────────────────────────────────────────────────────┤
│  行为：                                              │
│  + create()                创建支付单                 │
│  + startPay()              发起支付                   │
│  + complete()              支付成功完成                │
│  + fail()                  支付失败                    │
│  + close()                 关闭支付单                  │
│  + refund()                发起退款                    │
├─────────────────────────────────────────────────────┤
│  子实体集合：                                         │
│  - transactions: List<Transaction>  支付流水列表       │
└─────────────────────────────────────────────────────┘
```

### 3.2 交易流水（Transaction）— 实体

交易流水记录一次"调起渠道"的尝试。一笔支付可能因为超时重试而有多条流水。

```
┌─────────────────────────────────────────────────────┐
│                   Transaction                        │
│                    （实体）                           │
├─────────────────────────────────────────────────────┤
│  - transactionId: String   流水号                    │
│  - channel: PayChannel     渠道                      │
│  - channelFee: Money       渠道手续费                  │
│  - status: TxStatus        流水状态                   │
│  - channelResponse: JSON   渠道原始响应                │
│  - notifyTime: LocalDateTime  回调时间                │
├─────────────────────────────────────────────────────┤
│  行为：                                              │
│  + initCall()              初始化调起                  │
│  + channelSuccess()        渠道返回成功                │
│  + channelFail()           渠道返回失败                │
│  + retry()                 重试                        │
└─────────────────────────────────────────────────────┘
```

### 3.3 金额（Money）— 值对象

金额是支付系统中最重要的值对象，必须保证类型安全。

```
┌─────────────────────────────────────────────────────┐
│                     Money                           │
│                   （值对象）                          │
├─────────────────────────────────────────────────────┤
│  - amount: Long            金额，单位分               │
│  - currency: Currency      币种                      │
├─────────────────────────────────────────────────────┤
│  行为：                                              │
│  + add(Money)              加法                      │
│  + subtract(Money)         减法                      │
│  + multiply(BigDecimal)    乘法                      │
│  + allocate(Ratio...)      按比例分配                  │
│  + toYuanString()          转元字符串                  │
└─────────────────────────────────────────────────────┘
```

> **为什么用 Long 而非 BigDecimal？** 在支付系统中，金额用"分"为单位存储为 Long，可以避免浮点数精度问题，且计算效率更高。

---

## 四、领域事件（Domain Events）

领域事件是连接不同上下文的桥梁。支付上下文中定义了以下领域事件：

```java
// 支付成功事件
public class PaymentSucceededEvent extends DomainEvent {
    private String paymentOrderId;   // 支付单号
    private String bizOrderNo;       // 商户订单号
    private Money paidAmount;        // 实付金额
    private PayChannel channel;      // 支付渠道
}

// 支付失败事件
public class PaymentFailedEvent extends DomainEvent {
    private String paymentOrderId;
    private String bizOrderNo;
    private String failReason;       // 失败原因
    private PayChannel channel;
}

// 退款完成事件
public class RefundCompletedEvent extends DomainEvent {
    private String refundOrderId;
    private String originalPaymentOrderId;
    private Money refundAmount;
}
```

**发布 / 订阅模式**：

```
PaymentOrder.complete()
    │
    ├── ▶ 状态变更：PAYING → SUCCESS
    │
    ├── ▶ 创建领域事件：PaymentSucceededEvent
    │       │
    │       ├── ▶ 发布到消息队列（Kafka / RocketMQ）
    │       │
    │       ├── ▶ 订阅者：账户上下文（入账）
    │       ├── ▶ 订阅者：通知上下文（通知商户）
    │       └── ▶ 订阅者：风控上下文（更新风控数据）
    │
    └── ▶ 返回结果给调用方
```

---

## 五、领域服务（Domain Services）

当某个行为不属于任何一个聚合根时，应该放在领域服务中。

### 5.1 渠道路由服务（ChannelRoutingService）

```java
// 根据支付请求的参数，决策应该使用哪个支付渠道
public interface ChannelRoutingService {

    /**
     * 路由决策
     * @param request 支付请求
     * @return 路由结果
     */
    RouteResult route(PayRequest request);
}

public class RouteResult {
    private PayChannel channel;       // 选定的渠道
    private Map<String, String> channelConfig;   // 渠道配置参数
    private boolean downgraded;        // 是否降级
}
```

### 5.2 支付策略服务（PaymentStrategyService）

```java
// 处理不同支付场景的策略差异
public interface PaymentStrategyService {

    /**
     * 创建支付单前的预处理
     */
    PaymentOrder preparePayment(PayRequest request);

    /**
     * 调起渠道支付
     */
    ChannelPayResult invokeChannel(PaymentOrder order, Transaction transaction);

    /**
     * 处理渠道回调结果
     */
    void handleChannelCallback(Transaction transaction, ChannelNotify notify);
}
```

### 5.3 幂等服务（IdempotencyService）

```java
public interface IdempotencyService {

    /**
     * 尝试幂等拦截
     * @param idempotentKey 幂等 Key
     * @return true: 已有结果，直接返回；false: 第一次请求
     */
    <T> IdempotentResult<T> tryProcess(String idempotentKey);

    /**
     * 幂等结果保存（支付完成后调用）
     */
    void saveResult(String idempotentKey, Object result);
}
```

---

## 六、仓储接口（Repository）

仓储是领域层与基础设施层的桥梁。在领域层中定义接口，在基础设施层中实现。

```java
public interface PaymentOrderRepository {

    /**
     * 保存支付单
     */
    void save(PaymentOrder order);

    /**
     * 根据 ID 获取支付单
     */
    PaymentOrder findById(String orderId);

    /**
     * 根据商户订单号查询
     */
    PaymentOrder findByBizOrderNo(String bizOrderNo);

    /**
     * 保存交易流水
     */
    void saveTransaction(Transaction transaction);

    /**
     * 更新交易流水
     */
    void updateTransaction(Transaction transaction);
}
```

---

## 七、Java 领域模型实现

下面给出支付上下文的核心领域模型实现。

### 7.1 聚合根：PaymentOrder

```java
package com.payment.core.domain.model;

import com.payment.common.model.BaseEntity;
import com.payment.common.enums.PayOrderStatus;
import com.payment.common.exception.PaymentException;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付单 - 支付上下文的聚合根
 */
@Getter
public class PaymentOrder extends BaseEntity {

    /** 全局唯一订单号 */
    private String orderId;

    /** 商户订单号 */
    private String bizOrderNo;

    /** 支付金额 */
    private Money amount;

    /** 状态 */
    private PayOrderStatus status;

    /** 支付渠道 */
    private String channel;

    /** 用户 ID */
    private String userId;

    /** 商户 ID */
    private String merchantId;

    /** 支付完成时间 */
    private LocalDateTime paidTime;

    /** 交易流水列表 */
    private List<Transaction> transactions;

    /** 失败原因 */
    private String failReason;

    // ========== 工厂方法 ==========

    public static PaymentOrder create(String orderId, String bizOrderNo,
                                       Money amount, String userId,
                                       String merchantId) {
        PaymentOrder order = new PaymentOrder();
        order.orderId = orderId;
        order.bizOrderNo = bizOrderNo;
        order.amount = amount;
        order.userId = userId;
        order.merchantId = merchantId;
        order.status = PayOrderStatus.INIT;
        order.transactions = new ArrayList<>();
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        return order;
    }

    // ========== 领域行为 ==========

    /**
     * 发起支付
     */
    public void startPay(String channel) {
        if (status != PayOrderStatus.INIT) {
            throw PaymentException.paramError("支付单状态异常，无法发起支付");
        }
        this.channel = channel;
        this.status = PayOrderStatus.PAYING;
        this.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 支付成功
     */
    public void complete() {
        if (status != PayOrderStatus.PAYING) {
            throw PaymentException.paramError("支付单状态异常，无法完成支付");
        }
        this.status = PayOrderStatus.SUCCESS;
        this.paidTime = LocalDateTime.now();
        this.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 支付失败
     */
    public void fail(String reason) {
        if (status != PayOrderStatus.PAYING) {
            throw PaymentException.paramError("支付单状态异常");
        }
        this.status = PayOrderStatus.FAIL;
        this.failReason = reason;
        this.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 关闭支付单
     */
    public void close() {
        if (status == PayOrderStatus.SUCCESS || status == PayOrderStatus.REFUNDED) {
            throw PaymentException.paramError("已成功的支付单不可关闭");
        }
        this.status = PayOrderStatus.CLOSED;
        this.setUpdateTime(LocalDateTime.now());
    }

    /**
     * 添加流水
     */
    public void addTransaction(Transaction transaction) {
        this.transactions.add(transaction);
    }
}
```

### 7.2 值对象：Money

```java
package com.payment.core.domain.model;

import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象 - 不可变
 */
@Getter
public class Money {

    /** 金额，单位：分 */
    private final long amount;

    /** 币种 */
    private final Currency currency;

    private static final Currency DEFAULT_CURRENCY = Currency.getInstance("CNY");

    public Money(long amount) {
        this.amount = amount;
        this.currency = DEFAULT_CURRENCY;
    }

    public Money(long amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    // ========== 运算 ==========

    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);
    }

    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount - other.amount, this.currency);
    }

    public Money multiply(int times) {
        return new Money(this.amount * times, this.currency);
    }

    // ========== 工具方法 ==========

    public String toYuanString() {
        BigDecimal yuan = BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return yuan.toString();
    }

    public static Money ofYuan(String yuan) {
        long fen = new BigDecimal(yuan)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return new Money(fen);
    }

    public static Money zero() {
        return new Money(0L);
    }

    private void validateSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "币种不一致: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Money money = (Money) o;
        return amount == money.amount && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }
}
```

### 7.3 实体：Transaction

```java
package com.payment.core.domain.model;

import com.payment.common.model.BaseEntity;
import com.payment.common.enums.TransactionStatus;
import com.payment.common.enums.PayChannel;
import com.payment.common.exception.PaymentException;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 交易流水 - 一次渠道调用记录
 */
@Getter
public class Transaction extends BaseEntity {

    /** 流水号 */
    private String transactionId;

    /** 所属支付单 ID */
    private String paymentOrderId;

    /** 渠道 */
    private String channel;

    /** 渠道手续费 */
    private Long channelFee;

    /** 状态 */
    private TransactionStatus status;

    /** 渠道返回的原始数据 */
    private String channelResponse;

    /** 渠道回调时间 */
    private LocalDateTime notifyTime;

    /** 失败原因 */
    private String failReason;

    /** 扩展参数 */
    private Map<String, String> extra;

    // ========== 工厂方法 ==========

    public static Transaction create(String transactionId, String paymentOrderId,
                                      String channel) {
        Transaction tx = new Transaction();
        tx.transactionId = transactionId;
        tx.paymentOrderId = paymentOrderId;
        tx.channel = channel;
        tx.status = TransactionStatus.INIT;
        tx.extra = new HashMap<>();
        tx.setCreateTime(LocalDateTime.now());
        tx.setUpdateTime(LocalDateTime.now());
        return tx;
    }

    // ========== 领域行为 ==========

    public void initCall() {
        this.status = TransactionStatus.CALLING;
        this.setUpdateTime(LocalDateTime.now());
    }

    public void markSuccess(String channelResponse) {
        this.status = TransactionStatus.SUCCESS;
        this.channelResponse = channelResponse;
        this.notifyTime = LocalDateTime.now();
        this.setUpdateTime(LocalDateTime.now());
    }

    public void markFail(String reason) {
        this.status = TransactionStatus.FAIL;
        this.failReason = reason;
        this.setUpdateTime(LocalDateTime.now());
    }
}
```

### 7.4 领域事件基类

```java
package com.payment.core.domain.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域事件基类
 */
public abstract class DomainEvent {

    /** 事件 ID */
    private final String eventId;

    /** 事件发生时间 */
    private final LocalDateTime occurredOn;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString().replace("-", "");
        this.occurredOn = LocalDateTime.now();
    }

    public String getEventId() {
        return eventId;
    }

    public LocalDateTime getOccurredOn() {
        return occurredOn;
    }
}
```

### 7.5 仓储接口

```java
package com.payment.core.domain.repository;

import com.payment.core.domain.model.PaymentOrder;
import com.payment.core.domain.model.Transaction;

import java.util.Optional;

/**
 * 支付单仓储接口
 */
public interface PaymentOrderRepository {

    void save(PaymentOrder order);

    Optional<PaymentOrder> findById(String orderId);

    Optional<PaymentOrder> findByBizOrderNo(String bizOrderNo);

    void saveTransaction(Transaction transaction);

    void updateTransaction(Transaction transaction);
}
```

---

## 八、领域层结构总览

```
payment-core/src/main/java/com/payment/core/
├── domain/                          # 领域层
│   ├── model/                       # 领域模型
│   │   ├── PaymentOrder.java        # 聚合根：支付单
│   │   ├── Transaction.java         # 实体：交易流水
│   │   └── Money.java               # 值对象：金额
│   ├── event/                       # 领域事件
│   │   ├── DomainEvent.java         # 事件基类
│   │   ├── PaymentSucceededEvent.java
│   │   └── PaymentFailedEvent.java
│   ├── service/                     # 领域服务
│   │   ├── ChannelRoutingService.java
│   │   ├── PaymentStrategyService.java
│   │   └── IdempotencyService.java
│   └── repository/                  # 仓储接口
│       └── PaymentOrderRepository.java
├── application/                     # 应用层
│   └── service/
│       └── PaymentAppService.java   # 支付应用服务
└── infrastructure/                  # 基础设施层
    └── repository/
        └── PaymentOrderRepositoryImpl.java  # 仓储实现
```

---

## 九、总结

本文使用 DDD 对支付系统的核心领域进行建模，要点如下：

1. **限界上下文**：将支付系统拆分为支付、退款、账户、对账、风控、通知等上下文
2. **核心聚合**：`PaymentOrder`（聚合根）、`Transaction`（实体）、`Money`（值对象）
3. **领域事件**：`PaymentSucceededEvent`、`PaymentFailedEvent` 驱动上下游解耦
4. **领域服务**：渠道路由、支付策略、幂等服务
5. **仓储接口**：定义在领域层，实现由基础设施层完成

这套模型的核心思想是：**把业务规则的完整性保护在领域层内部**，不允许外部绕过领域模型直接操作数据。下一篇文章将深入**支付状态机的设计与实现**。

---

> **📚 系列文章导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - 本文：支付核心领域建模（DDD）
> - [下一篇：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - [支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - [支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})
> - [对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - [资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})
> - [支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - [支付可观测性]({{< relref "post/payment-architecture-10" >}})
