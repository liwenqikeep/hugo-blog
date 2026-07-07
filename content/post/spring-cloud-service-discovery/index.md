---
title: "Spring Cloud 服务发现：Nacos 注册中心原理与实战"
date: 2019-04-05
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Nacos", "服务发现", "注册中心"]
toc: true
---

## 前言

服务发现是微服务架构的核心组件。在分布式系统中，服务的实例地址是动态变化的——扩缩容、故障转移、滚动升级都会导致实例上下线。服务发现机制让服务消费者能够动态获取服务提供者的地址列表。

Nacos 是阿里巴巴开源的服务发现和配置管理平台，支持 AP 和 CP 两种模式，是国内 Spring Cloud 微服务的首选注册中心。

<!--more-->

## 一、服务发现架构

### 1.1 核心角色

```
服务提供者（Provider）：启动时注册自己的地址
服务消费者（Consumer）：从注册中心获取服务列表
注册中心（Registry）：管理服务的注册和发现

服务启动时：
Provider ──register──▶ Registry
                       │
Consumer ──subscribe──▶ Registry ──notify──▶ Consumer

健康检查：
Registry ◀──heartbeat── Provider（周期性发送心跳）
```

### 1.2 客户端发现 vs 服务端发现

```
客户端发现（Nacos/Eureka/Consul）：
  Consumer 从 Registry 获取 Provider 列表
  Consumer 自己选择调用哪个 Provider（负载均衡）
  优点：架构简单，无需额外代理
  缺点：客户端集成发现逻辑

服务端发现（Kubernetes + kube-proxy）：
  Consumer 请求 Service 的 Cluster IP
  kube-proxy 将请求转发到后端 Pod
  优点：客户端无需感知服务地址
  缺点：需要额外代理组件
```

---

## 二、Nacos 架构

### 2.1 核心组件

```
Nacos Server
    │
    ├── AP 模式（Distro 协议）—— 最终一致性，可用性优先
    │     ├── 临时实例（默认）
    │     └── 健康检查：客户端心跳上报（每 5 秒）
    │
    └── CP 模式（Raft 协议）—— 强一致性，一致性优先
          ├── 永久实例（ephemeral=false）
          └── 健康检查：服务端主动探测

Nacos Client
    ├── 服务注册
    ├── 服务发现
    └── 心跳上报
```

### 2.2 Nacos 与 Eureka 对比

| 对比 | Nacos | Eureka |
|------|-------|--------|
| CAP 模型 | AP/CP 可切换 | AP 仅可用性 |
| 控制台 | ✅ 自带管理界面 | ❌ 需要自行搭建 |
| 配置中心 | ✅ 一体化 | ❌ 需要搭配 Spring Cloud Config |
| 健康检查 | 心跳 + 主动探测 | 心跳（默认 30s）|
| 保护机制 | 多种保护策略 | 自我保护模式 |
| 协议 | Distro / Raft | 自我复制（Peer-to-Peer）|

---

## 三、Nacos 快速入门

### 3.1 安装 Nacos Server

```bash
# 下载 Nacos
wget https://github.com/alibaba/nacos/releases/download/2.2.0/nacos-server-2.2.0.zip
unzip nacos-server-2.2.0.zip
cd nacos/bin

# 单机模式启动
sh startup.sh -m standalone

# 访问控制台
# http://localhost:8848/nacos
# 默认账号：nacos / nacos
```

### 3.2 Spring Boot 集成

```xml
<!-- 父 POM 管理版本 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>2021.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- Nacos 服务发现 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

### 3.3 配置

```yaml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: public       # 命名空间
        group: DEFAULT_GROUP     # 分组
        cluster-name: BJ         # 集群名称（北京机房）
        metadata:
          version: v1
          region: beijing
        ephemeral: true          # 临时实例（默认）
```

```java
@SpringBootApplication
@EnableDiscoveryClient  // 开启服务发现（Spring Cloud 通用注解）
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

---

## 四、服务调用

### 4.1 使用 DiscoveryClient

```java
@RestController
@RequestMapping("/api")
public class UserController {
    
    @Autowired
    private DiscoveryClient discoveryClient;
    
    @GetMapping("/discover")
    public List<ServiceInstance> discover(@RequestParam String serviceName) {
        // 手动获取服务的所有实例
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
        for (ServiceInstance instance : instances) {
            System.out.println(instance.getHost() + ":" + instance.getPort());
        }
        return instances;
    }
}

// 输出：
// 192.168.1.10:8081
// 192.168.1.11:8081
```

### 4.2 使用 LoadBalancerClient

```java
@RestController
@RequestMapping("/api")
public class CallController {
    
    @Autowired
    private LoadBalancerClient loadBalancerClient;
    
    @GetMapping("/call")
    public String callService() {
        // 负载均衡选择一个实例
        ServiceInstance instance = loadBalancerClient.choose("order-service");
        String url = String.format("http://%s:%s/api/orders", 
                                   instance.getHost(), instance.getPort());
        // 调用服务
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate.getForObject(url, String.class);
    }
}
```

### 4.3 @LoadBalanced RestTemplate

```java
@Configuration
public class BeanConfig {
    
    @Bean
    @LoadBalanced  // 自动集成负载均衡
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@Service
public class OrderServiceClient {
    
    @Autowired
    private RestTemplate restTemplate;
    
    public String getOrder(Long orderId) {
        // 直接使用服务名调用，Ribbon/Nacos 自动解析为具体 IP
        return restTemplate.getForObject(
                "http://order-service/api/orders/" + orderId, 
                String.class);
    }
}
```

---

## 五、Nacos 核心概念

### 5.1 命名空间（Namespace）

```
用于环境隔离：

Namespace = dev
  ├── Group = DEFAULT_GROUP
  │     ├── user-service
  │     └── order-service
  │
Namespace = prod
  ├── Group = DEFAULT_GROUP
  │     ├── user-service
  │     └── order-service

不同 Namespace 之间的服务不可见。
```

### 5.2 集群（Cluster）

```
用于同城多机房部署：

user-service:
  ├── Cluster = BJ（北京机房）
  │     ├── 192.168.1.10:8081
  │     └── 192.168.1.11:8081
  │
  └── Cluster = SH（上海机房）
        ├── 192.168.2.10:8081
        └── 192.168.2.11:8081

优先调用同集群的服务，减少跨机房延迟。
```

### 5.3 保护阈值（Protect Threshold）

```
触发条件：健康实例数 / 总实例数 < 保护阈值
触发后行为：Nacos 将不健康的实例也返回给消费者
目的：防止所有实例都被摘除导致服务完全不可用

配置：
spring.cloud.nacos.discovery.protect-threshold=0.8
```

---

## 六、Eureka 快速集成

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
```

---

## 七、总结

### 服务发现核心概念

| 概念 | 说明 |
|------|------|
| 服务注册 | 服务启动时将自己的 IP 和端口注册到注册中心 |
| 服务发现 | 消费者从注册中心获取服务提供者的地址列表 |
| 健康检查 | 注册中心定期检测服务是否存活 |
| 负载均衡 | 从多个实例中选择一个进行调用 |

### Nacos 配置速查

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: public
        group: DEFAULT_GROUP
        cluster-name: BJ
        ephemeral: true
        protect-threshold: 0.8
```

**下一篇：** [Spring Cloud 负载均衡：LoadBalancer 原理与实战]({{< relref "post/spring-cloud-loadbalancer" >}})
