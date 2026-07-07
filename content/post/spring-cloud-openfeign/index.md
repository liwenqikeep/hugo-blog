---
title: "Spring Cloud 远程调用：OpenFeign 声明式服务调用"
date: 2019-04-09
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "OpenFeign", "远程调用", "Feign", "声明式HTTP"]
toc: true
---

## 前言

OpenFeign 是 Spring Cloud 中的声明式 HTTP 客户端。开发者只需定义接口并添加注解，即可像调用本地方法一样调用远程服务，无需手动拼接 URL、解析响应等重复工作。

Feign 整合了 Ribbon/LoadBalancer 和 Sentinel，可以无缝集成负载均衡和熔断降级。

<!--more-->

## 一、Feign 简介

### 1.1 传统方式 vs Feign

```java
// 传统方式：手动拼接 URL、序列化、异常处理
@Service
public class OrderServiceClient {
    
    @Autowired
    private RestTemplate restTemplate;
    
    public Order getOrder(Long id) {
        String url = "http://order-service/api/orders/" + id;
        try {
            return restTemplate.getForObject(url, Order.class);
        } catch (RestClientException e) {
            log.error("调用订单服务失败", e);
            throw new BizException("订单服务不可用");
        }
    }
}

// Feign 方式：声明式接口
@FeignClient(name = "order-service")
public interface OrderFeignClient {
    
    @GetMapping("/api/orders/{id}")
    Order getOrder(@PathVariable("id") Long id);
}
```

### 1.2 架构

```
Controller → FeignClient（声明式接口）
                │
                ▼
         Feign 动态代理
                │
                ├── 参数解析（@PathVariable、@RequestParam）
                ├── 构建 HTTP 请求
                ├── 负载均衡（LoadBalancer）
                └── 发送 HTTP 请求 → 目标服务
```

---

## 二、快速入门

### 2.1 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

### 2.2 开启 Feign

```java
@SpringBootApplication
@EnableFeignClients(basePackages = "com.example.client")  // 扫描 Feign 接口
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
```

### 2.3 定义 Feign 接口

```java
@FeignClient(
    name = "user-service",              // 目标服务名
    path = "/api/users",                 // 统一前缀
    fallback = UserFallback.class        // 降级处理
)
public interface UserFeignClient {
    
    @GetMapping("/{id}")
    User getUser(@PathVariable("id") Long id);
    
    @PostMapping
    User createUser(@RequestBody User user);
    
    @PutMapping("/{id}")
    User updateUser(@PathVariable("id") Long id, @RequestBody User user);
    
    @DeleteMapping("/{id}")
    void deleteUser(@PathVariable("id") Long id);
    
    @GetMapping("/search")
    List<User> search(@RequestParam("keyword") String keyword);
}
```

### 2.4 使用 Feign 接口

```java
@Service
public class OrderService {
    
    @Autowired
    private UserFeignClient userFeignClient;
    
    public Order createOrder(OrderRequest request) {
        // 像调用本地方法一样调用远程服务
        User user = userFeignClient.getUser(request.getUserId());
        // 处理业务...
        return order;
    }
}
```

---

## 三、Feign 配置

### 3.1 全局配置

```yaml
feign:
  client:
    config:
      default:                          # 全局默认配置
        connect-timeout: 5000
        read-timeout: 5000
        logger-level: FULL              # 日志级别
  compression:
    request:
      enabled: true
      mime-types: application/json
      min-request-size: 2048
    response:
      enabled: true
```

### 3.2 为指定服务配置

```yaml
feign:
  client:
    config:
      user-service:                     # 只对 user-service 生效
        connect-timeout: 3000
        read-timeout: 3000
      order-service:
        connect-timeout: 10000
        read-timeout: 10000
```

### 3.3 日志配置

```yaml
# application.yml
logging:
  level:
    com.example.client: DEBUG  # Feign 接口所在的包路径
```

```java
// 日志级别枚举
public enum Logger.Level {
    NONE,       // 不记录（默认）
    BASIC,      // 记录请求方法、URL、响应状态码、执行时间
    HEADERS,    // BASIC + 请求和响应头
    FULL        // HEADERS + 请求和响应体
}

@Bean
public Logger.Level feignLoggerLevel() {
    return Logger.Level.FULL;
}
```

---

## 四、高级用法

### 4.1 拦截器

```java
// 添加统一的请求头
@Component
public class FeignAuthInterceptor implements RequestInterceptor {
    
    @Override
    public void apply(RequestTemplate template) {
        // 添加 Token
        template.header("Authorization", "Bearer " + getToken());
        
        // 添加 Trace ID
        template.header("X-Trace-Id", MDC.get("traceId"));
        
        // 添加请求来源
        template.header("X-Source", "order-service");
    }
    
    private String getToken() {
        // 从请求上下文获取 Token
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = ((ServletRequestAttributes) attributes).getRequest();
            return request.getHeader("Authorization");
        }
        return "";
    }
}
```

### 4.2 错误解码器

```java
@Component
public class FeignErrorDecoder implements ErrorDecoder {
    
    @Override
    public Exception decode(String methodKey, Response response) {
        String body = null;
        try {
            body = new String(response.body().asInputStream().readAllBytes());
        } catch (IOException e) {
            // ignore
        }
        
        return switch (response.status()) {
            case 400 -> new BadRequestException(body);
            case 404 -> new NotFoundException("资源不存在");
            case 500 -> new ServerException("服务端异常: " + body);
            default -> new RuntimeException("调用失败: " + response.status());
        };
    }
}
```

### 4.3 自定义解码器

```java
// 使用 FastJson 替换默认的 Jackson
@Bean
public Decoder feignDecoder() {
    return new SpringDecoder(() -> {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new FastJsonHttpMessageConverter());
        return new HttpMessageConverters(converters);
    });
}
```

---

## 五、整合 Sentinel

### 5.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

### 5.2 熔断与降级

```yaml
feign:
  sentinel:
    enabled: true
```

```java
@FeignClient(
    name = "user-service",
    fallbackFactory = UserFallbackFactory.class  // 使用工厂模式
)
public interface UserFeignClient {
    
    @GetMapping("/{id}")
    User getUser(@PathVariable("id") Long id);
}

// 降级工厂（可以获取异常信息）
@Component
public class UserFallbackFactory implements FallbackFactory<UserFeignClient> {
    
    @Override
    public UserFeignClient create(Throwable cause) {
        return id -> {
            log.error("调用 user-service 失败", cause);
            return new User();  // 返回默认值
        };
    }
}
```

---

## 六、性能优化

### 6.1 连接池

```xml
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-httpclient</artifactId>
</dependency>
```

```yaml
# 使用 Apache HttpClient 连接池替代默认的 URLConnection
feign:
  httpclient:
    enabled: true
    max-connections: 200
    max-connections-per-route: 50
    time-to-live: 900
```

### 6.2 请求压缩

```yaml
feign:
  compression:
    request:
      enabled: true
      mime-types: application/json,application/xml
      min-request-size: 2048   # 超过 2KB 才压缩
    response:
      enabled: true
```

---

## 七、Feign 工作原理

```java
// Feign 的动态代理核心流程：
// 
// 1. @EnableFeignClients 扫描 @FeignClient 接口
// 2. 每个 @FeignClient 接口创建动态代理
// 3. 方法调用时，Feign 构建 MethodHandler
// 4. 解析方法注解，构建 RequestTemplate
// 5. 通过 Client 发送 HTTP 请求
// 6. 解码响应，返回结果

// Client 的实现（按优先级）：
// 1. LoadBalancerFeignClient（负载均衡 + 服务发现）
// 2. ApacheHttpClient（连接池）
// 3. OkHttpClient
// 4. Default（URLConnection，默认）
```

---

## 八、总结

### Feign 配置速查

```yaml
feign:
  client:
    config:
      default:
        connect-timeout: 5000
        read-timeout: 5000
        logger-level: FULL
  compression:
    request:
      enabled: true
      min-request-size: 2048
  httpclient:
    enabled: true
    max-connections: 200
  sentinel:
    enabled: true
```

### @FeignClient 核心属性

| 属性 | 说明 |
|------|------|
| name / value | 目标服务名 |
| path | 接口统一前缀 |
| fallback | 降级类 |
| fallbackFactory | 降级工厂（可获取异常）|
| configuration | 自定义配置类 |

**上一篇：** [Spring Cloud 负载均衡：LoadBalancer 原理与实战]({{< relref "post/spring-cloud-loadbalancer" >}})

**下一篇：** [Spring Cloud Gateway：网关路由与过滤器]({{< relref "post/spring-cloud-gateway" >}})
