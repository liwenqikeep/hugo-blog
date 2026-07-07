---
title: "Dubbo（二）：快速入门与配置"
date: 2021-08-12
draft: false
categories: ["分布式"]
tags: ["Dubbo", "RPC", "Spring Boot", "Nacos", "配置"]
toc: true
---

## 前言

本文通过 Spring Boot 集成 Dubbo + Nacos，从零搭建一个完整的 Dubbo 微服务项目，覆盖服务接口定义、服务提供者、服务消费者的完整开发流程。

<!--more-->

## 一、项目结构

```
dubbo-demo/
├── dubbo-api/                 ← 公共 API 接口
│   └── src/main/java/
│       └── com/example/api/
│           └── UserService.java
├── dubbo-provider/            ← 服务提供者
│   └── src/main/java/
│       └── com/example/provider/
│           ├── ProviderApplication.java
│           └── UserServiceImpl.java
└── dubbo-consumer/            ← 服务消费者
    └── src/main/java/
        └── com/example/consumer/
            ├── ConsumerApplication.java
            └── UserController.java
```

---

## 二、公共 API

### 2.1 定义接口

```java
// dubbo-api — 统一管理接口和实体
public interface UserService {
    User getUser(Long id);
    List<User> listUsers(String keyword);
    Long createUser(User user);
}

public class User implements Serializable {
    private Long id;
    private String name;
    private String email;
    // getter / setter（必须实现 getter/setter）
}
```

```xml
<!-- dubbo-api/pom.xml -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>dubbo-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 三、服务提供者

### 3.1 依赖

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo-bom</artifactId>
            <version>3.0.8</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Dubbo Spring Boot Starter -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-spring-boot-starter</artifactId>
    </dependency>
    
    <!-- 注册中心：Nacos -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-registry-nacos</artifactId>
    </dependency>
    
    <!-- 协议：dubbo 协议 -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-rpc-dubbo</artifactId>
    </dependency>
    
    <!-- 序列化：Hessian -->
    <dependency>
        <groupId>org.apache.dubbo</groupId>
        <artifactId>dubbo-serialization-hessian2</artifactId>
    </dependency>
    
    <!-- 公共 API -->
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>dubbo-api</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

### 3.2 application.yml

```yaml
server:
  port: 8081

spring:
  application:
    name: dubbo-provider

dubbo:
  application:
    name: ${spring.application.name}
    qos-enable: true
    qos-port: 22222
  
  # 注册中心
  registry:
    address: nacos://127.0.0.1:8848
    
  # 协议
  protocol:
    name: dubbo
    port: -1          # 随机端口
  
  # 扫描服务
  scan:
    base-packages: com.example.provider
```

### 3.3 服务实现

```java
@SpringBootApplication
public class ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}

// dubbo 服务注解
@DubboService(version = "1.0.0", group = "default",
              timeout = 3000, retries = 2)
@Service
public class UserServiceImpl implements UserService {
    
    @Override
    public User getUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setName("User-" + id);
        return user;
    }
    
    @Override
    public List<User> listUsers(String keyword) {
        return List.of(new User(1L, "Tom"), new User(2L, "Jerry"));
    }
    
    @Override
    public Long createUser(User user) {
        return 1L;
    }
}
```

---

## 四、服务消费者

### 4.1 依赖

```xml
<!-- 与提供者相同的 Dubbo 和注册中心依赖 -->
<!-- 加上公共 API 依赖 -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>dubbo-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 4.2 application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: dubbo-consumer

dubbo:
  application:
    name: ${spring.application.name}
  
  registry:
    address: nacos://127.0.0.1:8848
  
  protocol:
    name: dubbo
    port: -1
  
  consumer:
    timeout: 5000
    retries: 2
    check: false    # 启动时不检查提供者是否可用
```

### 4.3 远程调用

```java
@SpringBootApplication
@EnableAutoConfiguration
public class ConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}

@RestController
@RequestMapping("/api")
public class UserController {
    
    // Dubbo 远程注入
    @DubboReference(version = "1.0.0", group = "default",
                    timeout = 3000, retries = 1,
                    loadbalance = "roundrobin",
                    cluster = "failover")
    private UserService userService;
    
    @GetMapping("/users/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.getUser(id);  // 远程调用
    }
}
```

---

## 五、集群容错配置

### 5.1 全局配置

```yaml
dubbo:
  consumer:
    timeout: 5000
    retries: 2
    loadbalance: leastactive
    cluster: failover
```

### 5.2 方法级配置

```java
@DubboReference(interfaceClass = UserService.class,
                methods = {
                    @Method(name = "getUser", 
                            timeout = 1000, 
                            retries = 0),
                    @Method(name = "listUsers",
                            timeout = 5000,
                            retries = 3,
                            loadbalance = "roundrobin")
                })
private UserService userService;
```

---

## 六、多注册中心

```yaml
dubbo:
  registries:
    nacos:
      address: nacos://127.0.0.1:8848
      default: true
    zk:
      address: zookeeper://127.0.0.1:2181
```

```java
// 指定注册中心
@DubboReference(registry = "nacos")
private UserService userService;
```

---

## 七、多协议

```yaml
dubbo:
  protocols:
    dubbo:
      name: dubbo
      port: -1
    rest:
      name: rest
      port: 8082
      server: netty
```

---

## 八、总结

### 配置速查

```yaml
dubbo:
  application:
    name: user-service
  registry:
    address: nacos://127.0.0.1:8848
  protocol:
    name: dubbo
    port: -1
  consumer:
    timeout: 5000
    retries: 2
    check: false
  scan:
    base-packages: com.example.service
```

### 注解速查

| 注解 | 位置 | 说明 |
|------|------|------|
| @DubboService | 提供者 | 暴露服务 |
| @DubboReference | 消费者 | 引用远程服务 |
| @Method | 消费者 | 方法级配置 |

**上一篇：** [Dubbo（一）：核心架构与原理]({{< relref "post/dubbo-architecture" >}})

**下一篇：** [Dubbo（三）：源码与高级特性]({{< relref "post/dubbo-source-code-advanced" >}})
