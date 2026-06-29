---
title: "分布式系统设计（六）：微服务架构设计"
date: 2018-09-02
draft: false
categories: ["分布式"]
tags: ["微服务", "服务拆分", "RPC", "gRPC", "服务网格"]
toc: true
---

## 前言

微服务架构是一种将应用拆分为多个独立服务的架构风格。每个服务围绕业务能力构建，独立部署、独立扩展。

<!--more-->

## 一、服务拆分原则

### 1.1 拆分方法

```
横向拆分（按业务领域）：
  ├── 用户服务（User Service）
  ├── 订单服务（Order Service）
  ├── 支付服务（Payment Service）
  └── 商品服务（Product Service）

纵向拆分（按层次）：
  ├── 业务服务：核心业务逻辑
  ├── 基础服务：短信、邮件、推送
  └── 网关服务：路由、鉴权、限流
```

### 1.2 拆分原则

```
1. 单一职责
   每个服务只做一件事
   如果很难描述某个服务的功能 → 拆

2. 团队自治
   每个服务由独立团队负责
   团队可独立决策技术栈和发布节奏

3. 限界上下文（DDD）
   按业务领域确定服务边界
   每个服务有自己的数据库

4. 数据独占
   服务间不共享数据库
   只能通过 API 访问其他服务的数据
```

### 1.3 避免过度拆分

```
拆分的代价：
├── 网络通信延迟
├── 分布式事务问题
├── 调试和排查困难
└── 运维成本增加

不要为了拆分而拆分
当单体应用出现以下问题时再考虑拆分：
- 代码臃肿，难以维护
- 部署频率冲突
- 团队协作困难
- 部分功能需要独立扩展
```

---

## 二、服务间通信

### 2.1 同步通信（RPC）

```
HTTP REST：
  优点：简单、通用、防火墙友好
  缺点：性能不如二进制协议
  适用：对外 API、OpenAPI

gRPC：
  优点：高性能（Protobuf + HTTP/2），强类型
  缺点：需生成代码，浏览器支持差
  适用：内部服务间通信

Dubbo：
  优点：Java 生态，自带服务发现
  缺点：绑定 Java 语言
  适用：Java 技术栈
```

### 2.2 异步通信（消息）

```
Message Queue：
  优点：解耦、削峰、异步
  缺点：增加系统复杂度、消息可能丢失
  适用：事件通知、异步任务

事件驱动：
  优点：松耦合、可追溯
  缺点：一致性模型复杂
  适用：跨服务事件通知
```

### 2.3 gRPC 示例

```protobuf
// user.proto
service UserService {
    rpc GetUser (GetUserRequest) returns (User);
    rpc ListUsers (ListUsersRequest) returns (ListUsersResponse);
}

message GetUserRequest {
    int64 user_id = 1;
}

message User {
    int64 id = 1;
    string name = 2;
    string email = 3;
}
```

---

## 三、服务发现与注册

### 3.1 注册中心的核心功能

```
1. 服务注册
   服务启动时将自己的地址注册到注册中心

2. 健康检查
   注册中心定期检测服务是否存活
   不健康的实例自动摘除

3. 服务订阅
   消费者从注册中心获取服务列表
   监听服务变更（上线/下线）
```

### 3.2 Nacos 配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: dev
        group: DEFAULT_GROUP

# 服务调用
@Autowired
private RestTemplate restTemplate;

@LoadBalanced  // 集成负载均衡
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

---

## 四、配置管理

### 4.1 配置分类

```
按环境分：
  dev / test / staging / prod

按粒度分：
  应用级：日志级别、线程池大小
  组件级：数据源、缓存、MQ
  业务级：开关配置、限流阈值
```

### 4.2 配置管理的演进

```
阶段一：配置文件
  每个环境一套 application-{env}.yml
  修改配置需要重启应用

阶段二：配置中心
  配置统一管理，支持动态刷新
  Apollo / Nacos

阶段三：不可变配置
  Docker 镜像 + K8s ConfigMap
  配置随应用发布，不热更新
```

---

## 五、服务观测

### 5.1 可观测性三大支柱

```
日志（Logging）：
  记录系统运行时的离散事件
  工具：ELK / Loki

指标（Metrics）：
  可聚合的数值数据（QPS、延迟、错误率）
  工具：Prometheus + Grafana

链路追踪（Tracing）：
  记录一次请求在多个服务间的完整调用链
  工具：Jaeger / Zipkin
```

### 5.2 分布式链路追踪

```
Trace（追踪）：表示一次完整的请求链路
Span（跨度）：表示链路中的一个服务调用

Trace = Span1 + Span2 + Span3 + ...

服务A (Span1)
  │ 调用
  ▼
服务B (Span2)
  │ 调用
  ▼
服务C (Span3)

每个 Span 携带：
Trace ID：标识整个链路
Span ID：当前服务调用
Parent Span ID：父级调用
```

**通过 Trace ID 可以查询到一次请求经过的所有服务的完整日志。**

---

## 六、服务网格（Service Mesh）

### 6.1 什么是 Service Mesh

```
Service Mesh 将服务间通信的逻辑从应用代码中分离出来，
下沉到基础设施层（Sidecar Proxy）。

传统微服务：
  服务代码中包含：服务发现、负载均衡、熔断、重试等逻辑

Service Mesh：
  App → Sidecar Proxy（Envoy）→ Sidecar Proxy（Envoy）→ App
  服务本身只关注业务逻辑
  通信逻辑由 Sidecar 处理
```

### 6.2 Istio 架构

```
Istio 控制平面：
├── Pilot：服务发现和流量管理
├── Citadel：安全和证书管理
└── Galley：配置管理

数据平面：
└── Envoy Proxy：Sidecar 代理
     ├── 所有流量经过 Envoy
     ├── 自动实现重试、熔断、限流
     └── 收集遥测数据
```

---

## 七、总结

### 微服务决策清单

```
什么时候用微服务：
✅ 团队规模 > 10 人
✅ 代码库过于庞大
✅ 需要独立扩展部分功能
✅ 不同功能有不同发布频率

什么时候不要用微服务：
❌ 团队规模小
❌ 业务逻辑简单
❌ 没有明确的领域边界
❌ 无法承担运维复杂度
```

### 微服务关键组件

| 组件 | 功能 | 推荐 |
|------|------|------|
| 注册中心 | 服务发现 | Nacos / Consul |
| 配置中心 | 配置管理 | Apollo / Nacos |
| 网关 | 统一入口 | Spring Cloud Gateway |
| RPC 框架 | 服务调用 | gRPC / Dubbo |
| 链路追踪 | 全链路监控 | Jaeger / Zipkin |
| 监控 | 指标采集 | Prometheus + Grafana |

**相关阅读：**
- [分布式系统设计（五）：高可用与流量治理]({{< relref "post/distributed-system-high-availability" >}})
- [分布式系统设计（七）：分布式事务]({{< relref "post/distributed-system-transaction" >}})
