---
title: "Spring Cloud Gateway：网关路由与过滤器"
date: 2019-04-11
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Gateway", "API网关", "路由", "过滤器", "限流"]
toc: true
---

## 前言

API 网关是微服务架构的统一入口，负责请求路由、认证鉴权、限流熔断、日志监控等横切关注点。Spring Cloud Gateway 是基于 Spring WebFlux 的响应式网关，性能优于传统的 Zuul。

<!--more-->

## 一、网关的核心功能

```
客户端 → Gateway → 微服务集群
                    │
  ┌─────────────────┼─────────────────┐
  │                  │                  │
  ▼                  ▼                  ▼
用户服务            订单服务            支付服务

Gateway 的职责：
├── 路由转发：根据请求路径分发到后端服务
├── 认证鉴权：统一验证 Token
├── 限流熔断：保护后端服务
├── 跨域处理：统一配置 CORS
├── 请求/响应日志：统一记录
└── 协议转换：外部 HTTP → 内部 RPC
```

---

## 二、快速入门

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
```

### 2.2 配置路由

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      routes:
        # 路由到用户服务
        - id: user-service
          uri: lb://user-service          # 负载均衡到 user-service
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=1               # 去除 /api 前缀

        # 路由到订单服务
        - id: order-service
          uri: lb://order-service
          predicates:
            - Path=/api/orders/**
          filters:
            - StripPrefix=1
```

### 2.3 启动类

```java
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

---

## 三、路由谓词（Predicate）

### 3.1 内置谓词

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            # 路径匹配
            - Path=/api/users/**,/api/admins/**
            
            # 请求方法
            - Method=GET,POST
            
            # 请求头
            - Header=X-Request-Type, \d+
            
            # 请求参数
            - Query=page, \d+
            
            # Cookie
            - Cookie=sessionId, abc
            
            # 请求时间范围
            - Between=2024-01-01T00:00:00+08:00, 2024-12-31T23:59:59+08:00
            
            # 权重（灰度发布）
            - Weight=group1, 80    # 80% 流量
```

### 3.2 Java 配置

```java
@Configuration
public class GatewayConfig {
    
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("user-service", r -> r
                        .path("/api/users/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://user-service"))
                .route("order-service", r -> r
                        .path("/api/orders/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://order-service"))
                .build();
    }
}
```

---

## 四、过滤器（Filter）

### 4.1 内置过滤器

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/users/**
          filters:
            # 请求头操作
            - AddRequestHeader=X-Gateway-Version, v1
            - RemoveRequestHeader=Cookie
            - AddRequestParameter=source, gateway
            
            # 响应头操作
            - AddResponseHeader=X-Gateway, true
            - RemoveResponseHeader=Server
            
            # 请求路径
            - StripPrefix=1              # 去除 /api
            - PrefixPath=/api            # 添加前缀
            
            # 限流
            - name: RequestRateLimiter
              args:
                key-resolver: "#{@userKeyResolver}"
                redis-rate-limiter:
                  replenishRate: 100     # 每秒令牌数
                  burstCapacity: 200     # 突发容量
            
            # 熔断
            - name: CircuitBreaker
              args:
                name: userServiceBreaker
                fallbackUri: forward:/fallback
            
            # 重试
            - name: Retry
              args:
                retries: 3
                statuses: BAD_GATEWAY
                methods: GET
```

### 4.2 自定义过滤器

```java
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 获取请求路径
        String path = exchange.getRequest().getURI().getPath();
        
        // 2. 白名单放过
        if (path.startsWith("/api/login") || path.startsWith("/api/register")) {
            return chain.filter(exchange);
        }
        
        // 3. 验证 Token
        String token = exchange.getRequest().getHeaders()
                .getFirst("Authorization");
        
        if (token == null || !token.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        
        // 4. 解析 Token，放入请求头
        String userId = parseToken(token);
        ServerWebRequest request = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .build();
        
        // 5. 继续过滤链
        return chain.filter(exchange.mutate().request(request).build());
    }
    
    @Override
    public int getOrder() {
        return -100;  // 高优先级
    }
    
    private String parseToken(String token) {
        // 解析 JWT Token
        return "123";
    }
}
```

### 4.3 限流过滤器

```java
@Bean
public KeyResolver userKeyResolver() {
    // 按用户 ID 限流
    return exchange -> {
        String userId = exchange.getRequest()
                .getHeaders().getFirst("X-User-Id");
        if (userId == null) {
            return Mono.just("anonymous");
        }
        return Mono.just(userId);
    };
}

@Bean
public KeyResolver apiKeyResolver() {
    // 按请求路径限流
    return exchange -> Mono.just(
            exchange.getRequest().getURI().getPath());
}
```

---

## 五、跨域配置

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins: "https://example.com"
            allowed-methods: "*"
            allowed-headers: "*"
            allow-credentials: true
            max-age: 3600
```

```java
@Bean
public CorsWebFilter corsWebFilter() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    
    UrlBasedCorsConfigurationSource source = 
            new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    
    return new CorsWebFilter(source);
}
```

---

## 六、Gateway vs Zuul

| 对比 | Gateway | Zuul 1.x |
|------|---------|----------|
| 底层 | Spring WebFlux（Netty）| Servlet（Tomcat）|
| 线程模型 | 响应式（非阻塞）| 同步阻塞 |
| 性能 | 高 | 一般 |
| 长连接 | ✅ 支持 WebSocket | ❌ |
| 限流 | 内置 Redis 限流 | 需集成 |
| Spring Boot 兼容 | 2.x+ | 1.x/2.x |

---

## 七、总结

### 核心配置速查

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=1
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins: "*"
            allowed-methods: "*"
```

### 网关最佳实践

```
1. 统一认证：GlobalFilter 校验 Token
2. 限流保护：RequestRateLimiter + Redis
3. 熔断降级：CircuitBreaker + fallback
4. 跨域配置：globalcors（前后端分离必备）
5. 日志记录：自定义 GlobalFilter 记录请求和响应
```

**上一篇：** [Spring Cloud 远程调用：OpenFeign 声明式服务调用]({{< relref "post/spring-cloud-openfeign" >}})

**下一篇：** [Spring Cloud 配置中心：Nacos Config 动态配置管理]({{< relref "post/spring-cloud-config-nacos" >}})
