---
title: "Spring Cloud 负载均衡：LoadBalancer 原理与实战"
date: 2019-04-07
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "负载均衡", "LoadBalancer", "Ribbon"]
toc: true
---

## 前言

负载均衡是微服务架构中常用手段。Spring Cloud 提供了两种客户端负载均衡器：Netflix Ribbon（维护模式）和 Spring Cloud LoadBalancer（官方推荐）。

负载均衡器集成在服务发现和远程调用之间——从注册中心拿到服务实例列表后，负载均衡器从中选择一个实例发起调用。

<!--more-->

## 一、客户端负载均衡 vs 服务端负载均衡

```
服务端负载均衡（Nginx / LVS）：
  Client → Nginx（负载均衡）→ Server1 / Server2
  负载均衡逻辑在独立的中间件中

客户端负载均衡（Ribbon / LoadBalancer）：
  Client（内置负载均衡）→ Server1 / Server2
  负载均衡逻辑在客户端应用中
```

---

## 二、Spring Cloud LoadBalancer

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

### 2.2 使用方式

```java
// 方式一：@LoadBalanced RestTemplate
@Configuration
public class BeanConfig {
    
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@Service
public class OrderService {
    
    @Autowired
    private RestTemplate restTemplate;
    
    public Order getOrder(Long id) {
        // 自动负载均衡到 order-service 的某个实例
        return restTemplate.getForObject(
                "http://order-service/orders/" + id, Order.class);
    }
}

// 方式二：WebClient（响应式）
@Bean
@LoadBalanced
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}
```

### 2.3 内置负载均衡策略

| 策略 | 说明 |
|------|------|
| RoundRobinLoadBalancer | 轮询（默认）|
| RandomLoadBalancer | 随机 |
| WeightedLoadBalancer | 权重 |

### 2.4 自定义负载均衡策略

```java
// 自定义策略：优先调用同集群实例
public class SameClusterLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    
    private final String clusterName;
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        return supplierProvider.get(request).get(request)
                .next().map(instances -> {
                    
                    // 优先选择同集群的服务
                    List<ServiceInstance> sameCluster = instances.stream()
                            .filter(i -> clusterName.equals(
                                    i.getMetadata().get("cluster")))
                            .collect(Collectors.toList());
                    
                    if (!sameCluster.isEmpty()) {
                        // 同集群内随机
                        return Response.of(sameCluster.get(
                                ThreadLocalRandom.current()
                                        .nextInt(sameCluster.size())));
                    }
                    
                    // 没有同集群实例，跨集群调用
                    return Response.of(instances.get(
                            ThreadLocalRandom.current()
                                    .nextInt(instances.size())));
                });
    }
}
```

---

## 三、Ribbon（Legacy）

### 3.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-ribbon</artifactId>
</dependency>
```

### 3.2 内置策略

| 策略类 | 说明 |
|--------|------|
| RoundRobinRule | 轮询（默认）|
| RandomRule | 随机 |
| WeightedResponseTimeRule | 响应时间权重 |
| RetryRule | 可重试的轮询 |
| BestAvailableRule | 最小并发数 |
| AvailabilityFilteringRule | 可用性过滤 |

### 3.3 配置

```yaml
# 全局配置
ribbon:
  ConnectTimeout: 5000
  ReadTimeout: 5000
  MaxAutoRetries: 1
  MaxAutoRetriesNextServer: 2
  OkToRetryOnAllOperations: false

# 为指定服务配置
order-service:
  ribbon:
    NFLoadBalancerRuleClassName: com.netflix.loadbalancer.RandomRule
    ConnectTimeout: 3000
    ReadTimeout: 3000
```

```java
// 自定义 Ribbon 策略
@Configuration
public class RibbonConfig {
    
    @Bean
    public IRule ribbonRule() {
        return new WeightedResponseTimeRule();
    }
}

// 全局生效
@RibbonClients(defaultConfiguration = RibbonConfig.class)
// 指定服务生效
@RibbonClient(name = "order-service", configuration = RibbonConfig.class)
```

---

## 四、重试机制

### 4.1 Spring Retry 集成

```yaml
# 开启重试
spring.cloud.loadbalancer.retry.enabled=true

# 重试配置
order-service:
  ribbon:
    MaxAutoRetries: 1           # 同一实例重试次数
    MaxAutoRetriesNextServer: 2  # 切换实例重试次数
    OkToRetryOnAllOperations: true
```

```java
// 自定义重试策略
@Bean
public RetryTemplate retryTemplate() {
    RetryTemplate template = new RetryTemplate();
    
    // 重试间隔
    FixedBackOffPolicy backOff = new FixedBackOffPolicy();
    backOff.setBackOffPeriod(1000);
    template.setBackOffPolicy(backOff);
    
    // 重试规则
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(3);
    // 只对特定异常重试
    Map<Class<? extends Throwable>, Boolean> retryable = new HashMap<>();
    retryable.put(TimeoutException.class, true);
    retryable.put(ConnectException.class, true);
    retryable.put(RuntimeException.class, false);
    retryPolicy.setRetryableExceptionMap(retryable);
    template.setRetryPolicy(retryPolicy);
    
    return template;
}
```

---

## 五、负载均衡实战

### 5.1 切换为 LoadBalancer 模式

```yaml
# Spring Cloud 2020.x 默认使用 LoadBalancer
# 如需显式启用
spring.cloud.loadbalancer.enabled=true
```

### 5.2 权重负载均衡

```yaml
# Nacos 权重配置
spring:
  cloud:
    nacos:
      discovery:
        weight: 0.8  # 权重（0-1）

# 权重较低的实例收到的流量更少
# 可用于灰度发布：新版本先配小权重
```

### 5.3 同集群优先

```yaml
# Nacos 优先调用同集群服务
spring:
  cloud:
    nacos:
      discovery:
        cluster-name: BJ

# 消费者和提供者在同一集群时，优先调用同集群实例
# 减少跨机房网络延迟
```

---

## 六、总结

### 两种负载均衡器

| 对比 | LoadBalancer（推荐）| Ribbon（维护）|
|------|:------------------:|:------------:|
| 官方推荐 | ✅ | ❌ |
| 响应式支持 | ✅ | ❌ |
| 自定义策略 | ✅ | ✅ |
| 重试 | ✅ | ✅ |

### 核心配置速查

```yaml
# LoadBalancer 配置
spring.cloud.loadbalancer.retry.enabled=true

# Ribbon 配置
ribbon:
  ConnectTimeout: 5000
  ReadTimeout: 5000
  MaxAutoRetries: 1
  MaxAutoRetriesNextServer: 2
```

**上一篇：** [Spring Cloud 服务发现：Nacos 注册中心原理与实战]({{< relref "post/spring-cloud-service-discovery" >}})

**下一篇：** [Spring Cloud 远程调用：OpenFeign 声明式服务调用]({{< relref "post/spring-cloud-openfeign" >}})
