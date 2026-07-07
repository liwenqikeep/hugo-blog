---
title: "支付系统架构设计（五）：支付网关与渠道路由"
date: 2021-10-18
draft: false
categories: ["支付系统"]
tags: ["支付", "支付网关", "渠道路由", "适配器模式", "降级"]
toc: true
---

## 前言

支付网关是支付系统的"外交官"——它负责与外部支付渠道（微信、支付宝、银联等）打交道。一个设计良好的支付网关，可以在不改变核心业务逻辑的前提下，灵活地接入新渠道、切换主备渠道、实现灰度发布和降级。

本文将从架构设计到代码实现，系统地拆解支付网关的完整设计。

> **系列导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - [支付系统架构设计（四）：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - 本文：支付网关与渠道路由

<!--more-->

## 一、支付网关的职责

支付网关位于核心业务层和外部支付渠道之间，承担以下职责：

```
┌─────────────────────────────────────────────────────────┐
│                  支付网关职责全景                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  核心职责：                                               │
│  ┌─────────────────────────────────────────────────────┐│
│  │ 1. 协议转换    内部对象 ↔ 渠道报文格式                  ││
│  │ 2. 签名验签    渠道身份认证、数据完整性校验                ││
│  │ 3. 路由决策    选择最优渠道                             ││
│  │ 4. 重试降级    超时重试、渠道降级                       ││
│  │ 5. 回调解析    渠道异步通知的统一处理                    ││
│  └─────────────────────────────────────────────────────┘│
│                                                         │
│  非核心职责（不应放在网关层）：                              │
│  ┌─────────────────────────────────────────────────────┐│
│  │ ✗ 业务逻辑（状态机、风控）                             ││
│  │ ✗ 资金计算（手续费、分账）                             ││
│  │ ✗ 持久化（不直接操作数据库）                           ││
│  └─────────────────────────────────────────────────────┘│
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 二、渠道适配器模式

适配器模式是支付网关的核心设计模式：每个渠道实现一套统一的接口，网关层只依赖抽象，不依赖具体实现。

### 2.1 统一抽象接口

```java
package com.payment.channel.spi;

/**
 * 渠道适配器接口
 * 所有支付渠道必须实现此接口
 */
public interface ChannelAdapter {

    /**
     * 渠道标识
     */
    String getChannel();

    /**
     * 发起支付
     */
    PayResponse pay(PayRequest request);

    /**
     * 发起退款
     */
    RefundResponse refund(RefundRequest request);

    /**
     * 查询交易状态
     */
    QueryResponse query(QueryRequest request);

    /**
     * 解析渠道回调通知
     */
    NotifyResult parseNotify(Map<String, String> notifyParams);
}
```

### 2.2 渠道适配器实现示例

```java
@Component
public class WechatPayAdapter implements ChannelAdapter {

    @Override
    public String getChannel() {
        return "WECHAT";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        // 1. 组装微信支付请求参数
        WechatPayRequest wechatReq = new WechatPayRequest();
        wechatReq.setOutTradeNo(request.getOrderId());
        wechatReq.setTotalFee(request.getAmount()); // 分
        wechatReq.setDescription(request.getSubject());
        wechatReq.setNotifyUrl(request.getNotifyUrl());

        // 2. 调用微信支付 API
        WechatPayResponse wechatResp = wechatApi.createOrder(wechatReq);

        // 3. 转换为统一响应
        PayResponse response = new PayResponse();
        response.setSuccess(wechatResp.isSuccess());
        response.setChannelOrderId(wechatResp.getTransactionId());
        response.setPayUrl(wechatResp.getH5Url()); // H5 支付链接
        response.setRawResponse(wechatResp.getRawJson());
        return response;
    }

    // ... refund, query, parseNotify 类似
}
```

```java
@Component
public class AlipayAdapter implements ChannelAdapter {

    @Override
    public String getChannel() {
        return "ALIPAY";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        // 1. 组装支付宝请求参数
        AlipayTradePrecreateRequest aliReq = new AlipayTradePrecreateRequest();
        aliReq.setOutTradeNo(request.getOrderId());
        aliReq.setTotalAmount(request.getAmountYuan()); // 元
        aliReq.setSubject(request.getSubject());

        // 2. 调用支付宝 API
        AlipayResponse aliResp = alipayApi.tradePrecreate(aliReq);

        // 3. 转换为统一响应
        PayResponse response = new PayResponse();
        response.setSuccess(aliResp.isSuccess());
        response.setChannelOrderId(aliResp.getTradeNo());
        response.setQrCode(aliResp.getQrCode()); // 二维码
        return response;
    }
}
```

### 2.3 适配器注册中心

适配器通过 Spring 的依赖注入自动注册到 Map 中。

```java
@Component
public class ChannelAdapterRegistry {

    /** channel -> adapter 映射 */
    private final Map<String, ChannelAdapter> adapterMap;

    /**
     * Spring 自动注入所有 ChannelAdapter 实现
     */
    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(
                        ChannelAdapter::getChannel,
                        Function.identity(),
                        (a, b) -> { throw new IllegalStateException("重复的渠道: " + a.getChannel()); }
                ));
    }

    /**
     * 获取渠道适配器
     */
    public ChannelAdapter getAdapter(String channel) {
        ChannelAdapter adapter = adapterMap.get(channel);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的支付渠道: " + channel);
        }
        return adapter;
    }

    /**
     * 获取所有支持的渠道列表
     */
    public Set<String> getSupportedChannels() {
        return adapterMap.keySet();
    }
}
```

---

## 三、渠道路由设计

渠道路由决定"这笔支付应该走哪个渠道"。一个好的路由策略不是简单地固定配置，而是基于多维度实时决策。

### 3.1 路由维度

```
┌─────────────────────────────────────────────────────────┐
│                  渠道路由维度                              │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. 金额维度                                             │
│     ├── 大额（>5000） → 银联/网关                         │
│     └── 小额          → 微信/支付宝                       │
│                                                         │
│  2. 商户维度                                             │
│     ├── 签约渠道不同                                      │
│     └── 商户等级不同，路由策略不同                          │
│                                                         │
│  3. 用户维度                                             │
│     ├── 用户偏好：用户上次用了微信                          │
│     └── 用户设备：Android 偏好支付宝                       │
│                                                         │
│  4. 渠道健康度                                           │
│     ├── 成功率（近期成功率低于阈值 → 降级）                  │
│     ├── 响应时间（过慢 → 切换备用）                        │
│     └── 可用状态（渠道宕机 → 熔断）                        │
│                                                         │
│  5. 成本维度                                             │
│     ├── 渠道费率（微信 0.6%，支付宝 0.55%）                │
│     └── 结算周期（T+0 vs T+1）                            │
│                                                         │
│  6. 地域维度                                             │
│     ├── 不同渠道在不同地区的成功率不同                       │
│     └── 跨境支付需要本地化渠道                              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 路由策略实现

```java
@Component
public class ChannelRouter {

    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelHealthManager healthManager;

    /**
     * 路由决策
     *
     * @param context 路由上下文
     * @return 选定的渠道
     */
    public RouteResult route(RouteContext context) {
        // 1. 获取所有可用渠道
        List<String> candidates = getAvailableChannels(context);

        if (candidates.isEmpty()) {
            throw new PaymentException("NO_AVAILABLE_CHANNEL", "无可用支付渠道");
        }

        // 2. 应用路由策略
        String selected = applyRouteStrategy(context, candidates);

        return new RouteResult(selected, false);
    }

    /**
     * 获取候选渠道（排除熔断的渠道）
     */
    private List<String> getAvailableChannels(RouteContext context) {
        return adapterRegistry.getSupportedChannels().stream()
                .filter(ch -> !healthManager.isCircuitBroken(ch))   // 未熔断
                .filter(ch -> healthManager.isChannelEnabled(ch))   // 未禁用
                .collect(Collectors.toList());
    }

    /**
     * 应用路由策略
     */
    private String applyRouteStrategy(RouteContext context, List<String> candidates) {
        // 如果只有一个候选，直接返回
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 多维度评分，选最高分
        return candidates.stream()
                .max(Comparator.comparingDouble(ch -> scoreChannel(ch, context)))
                .orElse(candidates.get(0));
    }

    /**
     * 对单个渠道打分（分数越高越优先）
     */
    private double scoreChannel(String channel, RouteContext context) {
        double score = 0;

        // 1. 商户签约渠道权重 +20
        if (context.getMerchantChannels().contains(channel)) {
            score += 20;
        }

        // 2. 健康度评分（0-50）
        score += healthManager.getHealthScore(channel);

        // 3. 费率评分：费率越低分越高（0-20）
        double rate = healthManager.getChannelRate(channel);
        score += (1 - rate) * 20;

        // 4. 用户历史偏好 +10
        if (context.getUserPreferredChannel() != null
                && context.getUserPreferredChannel().equals(channel)) {
            score += 10;
        }

        return score;
    }
}
```

### 3.3 路由降级

```java
/**
 * 降级路由：当首选渠道不可用时，自动切换到备选渠道
 */
@Component
@Slf4j
public class RouteDowngradeHandler {

    private static final int DOWNGRADE_WINDOW_MINUTES = 5;
    private static final double SUCCESS_RATE_THRESHOLD = 0.95;

    /**
     * 检测是否需要降级
     */
    @Scheduled(fixedRate = 60_000)
    public void checkDowngrade() {
        for (String channel : adapterRegistry.getSupportedChannels()) {
            ChannelHealth health = healthManager.getHealth(channel);

            if (health.getTotalRequests() < 100) {
                continue; // 样本不足，不判定降级
            }

            double successRate = (double) health.getSuccessCount() / health.getTotalRequests();
            if (successRate < SUCCESS_RATE_THRESHOLD) {
                log.warn("渠道降级: channel={}, successRate={}%, threshold={}%",
                        channel, successRate * 100, SUCCESS_RATE_THRESHOLD * 100);
                healthManager.downgradeChannel(channel, DOWNGRADE_WINDOW_MINUTES);
            }
        }
    }
}
```

---

## 四、渠道健康管理

### 4.1 熔断器设计

```java
@Component
@Slf4j
public class ChannelHealthManager {

    /** 渠道健康状态 */
    private final Map<String, ChannelHealth> healthMap = new ConcurrentHashMap<>();

    /** 熔断配置 */
    private static final int FAIL_THRESHOLD = 10;       // 连续失败 N 次触发熔断
    private static final long CIRCUIT_TIMEOUT_MS = 30_000; // 半开后等待时间

    /**
     * 记录调用成功
     */
    public void recordSuccess(String channel, long responseTimeMs) {
        ChannelHealth health = getOrCreateHealth(channel);
        health.recordSuccess(responseTimeMs);
    }

    /**
     * 记录调用失败
     */
    public void recordFailure(String channel, String errorCode) {
        ChannelHealth health = getOrCreateHealth(channel);
        health.recordFailure(errorCode);

        if (health.getConsecutiveFailures() >= FAIL_THRESHOLD) {
            log.warn("渠道熔断: channel={}, consecutiveFailures={}", channel, health.getConsecutiveFailures());
            health.setCircuitBreakerState(CircuitState.OPEN);
            health.setCircuitOpenedAt(System.currentTimeMillis());
        }
    }

    /**
     * 判断渠道是否熔断
     * 熔断后经过超时时间进入半开状态
     */
    public boolean isCircuitBroken(String channel) {
        ChannelHealth health = healthMap.get(channel);
        if (health == null) return false;

        if (health.getCircuitBreakerState() == CircuitState.OPEN) {
            if (System.currentTimeMillis() - health.getCircuitOpenedAt() > CIRCUIT_TIMEOUT_MS) {
                // 进入半开状态，允许少量请求探测
                health.setCircuitBreakerState(CircuitState.HALF_OPEN);
                return false; // 放行
            }
            return true;
        }
        return false;
    }
}
```

### 4.2 健康状态模型

```java
@Data
public class ChannelHealth {

    private String channel;

    /** 熔断器状态 */
    private CircuitState circuitBreakerState = CircuitState.CLOSED;

    /** 熔断开启时间 */
    private long circuitOpenedAt;

    /** 连续失败次数 */
    private int consecutiveFailures;

    /** 总请求数（滑动窗口内） */
    private int totalRequests;

    /** 成功数 */
    private int successCount;

    /** 平均响应时间 */
    private double avgResponseTimeMs;

    /** 渠道费率 */
    private double rate;

    /** 是否启用 */
    private boolean enabled = true;

    public synchronized void recordSuccess(long responseTimeMs) {
        consecutiveFailures = 0;
        totalRequests++;
        successCount++;
        avgResponseTimeMs = (avgResponseTimeMs * (totalRequests - 1) + responseTimeMs) / totalRequests;

        if (circuitBreakerState == CircuitState.HALF_OPEN) {
            circuitBreakerState = CircuitState.CLOSED; // 探测成功，关闭熔断器
        }
    }

    public synchronized void recordFailure(String errorCode) {
        consecutiveFailures++;
        totalRequests++;
    }
}

public enum CircuitState {
    CLOSED,     // 关闭：正常运行
    OPEN,       // 打开：熔断中
    HALF_OPEN   // 半开：探测中
}
```

---

## 五、渠道回调处理

支付渠道的异步通知是支付系统的"短板"——各渠道的通知格式不同、重试策略不同、签名算法不同。

### 5.1 统一回调处理

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class ChannelNotifyHandler {

    private final ChannelAdapterRegistry adapterRegistry;
    private final PayCallbackService payCallbackService;

    /**
     * 统一处理渠道回调
     *
     * @param channel      渠道标识
     * @param notifyParams 渠道回调参数
     * @return 处理结果
     */
    public NotifyResult handleNotify(String channel, Map<String, String> notifyParams) {
        // 1. 获取适配器
        ChannelAdapter adapter = adapterRegistry.getAdapter(channel);

        // 2. 解析回调（包括验签）
        NotifyResult notifyResult = adapter.parseNotify(notifyParams);

        // 3. 幂等+状态机处理
        if (notifyResult.isSuccess()) {
            payCallbackService.handlePayCallback(
                    notifyResult.getTransactionId(),
                    notifyResult.getChannelResult()
            );
        }

        return notifyResult;
    }
}
```

### 5.2 回调重试队列

渠道回调可能因网络问题延迟到达。如果支付系统当时恰好异常，应该有一个重试机制兜底。

```java
/**
 * 回调补偿任务
 * 定期查询长时间处于 PAYING 状态的支付单，主动调用渠道查询接口确认状态
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CallbackCompensationTask {

    private final ChannelAdapterRegistry adapterRegistry;
    private final PaymentOrderRepository orderRepository;

    /** 支付中超过 N 分钟未收到回调的，主动查询 */
    private static final long COMPENSATION_MINUTES = 5;

    @Scheduled(fixedRate = 120_000) // 每 2 分钟
    public void compensate() {
        // 实际项目中：查询超过 5 分钟仍处于 PAYING 状态的支付单
        // List<PaymentOrder> pendingOrders = orderRepository
        //         .findByStatusAndUpdateTimeBefore("PAYING",
        //                 LocalDateTime.now().minusMinutes(COMPENSATION_MINUTES));
        //
        // for (PaymentOrder order : pendingOrders) {
        //     for (Transaction tx : order.getTransactions()) {
        //         if (tx.getStatus() == "CALLING") {
        //             // 调用渠道查询接口
        //             ChannelAdapter adapter = adapterRegistry.getAdapter(tx.getChannel());
        //             QueryResponse queryResp = adapter.query(new QueryRequest(tx.getTransactionId()));
        //
        //             if (queryResp.isSuccess()) {
        //                 // 手动触发回调处理
        //                 payCallbackService.handlePayCallback(
        //                         tx.getTransactionId(), queryResp.getChannelResult());
        //             }
        //         }
        //     }
        // }
    }
}
```

---

## 六、网关防护

### 6.1 签名验签

支付网关必须对所有请求进行签名验证，防止篡改和伪造。

```java
@Component
public class SignVerifyFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. 获取签名参数
        String sign = request.getHeaders().getFirst("X-Sign");
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");

        // 2. 校验时间戳防重放（5 分钟内有效）
        long now = System.currentTimeMillis();
        long reqTime = Long.parseLong(timestamp);
        if (now - reqTime > 300_000) {
            return reject(exchange, "请求已过期");
        }

        // 3. 校验 nonce 防重放
        if (redisTemplate.hasKey("nonce:" + nonce)) {
            return reject(exchange, "重复请求");
        }
        redisTemplate.opsForValue().set("nonce:" + nonce, "1", 5, TimeUnit.MINUTES);

        // 4. 校验签名
        String payload = buildSignPayload(request);
        String expectedSign = HmacSHA256.sign(payload, merchantSecret);
        if (!expectedSign.equals(sign)) {
            return reject(exchange, "签名验证失败");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(("{\"code\":\"SIGN_ERROR\",\"message\":\"" + message + "\"}").getBytes())));
    }
}
```

### 6.2 限流

```java
/**
 * 按商户、接口级别的 QPS 限流
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimiter rateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String merchantId = extractMerchantId(exchange);
        String apiPath = exchange.getRequest().getURI().getPath();

        // 组合限流 key
        String key = merchantId + ":" + apiPath;

        if (!rateLimiter.tryAcquire(key)) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse()
                    .writeWith(Mono.just(exchange.getResponse()
                            .bufferFactory()
                            .wrap("{\"code\":\"RATE_LIMITED\"}".getBytes())));
        }

        return chain.filter(exchange);
    }
}
```

---

## 七、新增渠道的流程

一个设计良好的支付网关，接入新渠道的成本应该非常低。

```
新增渠道流程：

┌─────────────────────────────────────────────────────────┐
│  1. 实现 ChannelAdapter 接口                             │
│     ├── 实现 pay()     ── 发起支付                       │
│     ├── 实现 refund()  ── 发起退款                       │
│     ├── 实现 query()   ── 交易查询                       │
│     └── 实现 parseNotify() ── 回调解析                   │
│                                                         │
│  2. 配置渠道参数                                         │
│     ├── 渠道 API 地址、APPID、密钥                        │
│     ├── 费率、结算周期                                    │
│     └── 支持的金额范围、币种                               │
│                                                         │
│  3. 不需要修改的部分                                      │
│     ├── 核心业务层（状态机、领域模型）                       │
│     ├── 账务层（记账逻辑）                                 │
│     ├── 路由引擎（自动识别新渠道）                          │
│     └── 幂等/一致性方案                                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 八、渠道层整体结构

```
payment-channel/src/main/java/com/payment/channel/
├── spi/                              # 渠道 SPI 接口
│   ├── ChannelAdapter.java           # 适配器接口
│   ├── PayRequest.java               # 支付请求
│   ├── PayResponse.java              # 支付响应
│   ├── RefundRequest.java
│   ├── RefundResponse.java
│   ├── QueryRequest.java
│   ├── QueryResponse.java
│   └── NotifyResult.java
├── wechat/                           # 微信支付实现
│   ├── WechatPayAdapter.java
│   ├── WechatPayRequest.java
│   ├── WechatPayResponse.java
│   └── WechatApiClient.java          # 微信 HTTP 客户端
├── alipay/                           # 支付宝实现
│   ├── AlipayAdapter.java
│   ├── AlipayRequest.java
│   └── AlipayApiClient.java
├── registry/                         # 注册中心
│   └── ChannelAdapterRegistry.java
├── router/                           # 路由引擎
│   ├── ChannelRouter.java
│   └── RouteContext.java
├── health/                           # 健康管理
│   ├── ChannelHealthManager.java
│   ├── ChannelHealth.java
│   └── CircuitState.java
├── handler/                          # 回调处理
│   ├── ChannelNotifyHandler.java
│   └── CallbackCompensationTask.java
└── config/                           # 渠道配置
    └── ChannelConfig.java
```

---

## 九、总结

本文设计了支付网关与渠道路由的完整架构：

1. **适配器模式**：`ChannelAdapter` 统一接口，每个渠道独立实现，新增渠道不影响核心逻辑
2. **多维度路由**：基于金额、商户、渠道健康度、成本等多维度的评分路由策略
3. **熔断降级**：`ChannelHealthManager` + 滑动窗口熔断器，自动规避故障渠道
4. **回调处理**：统一回调入口 + `CallbackCompensationTask` 补偿兜底
5. **网关防护**：签名验签、nonce 防重放、QPS 限流

---

> **📚 系列文章导航**
> - [支付系统架构设计（一）：整体架构概览]({{< relref "post/payment-architecture-01" >}})
> - [支付系统架构设计（二）：支付核心领域建模（DDD）]({{< relref "post/payment-architecture-02" >}})
> - [支付系统架构设计（三）：支付状态机与生命周期]({{< relref "post/payment-architecture-03" >}})
> - [支付系统架构设计（四）：支付幂等性与一致性]({{< relref "post/payment-architecture-04" >}})
> - 本文：支付网关与渠道路由
> - [下一篇：对账系统设计]({{< relref "post/payment-architecture-06" >}})
> - [资金账户系统设计]({{< relref "post/payment-architecture-07" >}})
> - [支付安全与风控]({{< relref "post/payment-architecture-08" >}})
> - [支付高可用与容灾]({{< relref "post/payment-architecture-09" >}})
> - [支付可观测性]({{< relref "post/payment-architecture-10" >}})
