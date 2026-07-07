---
title: "支付系统架构设计（六）：对账系统设计"
date: 2021-10-20
draft: false
categories: ["支付系统"]
tags: ["支付", "对账系统", "资金安全", "差异处理"]
toc: true
---

## 前言

对账系统是支付系统的**资金安全最后一道防线**。无论前端的幂等设计多完善、分布式事务方案多严谨，都无法做到 100% 不出差错——网络丢包、渠道漏单、系统 Bug 都可能导致账实不符。对账系统的职责就是**发现这些差异并及时修复**。

本文设计一个完整的对账系统，覆盖文件拉取、逐笔比对、差异处理、差错修复的全流程。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（五）：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})
> - 本文：对账系统设计
> - [资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})

<!--more-->

## 一、对账体系架构

对账系统采用分层设计，分为数据层、对账引擎层、差异处理层、展示层。

```
┌──────────────────────────────────────────────────────────┐
│  展示层         对账报表、差异看板、告警通知                 │
├──────────────────────────────────────────────────────────┤
│  差异处理层    自动修复（补单/调账）+ 人工工单               │
├──────────────────────────────────────────────────────────┤
│  对账引擎层    文件解析 → 数据标准化 → 逐笔比对 → 差异分类   │
├──────────────────────────────────────────────────────────┤
│  数据层        渠道结算文件  │  我方支付流水  │  账户流水   │
└──────────────────────────────────────────────────────────┘
```

## 二、对账核心流程

```
每日对账流程（T+1）：

       ┌──────────┐
       │ 定时触发   │  每日凌晨 02:00
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 拉取渠道文件│  从微信/支付宝/银联 SFTP 拉取结算文件
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 文件解析   │  解析 CSV/Excel/XML 为统一格式
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 数据预处理  │  去重、过滤测试数据
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 逐笔比对   │  以我方数据为基准，逐笔匹配
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 差异分类   │  长款/短款/金额不一致/状态不一致
       └────┬─────┘
            │
       ┌────▼─────┐
       │ 差异处理   │  自动修复 + 人工工单
       └──────────┘
```

## 三、对账引擎核心实现

### 3.1 对账数据模型

```java
/**
 * 对账单 - 统一的对账记录
 */
@Data
public class ReconciliationRecord {
    private String id;
    private String channel;              // 渠道
    private String channelTransactionId; // 渠道交易号
    private String ourTransactionId;     // 我方流水号
    private Long channelAmount;          // 渠道金额（分）
    private Long ourAmount;              // 我方金额（分）
    private LocalDateTime channelTime;   // 渠道交易时间
    private LocalDateTime ourTime;       // 我方交易时间
    private String status;               // 渠道侧状态
    private String ourStatus;            // 我方可状态
}

/**
 * 对账结果
 */
@Data
public class ReconciliationResult {
    private String id;
    private String orderId;
    private String channel;
    private DiffType diffType;           // 差异类型
    private Long channelAmount;
    private Long ourAmount;
    private String channelStatus;
    private String ourStatus;
    private String resolveStatus;        // PENDING / AUTO_FIXED / MANUAL
}

public enum DiffType {
    MATCHED,             // 完全一致
    CHANNEL_ONLY,        // 渠道有，我方无（漏单）
    OUR_ONLY,            // 我方可，渠道无（多记）
    AMOUNT_MISMATCH,     // 金额不一致
    STATUS_MISMATCH      // 状态不一致
}
```

### 3.2 对账引擎

```java
@Component
@Slf4j
public class ReconciliationEngine {

    /**
     * 执行对账
     *
     * @param channelRecords 渠道对账单
     * @param ourRecords     我方流水
     * @return 差异列表
     */
    public List<ReconciliationResult> reconcile(
            List<ReconciliationRecord> channelRecords,
            List<ReconciliationRecord> ourRecords) {

        List<ReconciliationResult> results = new ArrayList<>();

        // 1. 构建渠道侧 Map（key: channelTransactionId）
        Map<String, ReconciliationRecord> channelMap = channelRecords.stream()
                .collect(Collectors.toMap(ReconciliationRecord::getChannelTransactionId, r -> r));

        // 2. 逐笔比对我方流水
        for (ReconciliationRecord our : ourRecords) {
            ReconciliationRecord channel = channelMap.remove(our.getChannelTransactionId());

            if (channel == null) {
                // 我方有，渠道无 → 我方多记
                results.add(buildDiff(our, null, DiffType.OUR_ONLY));
            } else {
                // 双方都有 → 比对金额和状态
                if (!channel.getChannelAmount().equals(our.getOurAmount())) {
                    results.add(buildDiff(our, channel, DiffType.AMOUNT_MISMATCH));
                } else if (!channel.getStatus().equals(our.getStatus())) {
                    results.add(buildDiff(our, channel, DiffType.STATUS_MISMATCH));
                } else {
                    results.add(buildDiff(our, channel, DiffType.MATCHED));
                }
            }
        }

        // 3. 剩余的是渠道有，我方无 → 渠道漏单
        for (ReconciliationRecord channel : channelMap.values()) {
            results.add(buildDiff(null, channel, DiffType.CHANNEL_ONLY));
        }

        return results;
    }
}
```

## 四、差异处理策略

```java
@Component
@Slf4j
public class DiffResolver {

    /**
     * 自动处理差异
     */
    public void autoResolve(ReconciliationResult diff) {
        switch (diff.getDiffType()) {
            case CHANNEL_ONLY -> {
                // 渠道有，我方无 → 主动补单
                log.info("补单: transactionId={}, amount={}",
                        diff.getOrderId(), diff.getChannelAmount());
                supplementOrder(diff);
            }
            case OUR_ONLY -> {
                // 我方有，渠道无 → 查询渠道确认
                log.warn("我方多记，需人工确认: orderId={}", diff.getOrderId());
                createManualTicket(diff);
            }
            case AMOUNT_MISMATCH -> {
                // 金额不一致 → 查询明细，差额调账
                log.warn("金额不一致: orderId={}, channel={}, our={}",
                        diff.getOrderId(), diff.getChannelAmount(), diff.getOurAmount());
                createManualTicket(diff);
            }
            case STATUS_MISMATCH -> {
                // 状态不一致 → 以渠道为准修正
                log.info("修正状态: orderId={}", diff.getOrderId());
                correctStatus(diff);
            }
        }
    }
}
```

## 五、总结

- **三层对账**：内部对账 → 渠道对账 → 资金对账
- **对账引擎**：双端比对、差异分类
- **差异处理**：自动修复（补单/调状态）+ 人工介入
- **频率**：T+1 日对 + T+0 实时监控
