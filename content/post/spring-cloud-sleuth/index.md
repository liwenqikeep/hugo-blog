---
title: "Spring Cloud 链路追踪：Sleuth + Zipkin 全链路监控"
date: 2019-04-17
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Sleuth", "Zipkin", "链路追踪", "Trace"]
toc: true
---

## 前言

在微服务架构中，一个请求可能经过多个服务。当请求出现延迟或失败时，传统方式难以快速定位问题发生在哪个服务。链路追踪通过在请求中注入 Trace ID，将一次请求经过的所有服务串联起来，实现全链路可视化。

<!--more-->

## 一、链路追踪核心概念

### 1.1 Trace 和 Span

```
Trace（追踪）：一次请求的完整调用链路
Span（跨度）：链路中的一个服务调用

Trace = Span1 + Span2 + Span3 + ...

                  Trace ID = abc123
                      │
    ┌─────────────────┼─────────────────┐
    │                 │                  │
  Span A           Span B             Span C
(网关)             (用户服务)         (订单服务)
    │                 │                  │
    ▼                 ▼                  ▼
[50ms]            [200ms]            [150ms]

Trace ID：abc123（全局唯一，贯穿整个链路）
Span ID：a1b2（每个服务调用生成唯一的 Span ID）
Parent Span ID：父级的 Span ID（串联调用关系）
```

### 1.2 核心标识

| 标识 | 说明 | 传递方式 |
|------|------|---------|
| traceId | 全局追踪 ID | HTTP Header：x-trace-id |
| spanId | 当前调用 ID | 每个服务重新生成 |
| parentId | 父调用 ID | 从上游 Header 获取 |

---

## 二、Sleuth 快速入门

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
```

### 2.2 效果

```yaml
# 引入 Sleuth 后，日志会自动打印 Trace ID 和 Span ID
# 无需额外配置

2024-01-01 10:00:00.123 [user-service,abc123,a1b2,true] INFO  [GET /api/users/1]
                          │        │      │    │
                          │        │      │    └── 是否导出到 Zipkin
                          │        │      └─────── Span ID
                          │        └────────────── Trace ID
                          └────────────────────── 服务名
```

---

## 三、Zipkin 集成

### 3.1 启动 Zipkin

```bash
# 方式一：Docker 启动
docker run -d -p 9411:9411 openzipkin/zipkin

# 方式二：JAR 包启动
wget https://repo1.maven.org/maven2/io/zipkin/zipkin-server/2.23.16/zipkin-server-2.23.16-exec.jar
java -jar zipkin-server-2.23.16-exec.jar

# 访问控制台：http://localhost:9411
```

### 3.2 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-sleuth-zipkin</artifactId>
</dependency>
```

### 3.3 配置

```yaml
spring:
  sleuth:
    sampler:
      probability: 1.0       # 采样率（1.0 = 100% 采样）
    web:
      client:
        enabled: true
  zipkin:
    base-url: http://localhost:9411
    enabled: true
    sender:
      type: web              # 发送方式：web/kafka/rabbit
```

---

## 四、日志关联

### 4.1 日志框架集成

```xml
<!-- Logback 中配置 MDC 输出 Trace ID -->
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
        <pattern>
            %d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId},%X{spanId}] %-5level %logger{36} - %msg%n
        </pattern>
    </encoder>
</appender>
```

### 4.2 通过 Trace ID 查询日志

```
# 全链路日志查询

Kibana 查询：traceId: "abc123"
Elasticsearch 搜索：x-trace-id: abc123

可以查询到一次请求在所有服务中打印的日志。
```

---

## 五、异步场景

### 5.1 线程池传递

```java
// Sleuth 默认使用 LazyTraceExecutor 包装线程池
// 自动在新的线程中传递 Trace 信息

@Configuration
public class ThreadPoolConfig {
    
    @Bean
    public ExecutorService executorService() {
        // Sleuth 会自动增强 @Async
        return Executors.newFixedThreadPool(10);
    }
}

@Service
public class AsyncService {
    
    @Async
    public void asyncMethod() {
        // 这里 Trace ID 会自动传递
    }
}
```

### 5.2 MQ 消息传递

```java
// Spring Cloud Sleuth 对 RabbitMQ 和 Kafka 也有自动支持
// 在消息的 Header 中自动注入 Trace 信息

// 发送端自动注入
rabbitTemplate.convertAndSend("exchange", "routingKey", message);
// Header 中自动包含：b3（traceId, spanId）

// 消费端自动提取
@RabbitListener(queues = "queue")
public void handle(Message message) {
    // 自动从 Header 中提取 Trace 信息
    // 消费逻辑的日志会继承发送端的 Trace ID
}
```

---

## 六、自定义链路信息

```java
// 自定义 Tag
@RestController
@RequestMapping("/api")
public class OrderController {
    
    @Autowired
    private Tracer tracer;  // Brave Tracer
    
    @GetMapping("/orders/{id}")
    public Order getOrder(@PathVariable Long id) {
        // 向当前 Span 添加自定义 Tag
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("order.id", String.valueOf(id));
            span.tag("order.source", "web");
        }
        
        return orderService.findById(id);
    }
}
```

---

## 七、Zipkin 数据持久化

```yaml
# Zipkin 默认将数据存储在内存中
# 生产环境需要配置持久化存储

# 使用 Elasticsearch（推荐）
spring:
  zipkin:
    storage:
      type: elasticsearch
      
# 使用 MySQL
spring:
  zipkin:
    storage:
      type: mysql

# Docker Compose 方式
version: '2'
services:
  zipkin:
    image: openzipkin/zipkin
    environment:
      - STORAGE_TYPE=elasticsearch
      - ES_HOSTS=http://elasticsearch:9200
    ports:
      - 9411:9411
  elasticsearch:
    image: elasticsearch:7.x
```

---

## 八、总结

### 链路追踪架构

```
请求 → 网关 → 用户服务 → 订单服务 → 支付服务
  │       │        │         │          │
  └───────┴────────┴─────────┴──────────┴──→ Zipkin（收集数据）
                                                 │
                                                 ▼
                                            Elasticsearch（存储）
                                                 │
                                                 ▼
                                            Zipkin UI（可视化）
```

### 配置速查

```yaml
spring:
  sleuth:
    sampler:
      probability: 1.0      # 采样率
  zipkin:
    base-url: http://localhost:9411
    sender:
      type: web             # web/kafka/rabbit
```

**上一篇：** [Spring Cloud 熔断限流：Sentinel 流量治理]({{< relref "post/spring-cloud-sentinel" >}})

**下一篇：** [Nacos 综合实战：注册中心 + 配置中心一体化]({{< relref "post/spring-cloud-nacos-comprehensive" >}})
