---
title: "Dubbo（一）：核心架构与原理"
date: 2021-08-10
draft: false
categories: ["分布式"]
tags: ["Dubbo", "RPC", "微服务", "架构", "SPI"]
toc: true
---

## 前言

Dubbo 是阿里巴巴开源的高性能 Java RPC 框架，在国内微服务架构中应用广泛。与 Spring Cloud 的 HTTP 通信方式不同，Dubbo 默认采用 TCP 协议通信，性能更高，且提供了强大的服务治理能力。

本文从 Dubbo 的整体架构出发，覆盖其核心角色、通信模型和 SPI 扩展机制。

<!--more-->

## 一、Dubbo 整体架构

### 1.1 核心角色

```
Provider（服务提供者）：
  暴露服务的应用

Consumer（服务消费者）：
  调用远程服务的应用

Registry（注册中心）：
  服务注册与发现

Monitor（监控中心）：
  调用次数和调用时间监控

Container（容器）：
  服务运行容器（如 Spring 容器）
```

### 1.2 调用流程

```
1. 服务启动
   Provider → Container（初始化）
   Provider → Registry（注册服务地址）

2. 服务发现
   Consumer → Registry（订阅服务列表）
   Registry → Consumer（推送服务地址变更）

3. 远程调用
   Consumer → Invoker（代理）
   Invoker → Cluster（集群容错）
   Cluster → Directory（服务目录）
   Directory → LoadBalance（负载均衡）
   LoadBalance → Invoker（选择 Provider）
   Invoker → Protocol（远程通信）
   Protocol → Provider（调用服务）

4. 监控
   Consumer + Provider → Monitor（上报统计）
```

---

## 二、分层架构

Dubbo 的分层设计是其核心架构特点，共分 10 层：

```
┌─────────────────────────────────────────┐
│          Business（业务逻辑层）          │
│        Service / 业务接口和实现          │
├─────────────────────────────────────────┤
│              RPC（RPC 层）               │
│     Proxy（代理） Config（配置）         │
├─────────────────────────────────────────┤
│              Remoting（远程层）          │
│  Protocol / Transport / Exchange/ Serial │
└─────────────────────────────────────────┘
```

### 分层详解

| 层次 | 说明 | 核心接口 |
|------|------|---------|
| **Service** | 业务接口和实现 | 开发者定义的 Service 接口 |
| **Config** | 配置层，解析配置生成 URL | ServiceConfig、ReferenceConfig |
| **Proxy** | 代理层，生成服务的代理对象 | ProxyFactory |
| **Registry** | 注册中心层，服务注册与发现 | RegistryFactory、Registry |
| **Cluster** | 路由和集群容错层 | Cluster、Router、LoadBalance |
| **Monitor** | 监控层，调用统计 | MonitorFactory、Monitor |
| **Protocol** | 协议层，远程调用 | Protocol、Invoker、Exporter |
| **Exchange** | 信息交换层，请求响应模式 | Exchanger、ExchangeChannel |
| **Transport** | 网络传输层，抽象 mina/netty | Transporter、Server、Client |
| **Serialize** | 序列化层 | Serialization、ObjectInput、ObjectOutput |

---

## 三、SPI 扩展机制

Dubbo 的 SPI（Service Provider Interface）是其**可扩展性的核心**。Dubbo 框架中的所有功能点（协议、注册中心、负载均衡等）都是通过 SPI 加载的。

### 3.1 Java SPI vs Dubbo SPI

```java
// Java SPI（标准）
// META-INF/services/com.example.LoadBalance
com.example.RandomLoadBalance

// 加载方式：
ServiceLoader<LoadBalance> loader = ServiceLoader.load(LoadBalance.class);

// Dubbo SPI（增强）
// META-INF/dubbo/com.example.LoadBalance
random=com.example.RandomLoadBalance
round=com.example.RoundRobinLoadBalance

// 加载方式（按名称获取）：
LoadBalance loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class)
        .getExtension("round");
```

### 3.2 Dubbo SPI 的增强

| 特性 | Java SPI | Dubbo SPI |
|------|:--------:|:---------:|
| 按需加载 | ❌ 一次性加载全部 | ✅ 按名称加载 |
| IOC & AOP | ❌ | ✅ 自动注入依赖 |
| 自适应扩展 | ❌ | ✅ @Adaptive |
| 自动激活 | ❌ | ✅ @Activate |

### 3.3 @Adaptive 自适应扩展

```java
// 根据 URL 参数动态选择实现
// 例如 protocol=hessian 则使用 Hessian 序列化

@SPI("dubbo")
public interface Protocol {
    
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;
    
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;
}
```

### 3.4 @Activate 自动激活

```java
// 自动激活扩展点，用于 Filter、Interceptor 等
// 例如所有 Filter 在满足条件时自动生效

@Activate(group = {CONSUMER, PROVIDER}, order = -10000)
public class ConsumerContextFilter implements Filter {
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        // 自动被激活，无需手动配置
    }
}
```

---

## 四、协议与通信

### 4.1 支持的协议

| 协议 | 说明 | 适用场景 |
|------|------|---------|
| **dubbo** | Dubbo 默认协议，单个 TCP 连接，NIO 异步通信 | 小数据量、高并发 |
| **hessian** | 基于 HTTP 的远程调用协议 | 跨语言 |
| **http** | 基于 HTTP 表单的远程调用 | 需要 HTTP 穿透 |
| **webservice** | 基于 WebService 的远程调用 | 系统集成 |
| **thrift** | 支持 Thrift 协议 | 跨语言 |
| **grpc** | 支持 gRPC 协议 | 跨语言、流式调用 |
| **rest** | 基于 RESTful 的远程调用 | OpenAPI |

### 4.2 dubbo 协议详解

```
dubbo 协议默认使用 Netty + Hessian 序列化

单连接 + NIO 异步通信：
  ├── 单一长连接（减少连接数）
  ├── NIO 异步（非阻塞 IO）
  ├── 数据包小（约 50 字节的 Header）
  └── 适用于：小数据量（< 100KB）、高并发场景

数据包结构：
┌───────────────┬──────────────────────────────┐
│ Header (16B)  │  Body (序列化后的请求/响应体)   │
├───────────────┼──────────────────────────────┤
│ magic=0xdabb  │                              │
│ flag(请求/响应)│                              │
│ status(状态)  │                              │
│ requestId     │                              │
│ dataLength    │                              │
└───────────────┴──────────────────────────────┘
```

---

## 五、服务治理

### 5.1 负载均衡

| 策略 | 说明 |
|------|------|
| Random | 加权随机（默认）|
| RoundRobin | 加权轮询 |
| LeastActive | 最少活跃调用数（慢提供者接收更少请求）|
| ConsistentHash | 一致性哈希（相同参数请求发到同一提供者）|
| ShortestResponse | 最短响应时间（2.7.7+）|

### 5.2 集群容错

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| failover | 失败自动切换（默认）| 读操作、幂等写操作 |
| failfast | 快速失败，立即报错 | 非幂等写操作 |
| failsafe | 失败安全，忽略异常 | 日志等非核心操作 |
| failback | 失败自动恢复，后台重试 | 消息通知 |
| forking | 并行调用多个服务，取第一个结果 | 实时性要求高 |
| broadcast | 广播调用所有服务 | 缓存更新等 |

### 5.3 服务降级

```xml
<dubbo:reference id="userService" interface="com.example.UserService">
    <!-- 失败后返回 null（不抛异常）-->
    <dubbo:method name="getUser" mock="return null"/>
    <!-- 强制返回 mock 数据（不发起远程调用）-->
    <dubbo:method name="listUsers" mock="force:return empty"/>
</dubbo:reference>
```

---

## 六、总结

### Dubbo 核心特性速查

| 特性 | 说明 |
|------|------|
| 连接数 | 单一长连接 |
| 通信模型 | NIO 异步（Netty）|
| 序列化 | Hessian 2.0（默认）|
| 注册中心 | ZooKeeper / Nacos / Redis |
| 负载均衡 | Random（默认）/ RoundRobin / ConsistentHash |
| 集群容错 | failover（默认）/ failfast / failsafe |
| 扩展机制 | 基于 SPI 实现高度可扩展 |

**下一篇：** [Dubbo（二）：快速入门与配置]({{< relref "post/dubbo-quick-start" >}})
