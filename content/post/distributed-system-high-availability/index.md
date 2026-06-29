---
title: "分布式系统设计（五）：高可用与流量治理"
date: 2018-08-31
draft: false
categories: ["分布式"]
tags: ["分布式系统", "高可用", "限流", "熔断", "负载均衡", "网关"]
toc: true
---

## 前言

高可用的本质是 **消除单点** 和 **控制流量**。消除单点通过冗余实现（多副本、多机房），控制流量通过限流、熔断、降级等手段实现。

<!--more-->

## 一、高可用设计

### 1.1 冗余部署

```
单点故障 → 冗余消除

无状态服务（水平扩展）：
  Nginx → 业务实例1 + 业务实例2 + 业务实例3
           （任意实例下线，流量自动切到其他实例）

有状态服务（主从架构）：
  MySQL Master → MySQL Slave1 + Slave2
  Master 下线 → 自动切换其中一个 Slave 为 Master
```

### 1.2 同城双活 vs 异地多活

```
同城双活：
  两个机房在同一城市
  网络延迟 1-3ms
  可以做双活写入

异地多活：
  两个机房在不同城市（如北京 + 上海）
  网络延迟 10-50ms
  数据同步复杂，通常只做读写分离

两地三中心：
  同城两个机房（双活）+ 异地一个机房（灾备）
  常见的金融级高可用方案
```

### 1.3 故障隔离

```
舱壁模式（Bulkhead）：
  将系统拆分为独立的舱室
  一个舱室故障不影响其他舱室

线程池隔离：
  为不同的服务分配独立的线程池
  订单线程池满了 → 只影响订单服务
  支付线程池正常 → 支付服务正常

信号量隔离：
  限制并发数量，超过则直接拒绝
  比线程池隔离更轻量
```

---

## 二、负载均衡

### 2.1 常见算法

| 算法 | 说明 | 适用场景 |
|------|------|---------|
| 轮询（RR）| 依次分发 | 后端能力均等 |
| 加权轮询 | 按权重分发 | 后端配置不等 |
| 最少连接 | 分发到连接最少的节点 | 长连接场景 |
| 一致性哈希 | 相同请求到相同节点 | 缓存场景 |
| 随机 | 随机选择 | 全场景可用 |

### 2.2 四层 vs 七层负载均衡

```
四层负载均衡（LVS、F5）：
  工作在传输层（TCP/UDP）
  根据 IP + 端口转发
  性能极高，不解析 HTTP 内容
  适合 TCP 长连接

七层负载均衡（Nginx、HAProxy）：
  工作在应用层（HTTP/HTTPS）
  可以根据 URL、Header、Cookie 分发
  功能丰富，性能略低于四层
  适合 HTTP 应用
```

---

## 三、限流

### 3.1 限流算法

```java
// 1. 计数器算法（固定窗口）
// 简单但有临界问题：第1秒最后100ms和第2秒前100ms都可能满

// 2. 滑动窗口
// 将时间窗口细分为多个小格
// 解决固定窗口的临界问题

// 3. 漏桶算法（Leaky Bucket）
// 请求先进桶，以固定速率出桶处理
// 可以削峰，但无法应对突发流量

// 4. 令牌桶算法（Token Bucket，推荐）
// 以固定速率生成令牌放入桶
// 请求需要获取令牌才能处理
// 可以应对突发流量（桶内有积压令牌）

// 5. 滑动日志
// 记录每个请求的时间戳
// 统计时间窗口内的请求数
// 精确但占用内存大
```

### 3.2 令牌桶实现

```python
class TokenBucket:
    def __init__(self, rate, capacity):
        self.rate = rate            # 每秒生成令牌数
        self.capacity = capacity    # 桶容量（最大突发流量）
        self.tokens = capacity      # 当前令牌数
        self.last_refill = time.time()
    
    def try_acquire(self):
        # 1. 补充令牌
        now = time.time()
        elapsed = now - self.last_refill
        self.tokens = min(self.capacity, 
                         self.tokens + elapsed * self.rate)
        self.last_refill = now
        
        # 2. 检查是否有可用令牌
        if self.tokens >= 1:
            self.tokens -= 1
            return True
        return False
```

### 3.3 分布式限流

```java
// 基于 Redis 的滑动窗口限流
public boolean tryAcquire(String key, int maxCount, int windowSeconds) {
    String luaScript = 
        "local key = KEYS[1]\n" +
        "local now = redis.call('TIME')[1]\n" +
        "local window = ARGV[1]\n" +
        "local max = ARGV[2]\n" +
        "redis.call('ZREMRANGEBYSCORE', key, 0, now - window)\n" +
        "local count = redis.call('ZCARD', key)\n" +
        "if tonumber(count) < tonumber(max) then\n" +
        "    redis.call('ZADD', key, now, now .. '_' .. math.random())\n" +
        "    redis.call('EXPIRE', key, window)\n" +
        "    return 1\n" +
        "end\n" +
        "return 0";
    return redisTemplate.execute(
            new DefaultRedisScript<>(luaScript, Long.class),
            List.of(key), String.valueOf(windowSeconds), 
            String.valueOf(maxCount)) > 0;
}
```

---

## 四、熔断与降级

### 4.1 熔断器状态机

```
CLOSED（关闭）
  ├── 请求正常通过
  ├── 统计失败率
  └── 失败率 > 阈值 → OPEN

OPEN（打开）
  ├── 请求直接失败（快速失败）
  ├── 等待超时时间
  └── 超时 → HALF_OPEN

HALF_OPEN（半开）
  ├── 放行少量请求
  ├── 成功 → CLOSED
  └── 失败 → OPEN
```

### 4.2 熔断配置

```java
// Resilience4j 熔断配置
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)                  // 失败率阈值 50%
    .slidingWindowSize(100)                    // 滑动窗口大小
    .minimumNumberOfCalls(10)                  // 最少呼叫数
    .waitDurationInOpenState(Duration.ofSeconds(30))  // 打开状态持续时间
    .permittedNumberOfCallsInHalfOpenState(5)  // 半开状态允许的请求数
    .build();
```

---

## 五、API 网关

### 5.1 网关的功能

```
├── 路由转发：根据请求路径路由到不同的后端服务
├── 认证鉴权：统一验证 Token
├── 限流熔断：对后端服务进行保护
├── 日志监控：统一记录请求日志
├── 协议转换：HTTP → Dubbo / gRPC
└── 灰度发布：按比例路由到不同版本
```

### 5.2 常见网关对比

| 网关 | 特点 | 适用场景 |
|------|------|---------|
| Nginx | 高性能、成熟稳定 | 七层负载均衡 |
| Kong | 基于 Nginx，插件丰富 | API 管理 |
| Spring Cloud Gateway | Java 生态，响应式 | Spring Cloud |
| APISIX | 高可用，动态路由 | 云原生 |
| Zuul | Netflix 维护 | Spring Cloud（已维护模式）|

---

## 六、总结

### 高可用设计原则

```
1. 无状态 → 水平扩展
2. 有状态 → 主从复制 + 自动切换
3. 依赖隔离 → 线程池/信号量隔离
4. 预防 → 限流（令牌桶）
5. 保护 → 熔断（快速失败）
6. 容错 → 降级（返回默认值）
7. 重试 → 超时重试+幂等性
```

### 流量治理速查

| 手段 | 作用 | 实现 |
|------|------|------|
| 负载均衡 | 分发流量 | Nginx / LVS |
| 限流 | 控制速率 | 令牌桶 / 滑动窗口 |
| 熔断 | 快速失败 | Hystrix / Resilience4j |
| 降级 | 有损服务 | 本地缓存 / 默认值 |
| 网关 | 统一入口 | Nginx / Spring Cloud Gateway |

**相关阅读：**
- [分布式系统设计（四）：分布式组件]({{< relref "post/distributed-system-components" >}})
- [分布式系统设计（六）：微服务架构设计]({{< relref "post/distributed-system-microservice" >}})
