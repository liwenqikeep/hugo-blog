---
title: "支付系统架构设计（十）：支付可观测性"
date: 2021-10-28
draft: false
categories: ["支付系统"]
tags: ["支付", "可观测性", "监控", "链路追踪", "告警"]
toc: true
---

## 前言

支付系统就像一个"黑盒子"——你不知道什么时候会出问题，直到用户反馈或对账发现差异。可观测性就是给这个黑盒子装上仪表盘，让我们能够实时了解系统的运行状态。

本文覆盖支付系统的三大可观测性支柱：**日志、指标、链路追踪**，以及支付特有的**业务监控和告警体系**。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（九）：支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - 本文：支付可观测性

<!--more-->

## 一、可观测性三大支柱

```
┌─────────────────────────────────────────────────────────┐
│                  可观测性三大支柱                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Logging (日志)                                          │
│  ├── 业务日志：支付单状态变更、入账记录                      │
│  ├── 访问日志：请求 IP、参数、响应时间                        │
│  └── 错误日志：异常堆栈、失败原因                            │
│                                                         │
│  Metrics (指标)                                          │
│  ├── 业务指标：支付成功率、支付金额、退款率                   │
│  ├── 系统指标：QPS、响应时间、错误率                        │
│  └── 资源指标：CPU、内存、GC                               │
│                                                         │
│  Tracing (链路追踪)                                       │
│  ├── 全链路：从接入层 → 核心层 → 渠道层                     │
│  ├── 跨服务：HTTP/MQ/RPC 调用关系                         │
│  └── 耗时分析：各环节耗时占比                              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 二、业务监控指标体系

### 2.1 核心业务指标

```
支付系统核心指标（实时监控）：

┌─────────────────────────────────────────────────────────┐
│  支付成功率    = 成功支付单数 / 总支付单数  → 目标 ≥ 99%   │
│  支付 QPS      = 每秒支付请求数                           │
│  支付成功 QPS  = 每秒支付成功数                           │
│  平均响应时间  = 支付的 P50/P95/P99 耗时                  │
│  退款率        = 退款金额 / 支付金额 → 目标 ≤ 5%          │
│  渠道可用率    = 各渠道成功/失败比例                       │
└─────────────────────────────────────────────────────────┘
```

### 2.2 Prometheus 指标暴露

```java
@RestController
public class PaymentMetricsController {

    private final MeterRegistry meterRegistry;

    private Counter paymentTotalCounter;
    private Counter paymentSuccessCounter;
    private Counter paymentFailCounter;
    private Timer paymentDurationTimer;

    public PaymentMetricsController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.paymentTotalCounter = meterRegistry.counter("payment.total");
        this.paymentSuccessCounter = meterRegistry.counter("payment.success");
        this.paymentFailCounter = meterRegistry.counter("payment.fail");
        this.paymentDurationTimer = meterRegistry.timer("payment.duration");
    }

    public void recordPaymentResult(String channel, boolean success, long durationMs) {
        paymentTotalCounter.increment();
        if (success) {
            paymentSuccessCounter.increment();
        } else {
            paymentFailCounter.increment();
        }
        paymentDurationTimer.record(Duration.ofMillis(durationMs));
    }
}
```

## 三、链路追踪

### 3.1 核心链路埋点

```java
@Component
@Slf4j
public class PaymentTraceHelper {

    /**
     * 支付全链路追踪埋点
     */
    public void tracePayment(String orderId, String channel) {
        Span span = tracer.spanBuilder("payment-process")
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("order.id", orderId);
            span.setAttribute("channel", channel);

            // Step 1: 创建支付单
            traceStep("create-order");
            // Step 2: 调用渠道
            traceStep("call-channel");
            // Step 3: 处理回调
            traceStep("handle-callback");
            // Step 4: 入账
            traceStep("bookkeeping");

        } finally {
            span.end();
        }
    }

    private void traceStep(String stepName) {
        Span stepSpan = tracer.spanBuilder(stepName)
                .startSpan();
        try (Scope scope = stepSpan.makeCurrent()) {
            // 执行步骤
        } finally {
            stepSpan.end();
        }
    }
}
```

## 四、告警体系

### 4.1 告警规则

```
┌───────────────────────────────────────────────────────────────┐
│  告警等级     |  触发条件                |  响应时间            │
├───────────────────────────────────────────────────────────────┤
│  P0 严重      |  支付成功率 < 90%        |  立即响应（5 分钟）  │
│  P0 严重      |  支付全链路不可用         |  立即响应            │
│  P1 警告      |  支付成功率 < 95%        |  15 分钟内处理       │
│  P1 警告      |  某渠道成功率 < 90%      |  15 分钟内处理       │
│  P2 通知      |  对账出现差异            |  工作日内处理         │
│  P2 通知      |  退款率 > 10%            |  工作日内处理         │
└───────────────────────────────────────────────────────────────┘
```

## 五、可观测性技术栈

| 组件 | 选型 | 用途 |
|------|------|------|
| 日志采集 | Filebeat + ELK | 统一日志收集与检索 |
| 指标监控 | Prometheus + Grafana | 实时指标采集与可视化 |
| 链路追踪 | SkyWalking / Jaeger | 分布式调用链追踪 |
| 告警通知 | AlertManager + 钉钉/企微 | 多渠道告警通知 |
| 业务大盘 | 自研 + Grafana | 支付业务核心看板 |

## 六、总结

可观测性是支付系统运维的"眼睛"。优秀的设计原则：

1. **全链路覆盖**：从用户请求到渠道响应，每一个环节都要可观测
2. **分级告警**：P0/P1/P2 分级，避免告警疲劳
3. **业务视角**：不只是看 CPU/QPS，更要看支付成功率、渠道可用率
4. **数据驱动**：所有监控数据最终服务于快速定位问题和持续优化

---

> **📚 系列文章导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - [支付系统架构设计（四）：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - [支付系统架构设计（五）：支付网关与渠道路由]({{< relref "post/payment-architecture-05" >}})
> - [支付系统架构设计（六）：对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - [支付系统架构设计（七）：资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付系统架构设计（八）：支付安全与风控]({{< relref "post/payment-architecture-08" >}})
> - [支付系统架构设计（九）：支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - 本文：支付可观测性
