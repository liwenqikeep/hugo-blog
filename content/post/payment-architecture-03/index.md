---
title: "支付系统架构设计（三）：支付状态机与生命周期"
date: 2021-10-14
draft: false
categories: ["支付系统"]
tags: ["支付", "状态机", "生命周期", "Spring StateMachine"]
toc: true
---

## 前言

支付过程的本质是**支付单状态的一系列有序流转**。状态管理的好坏直接决定了支付系统的健壮性——状态混乱会导致重复入账、资金损失等严重问题。

本文将深入设计支付系统的状态机模型，涵盖支付单、交易流水、退款单三种核心对象的状态流转，并使用 Spring StateMachine 给出可落地的 Java 实现。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - 本文：支付状态机与生命周期
> - [支付系统架构设计（四）：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - [支付系统架构设计（五）：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})

<!--more-->

## 一、为什么状态机如此重要？

在支付系统中，状态管理的核心挑战在于：

```
未使用状态机时的问题场景：

场景 1：网络超时重试
  1. 用户发起支付 → 调起渠道 → 超时
  2. 后台定时任务检测 → 再次调起渠道
  3. 两次都支付成功 → 重复入账 ❌

场景 2：回调乱序
  1. 支付成功回调到达 → 状态更新为 SUCCESS
  2. 支付失败回调后到 → 状态被覆盖为 FAIL ❌

场景 3：非正常操作
  1. 已退款的订单 → 用户再次点击退款
  2. 没有状态校验 → 二次退款 ❌
```

状态机的核心价值：**任何状态下，能触发什么动作、能流转到什么状态，都在代码中有明确的定义和约束。**

---

## 二、支付单状态机设计

### 2.1 状态定义

```
┌─────────────────────────────────────────────────────────┐
│                  支付单状态定义                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  INIT      ── 待支付：支付单已创建，等待用户确认支付         │
│                                                         │
│  PAYING    ── 支付中：已调起渠道，等待渠道异步通知          │
│                                                         │
│  SUCCESS   ── 支付成功：渠道已确认收款，资金已入账          │
│                                                         │
│  FAIL      ── 支付失败：渠道返回失败或超时未支付            │
│                                                         │
│  CLOSED    ── 已关闭：用户主动取消或超时关闭                │
│                                                         │
│  REFUNDING ── 退款中：正在处理退款                        │
│                                                         │
│  REFUNDED  ── 已退款：已全额退款                          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 2.2 状态流转图

```
                  ┌──────────────────────────────────┐
                  │               INIT                │
                  └──────┬────────────────┬───────────┘
                         │                │
                   用户发起支付        用户取消/超时
                         │                │
                         ▼                ▼
                  ┌──────────┐     ┌──────────┐
                  │  PAYING  │ ──▶ │  CLOSED  │
                  └──┬────┬──┘     └──────────┘
                     │    │
              渠道通知成功 渠道通知失败/超时
                     │    │
                     ▼    ▼
              ┌────────┐  ┌────────┐
              │ SUCCESS│  │  FAIL  │
              └────┬───┘  └────────┘
                   │
             发起退款(全额)
                   │
                   ▼
            ┌──────────┐
            │ REFUNDING│
            └────┬─────┘
                 │
            退款完成
                 │
                 ▼
           ┌─────────┐
           │ REFUNDED│
           └─────────┘
```

### 2.3 状态变更矩阵

每行代表当前状态，每列代表触发事件，交叉点表示目标状态。

| 当前状态 ↓ | 事件：发起支付 | 事件：支付成功 | 事件：支付失败 | 事件：取消关闭 | 事件：发起退款 | 事件：退款完成 |
|-----------|:----------:|:----------:|:----------:|:----------:|:----------:|:----------:|
| **INIT**    | PAYING     | ❌          | ❌          | CLOSED     | ❌          | ❌          |
| **PAYING**  | ❌          | SUCCESS    | FAIL        | ❌          | ❌          | ❌          |
| **SUCCESS** | ❌          | ❌          | ❌          | ❌          | REFUNDING  | ❌          |
| **FAIL**    | PAYING(重试)| ❌          | ❌          | CLOSED     | ❌          | ❌          |
| **CLOSED**  | ❌          | ❌          | ❌          | ❌          | ❌          | ❌          |
| **REFUNDING**| ❌         | ❌          | ❌          | ❌          | ❌          | REFUNDED   |
| **REFUNDED**| ❌         | ❌          | ❌          | ❌          | ❌          | ❌          |

> 注意：FAIL → PAYING 是"重试"场景，需要重试策略（如次数限制、间隔控制），并非无条件允许。

---

## 三、交易流水状态机设计

交易流水和支付单是两个不同维度的概念。支付单是"业务视角"，交易流水是"渠道调用视角"。

### 3.1 状态定义

```
INIT     ── 初始化：流水记录已创建，尚未调起渠道
CALLING  ── 调起中：已发出渠道请求，等待响应
SUCCESS  ── 调用成功：渠道返回成功（注意：不等于支付成功）
FAIL     ── 调用失败：渠道返回失败或请求超时
```

### 3.2 状态流转图

```
                ┌──────┐
                │ INIT │
                └──┬───┘
                   │ 调出渠道
                   ▼
               ┌───────┐
               │CALLING│
               └──┬─┬──┘
                  │ │
            OK ◄──┘ └──► 失败/超时
                  │        │
                  ▼        ▼
             ┌───────┐  ┌──────┐
             │SUCCESS│  │ FAIL │
             └───────┘  └──────┘
                  │        │
                  │        │ 允许重试创建新流水
                  │        │
                  ▼        ▼
                (结束)   (回到 INIT，创建新流水)
```

### 3.3 流水与支付单的关系

```
一条流水对应一次"调起渠道"的尝试。
一笔支付单可以有多条流水（重试场景）。

支付单状态由所有流水综合决定：
  ├── 有一条流水 SUCCESS → 支付单 SUCCESS
  ├── 所有流水 FAIL，且超过重试次数 → 支付单 FAIL
  └── 有一条流水 CALLING，其余 FAIL → 支付单 PAYING
```

---

## 四、退款单状态机设计

### 4.1 状态定义

```
INIT       ── 退款申请已创建，待审批（如需）
PROCESSING ── 退款处理中，已调起渠道退款接口
SUCCESS    ── 退款成功
FAIL       ── 退款失败
CLOSED     ── 退款申请被撤销
```

### 4.2 状态流转图

```
                ┌──────┐
                │ INIT │
                └──┬───┘
                   │ 提交退款
                   ▼
             ┌──────────┐
             │PROCESSING│
             └──┬────┬──┘
                │    │
         退款成功◄┘    └► 退款失败
                │         │
                ▼         ▼
           ┌────────┐  ┌──────┐
           │ SUCCESS│  │ FAIL │
           └────────┘  └──┬───┘
                           │ 重试(新流水)
                           ▼
                     ┌──────────┐
                     │PROCESSING│
                     └──────────┘
```

---

## 五、Spring StateMachine 实现

下面用 Spring StateMachine 来实现支付单状态机。

### 5.1 依赖配置

```xml
<dependency>
    <groupId>org.springframework.statemachine</groupId>
    <artifactId>spring-statemachine-core</artifactId>
    <version>4.0.0</version>
</dependency>
```

### 5.2 状态与事件枚举

```java
package com.payment.core.state;

/**
 * 支付单状态
 */
public enum PayOrderState {
    INIT,          // 待支付
    PAYING,        // 支付中
    SUCCESS,       // 支付成功
    FAIL,          // 支付失败
    CLOSED,        // 已关闭
    REFUNDING,     // 退款中
    REFUNDED       // 已退款
}

/**
 * 支付单事件
 */
public enum PayOrderEvent {
    PAY_START,      // 发起支付
    PAY_SUCCESS,    // 支付成功
    PAY_FAIL,       // 支付失败
    PAY_CANCEL,     // 取消/关闭
    REFUND_START,   // 发起退款
    REFUND_COMPLETE // 退款完成
}
```

### 5.3 状态机配置

```java
package com.payment.core.state;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachine;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachine(name = "payOrderStateMachine")
public class PayOrderStateMachineConfig
        extends StateMachineConfigurerAdapter<PayOrderState, PayOrderEvent> {

    @Override
    public void configure(StateMachineStateConfigurer<PayOrderState, PayOrderEvent> states)
            throws Exception {
        states
            .withStates()
                .initial(PayOrderState.INIT)
                .states(EnumSet.allOf(PayOrderState.class))
                .end(PayOrderState.SUCCESS)
                .end(PayOrderState.FAIL)
                .end(PayOrderState.CLOSED)
                .end(PayOrderState.REFUNDED);
    }

    @Override
    public void configure(
            StateMachineTransitionConfigurer<PayOrderState, PayOrderEvent> transitions)
            throws Exception {
        transitions
            // INIT → PAYING
            .withExternal()
                .source(PayOrderState.INIT)
                .target(PayOrderState.PAYING)
                .event(PayOrderEvent.PAY_START)

            // INIT → CLOSED
            .and().withExternal()
                .source(PayOrderState.INIT)
                .target(PayOrderState.CLOSED)
                .event(PayOrderEvent.PAY_CANCEL)

            // PAYING → SUCCESS
            .and().withExternal()
                .source(PayOrderState.PAYING)
                .target(PayOrderState.SUCCESS)
                .event(PayOrderEvent.PAY_SUCCESS)

            // PAYING → FAIL
            .and().withExternal()
                .source(PayOrderState.PAYING)
                .target(PayOrderState.FAIL)
                .event(PayOrderEvent.PAY_FAIL)

            // FAIL → PAYING（重试）
            .and().withExternal()
                .source(PayOrderState.FAIL)
                .target(PayOrderState.PAYING)
                .event(PayOrderEvent.PAY_START)

            // SUCCESS → REFUNDING
            .and().withExternal()
                .source(PayOrderState.SUCCESS)
                .target(PayOrderState.REFUNDING)
                .event(PayOrderEvent.REFUND_START)

            // REFUNDING → REFUNDED
            .and().withExternal()
                .source(PayOrderState.REFUNDING)
                .target(PayOrderState.REFUNDED)
                .event(PayOrderEvent.REFUND_COMPLETE);
    }
}
```

### 5.4 状态转换监听器

在实际的支付系统中，状态变更往往伴随着副作用——发消息、记账、通知商户等。通过监听器实现关注点分离。

```java
package com.payment.core.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PayOrderStateChangeListener
        extends StateMachineListenerAdapter<PayOrderState, PayOrderEvent> {

    @Override
    public void stateChanged(State<PayOrderState, PayOrderEvent> from,
                              State<PayOrderState, PayOrderEvent> to) {
        PayOrderState fromState = from != null ? from.getId() : null;
        PayOrderState toState = to.getId();

        log.info("支付单状态变更: {} → {}", fromState, toState);

        switch (toState) {
            case SUCCESS -> {
                // 1. 记账入账
                // 2. 发布 PaymentSucceededEvent
                // 3. 通知商户
            }
            case FAIL -> {
                // 1. 判断是否需要重试
                // 2. 发布 PaymentFailedEvent
                // 3. 通知商户
            }
            case REFUNDED -> {
                // 1. 解冻资金
                // 2. 发布 RefundCompletedEvent
            }
        }
    }

    @Override
    public void eventNotAccepted(PayOrderEvent event) {
        log.warn("非法状态转换: 事件 {} 不被当前状态接受", event);
        throw new IllegalStateException("当前状态不允许执行: " + event);
    }
}
```

### 5.5 状态机服务

```java
package com.payment.core.state;

import lombok.RequiredArgsConstructor;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;

/**
 * 支付单状态机服务
 */
@Service
@RequiredArgsConstructor
public class PayOrderStateMachineService {

    private final StateMachine<PayOrderState, PayOrderEvent> stateMachine;

    /**
     * 发送事件，驱动状态变更
     */
    public boolean sendEvent(String orderId, PayOrderEvent event) {
        stateMachine.start();

        // 从持久化恢复当前状态
        // restoreState(orderId);

        boolean accepted = stateMachine.sendEvent(event);

        if (accepted) {
            // 持久化新状态
            // persistState(orderId, stateMachine.getState().getId());
        }

        return accepted;
    }

    /**
     * 获取当前状态
     */
    public PayOrderState getCurrentState() {
        return stateMachine.getState().getId();
    }
}
```

---

## 六、从状态机到状态引擎：生产级设计

Spring StateMachine 在小规模场景下够用，但在高并发支付系统中，直接用其内置的状态持久化方案会有性能问题。一个生产级的状态引擎设计如下：

### 6.1 状态引擎架构

```
                    ┌──────────────────────┐
                    │    支付业务服务        │
                    └────────┬─────────────┘
                             │ 发送事件
                             ▼
                    ┌──────────────────────┐
                    │     状态引擎           │
                    ├──────────────────────┤
                    │  1. 加载当前状态       │ ← 从 DB 读取
                    │  2. 校验状态转换       │ ← 查转换矩阵
                    │  3. 执行转换前后钩子    │ ← 监听器链
                    │  4. 持久化新状态        │ ← 更新 DB
                    │  5. 发布领域事件        │ ← MQ
                    └──────────────────────┘
```

### 6.2 轻量状态机实现（不依赖 Spring StateMachine）

对于生产环境，很多团队选择自研轻量状态机，避免框架依赖：

```java
package com.payment.core.state;

import lombok.Getter;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 轻量级状态机
 */
public class SimpleStateMachine<S, E> {

    /** 状态转换表：当前状态 → (事件 → 目标状态) */
    private final Map<S, Map<E, S>> transitions = new EnumMap<>(getStateType());

    /** 每个状态的进入动作 */
    private final Map<S, Consumer<StateContext<S, E>>> entryActions = new EnumMap<>(getStateType());

    /** 每个状态的退出动作 */
    private final Map<S, Consumer<StateContext<S, E>>> exitActions = new EnumMap<>(getStateType());

    private final Class<S> stateType;
    private final Class<E> eventType;

    public SimpleStateMachine(Class<S> stateType, Class<E> eventType) {
        this.stateType = stateType;
        this.eventType = eventType;
    }

    /**
     * 添加状态转换
     */
    public SimpleStateMachine<S, E> addTransition(S source, E event, S target) {
        transitions.computeIfAbsent(source, k -> new EnumMap<>(eventType))
                .put(event, target);
        return this;
    }

    /**
     * 添加进入动作
     */
    public SimpleStateMachine<S, E> onEntry(S state, Consumer<StateContext<S, E>> action) {
        entryActions.put(state, action);
        return this;
    }

    /**
     * 添加退出动作
     */
    public SimpleStateMachine<S, E> onExit(S state, Consumer<StateContext<S, E>> action) {
        exitActions.put(state, action);
        return this;
    }

    /**
     * 触发事件
     */
    @SuppressWarnings("unchecked")
    private Class<S> getStateType() {
        return stateType;
    }

    public StateContext<S, E> fire(S currentState, E event, String orderId) {
        Map<E, S> stateTransitions = transitions.get(currentState);
        if (stateTransitions == null || !stateTransitions.containsKey(event)) {
            throw new IllegalStateException(
                    String.format("不允许的状态转换: 当前状态=%s, 事件=%s", currentState, event));
        }

        S targetState = stateTransitions.get(event);

        // 执行退出动作
        if (exitActions.containsKey(currentState)) {
            exitActions.get(currentState)
                    .accept(new StateContext<>(currentState, targetState, event, orderId));
        }

        // 执行进入动作
        if (entryActions.containsKey(targetState)) {
            entryActions.get(targetState)
                    .accept(new StateContext<>(currentState, targetState, event, orderId));
        }

        return new StateContext<>(currentState, targetState, event, orderId);
    }

    @Getter
    public static class StateContext<S, E> {
        private final S source;
        private final S target;
        private final E event;
        private final String orderId;

        public StateContext(S source, S target, E event, String orderId) {
            this.source = source;
            this.target = target;
            this.event = event;
            this.orderId = orderId;
        }
    }
}
```

### 6.3 使用示例

```java
// 初始化状态机
SimpleStateMachine<PayOrderState, PayOrderEvent> machine = new SimpleStateMachine<>(
        PayOrderState.class, PayOrderEvent.class);

machine.addTransition(PayOrderState.INIT, PayOrderEvent.PAY_START, PayOrderState.PAYING);
machine.addTransition(PayOrderState.PAYING, PayOrderEvent.PAY_SUCCESS, PayOrderState.SUCCESS);
machine.addTransition(PayOrderState.PAYING, PayOrderEvent.PAY_FAIL, PayOrderState.FAIL);
// ... 更多转换

machine.onEntry(PayOrderState.SUCCESS, ctx -> {
    // 入账处理
    accountingService.credit(ctx.getOrderId());
    // 发布事件
    eventPublisher.publish(new PaymentSucceededEvent(ctx.getOrderId()));
});

// 触发事件
machine.fire(PayOrderState.PAYING, PayOrderEvent.PAY_SUCCESS, "PAY20260706001");
```

---

## 七、重试策略与超时处理

### 7.1 支付重试策略

支付失败后是否重试，需要综合考虑：

```
┌─────────────────────────────────────────────────────────┐
│                  重试决策矩阵                             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  失败原因             是否重试    重试策略                │
│  ├── 网络超时          ✅ 是      指数退避，最多 3 次     │
│  ├── 渠道系统错误      ✅ 是      固定间隔，最多 5 次     │
│  ├── 余额不足          ❌ 否      通知用户换卡             │
│  ├── 风控拒绝          ❌ 否      人工审核                 │
│  ├── 渠道返回参数错误   ❌ 否      修复后重试               │
│  └── 重复支付          ❌ 否      幂等返回                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 7.2 超时关闭机制

```java
/**
 * 超时未支付自动关闭
 * 定时任务，扫描超过 N 分钟仍处于 INIT 状态的支付单
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentTimeoutTask {

    private final PaymentOrderRepository paymentOrderRepository;
    private final StateMachineService stateMachineService;

    /** 超时时间，单位：分钟 */
    private static final long TIMEOUT_MINUTES = 30;

    @Scheduled(fixedRate = 60_000)  // 每分钟执行一次
    public void closeTimeoutOrders() {
        List<PaymentOrder> timeoutOrders = paymentOrderRepository
                .findByStatusAndCreateTimeBefore("INIT", LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES));

        for (PaymentOrder order : timeoutOrders) {
            try {
                stateMachineService.sendEvent(order.getOrderId(), PayOrderEvent.PAY_CANCEL);
                log.info("超时关闭支付单: {}", order.getOrderId());
            } catch (Exception e) {
                log.error("关闭超时支付单失败: {}", order.getOrderId(), e);
            }
        }
    }
}
```

### 7.3 退款重试策略

退款失败通常需要**人工介入**或**定时补偿**：

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class RefundRetryTask {

    private final PaymentOrderRepository paymentOrderRepository;
    private final StateMachineService stateMachineService;

    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 10;

    @Scheduled(fixedRate = 180_000)  // 每 3 分钟
    public void retryFailedRefund() {
        List<PaymentOrder> refundingOrders = paymentOrderRepository
                .findByStatus("REFUNDING");

        for (PaymentOrder order : refundingOrders) {
            if (order.getRetryCount() >= MAX_RETRY_COUNT) {
                log.error("退款重试次数耗尽，需人工介入: {}", order.getOrderId());
                // 触发告警
                alertService.sendAlert("退款重试次数耗尽", order.getOrderId());
                continue;
            }
            // 重新发起退款
            refundService.processRefund(order);
        }
    }
}
```

---

## 八、状态持久化与数据一致性

### 8.1 乐观锁保障

数据库层面的状态变更必须使用乐观锁，防止并发覆盖：

```sql
-- 支付单表核心字段
CREATE TABLE `pay_order` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `order_id` varchar(32) NOT NULL COMMENT '全局订单号',
    `biz_order_no` varchar(64) NOT NULL COMMENT '商户订单号',
    `status` varchar(20) NOT NULL COMMENT '状态',
    `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_biz_order_no` (`biz_order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```java
// 乐观锁更新 SQL
@Update("UPDATE pay_order SET status = #{newStatus}, version = version + 1 " +
        "WHERE order_id = #{orderId} AND status = #{oldStatus} AND version = #{version}")
int updateStatusWithLock(String orderId, String oldStatus, String newStatus, int version);

// 更新结果检查
if (affectedRows == 0) {
    throw new ConcurrentModificationException("并发状态更新冲突: " + orderId);
}
```

### 8.2 状态机 + 乐观锁的完整流程

```
1. 读取支付单（获取当前 status + version）
2. 状态机校验：从 status 能否触发某个事件
3. 生成目标状态 newStatus
4. 执行 SQL：UPDATE ... WHERE order_id=? AND status=? AND version=?
5. 检查 affectedRows：
   ├── 1 → 成功，继续后续处理
   └── 0 → 冲突，抛出异常，重试或报错
```

---

## 九、状态机设计原则总结

### 9.1 原则

| 原则 | 说明 |
|------|------|
| **单向流转** | 支付状态总体是单向的：INIT → PAYING → SUCCESS，尽量避免状态回溯 |
| **显式定义** | 所有允许的状态转换必须在代码中显式定义，不允许隐式流转 |
| **转移即事件** | 每次状态变更都应该发布对应的领域事件 |
| **小状态粒度** | 状态粒度要细，不要用一个大状态涵盖多个子阶段 |
| **幂等转换** | 同一个事件多次触发，结果应该相同 |

### 9.2 常见反模式

```
❌ 反模式 1：直接 UPDATE status

    UPDATE pay_order SET status = 'SUCCESS' WHERE order_id = ?
    → 没有状态校验，任何状态下都可以成功


❌ 反模式 2：在业务代码中大量 if-else

    if (status == INIT) { ... }
    else if (status == PAYING) { ... }
    → 状态逻辑散落在各处，难以维护


❌ 反模式 3：一个状态包含多种含义

    status = 'PROCESSING' 同时代表支付中和退款中
    → 无法区分实际发生了什么


✅ 正确做法：状态机统一管理

    stateMachine.sendEvent(orderId, PayOrderEvent.PAY_SUCCESS)
    → 状态变更集中管控，所有路径一目了然
```

---

## 十、总结

本文设计了支付系统的三层状态机体系——支付单、交易流水、退款单——并给出了 Spring StateMachine 的完整实现和一个生产级的轻量状态机方案。

核心要点：

1. **三层状态机**：支付单（业务视角）、交易流水（渠道调用视角）、退款单（售后视角）
2. **Spring StateMachine**：`@EnableStateMachine` + `StateMachineConfigurerAdapter` 配置转换矩阵
3. **转换监听器**：状态变更触发的副作用（记账、发事件、通知）通过监听器解耦
4. **轻量状态机**：自研 `SimpleStateMachine` 避免框架依赖，适合高并发场景
5. **乐观锁**：`version` 字段保障并发安全，避免状态覆盖

下一篇文章将深入**支付系统的幂等性与一致性设计**，涵盖分布式事务方案和最终一致性保障。

---

> **📚 系列文章导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - 本文：支付状态机与生命周期
> - [下一篇：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - [支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})
> - [对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - [资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})
> - [支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - [支付可观测性]({{< relref "post/payment-architecture-10" >}})
