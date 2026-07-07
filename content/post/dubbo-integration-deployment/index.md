---
title: "Dubbo（四）：集成与部署"
date: 2021-08-16
draft: false
categories: ["分布式"]
tags: ["Dubbo", "集成", "部署", "Nacos", "Kubernetes", "生产环境"]
toc: true
---

## 前言

本文覆盖 Dubbo 在生产环境中的集成与部署方案：**Nacos 注册中心集群**、**Kubernetes 部署**、**服务网格（Service Mesh）** 以及**生产环境配置**。

<!--more-->

## 一、Nacos 注册中心集群

### 1.1 集群配置

```bash
# Nacos 集群至少 3 节点，保证 Raft 多数存活
# application.properties

# 集群节点
nacos.member.list=\
192.168.1.10:8848,\
192.168.1.11:8848,\
192.168.1.12:8848

# 使用 MySQL 持久化
spring.datasource.platform=mysql
db.url.0=jdbc:mysql://mysql:3306/nacos?characterEncoding=utf8
db.user=nacos
db.password=nacos

# 开启认证
nacos.core.auth.enabled=true
```

### 1.2 Dubbo 客户端配置

```yaml
dubbo:
  registry:
    address: nacos://192.168.1.10:8848?backup=192.168.1.11:8848,192.168.1.12:8848
    # 或通过 Nacos 域名
    # address: nacos://nacos-cluster:8848
```

---

## 二、ZooKeeper 注册中心

```yaml
dubbo:
  registry:
    address: zookeeper://192.168.1.10:2181?backup=192.168.1.11:2181,192.168.1.12:2181
    timeout: 10000
```

```xml
<!-- ZK 依赖 -->
<dependency>
    <groupId>org.apache.dubbo</groupId>
    <artifactId>dubbo-registry-zookeeper</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.curator</groupId>
    <artifactId>curator-framework</artifactId>
    <version>5.1.0</version>
</dependency>
```

---

## 三、Kubernetes 部署

### 3.1 不使用注册中心

```yaml
# 在 Kubernetes 中，可以直接通过 Service 名调用
# 替代注册中心

dubbo:
  registry:
    address: N/A  # 不使用注册中心
```

```yaml
# Kubernetes Service
apiVersion: v1
kind: Service
metadata:
  name: dubbo-provider
spec:
  selector:
    app: dubbo-provider
  ports:
    - name: dubbo
      protocol: TCP
      port: 20880
      targetPort: 20880
---
# StatefulSet（Dubbo 需要稳定的 Pod 名称）
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: dubbo-provider
spec:
  serviceName: dubbo-provider
  replicas: 3
  selector:
    matchLabels:
      app: dubbo-provider
  template:
    metadata:
      labels:
        app: dubbo-provider
    spec:
      containers:
        - name: dubbo-provider
          image: dubbo-provider:latest
          ports:
            - containerPort: 20880
---
# 消费者
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dubbo-consumer
spec:
  replicas: 2
  selector:
    matchLabels:
      app: dubbo-consumer
  template:
    metadata:
      labels:
        app: dubbo-consumer
    spec:
      containers:
        - name: dubbo-consumer
          image: dubbo-consumer:latest
          env:
            - name: DUBBO_REGISTRY_ADDRESS
              value: N/A
```

### 3.2 使用 Nacos + K8s

```yaml
# Nacos 也部署在 K8s 中
dubbo:
  registry:
    address: nacos://nacos:8848
```

---

## 四、生产环境配置

### 4.1 完整配置

```yaml
dubbo:
  application:
    name: user-service
    qos-enable: true
    qos-port: 22222
    qos-accept-foreign-ip: false  # 禁止外部访问 QOS
  
  registry:
    address: nacos://nacos-cluster:8848
    register: true
    subscribe: true
    check: false
    timeout: 15000
  
  protocol:
    name: dubbo
    port: -1
    serialization: hessian2
    threads: 200          # 线程池大小
    threadpool: fixed     # 线程池类型（fixed/cached/limited）
    accepts: 1000         # 最大连接数
  
  provider:
    timeout: 5000
    retries: 2
    loadbalance: leastactive
    filter: '-exception'  # 排除某些 Filter
  
  consumer:
    timeout: 5000
    retries: 0
    check: false
    loadbalance: roundrobin
    filter: customLog,traceId
```

### 4.2 常见配置

| 配置 | 建议 | 说明 |
|------|------|------|
| retries | 0 | 幂等操作可设为 2，非幂等必须为 0 |
| timeout | 5000 | 根据业务调整，避免长时间占用线程 |
| threads | 200 | 根据机器配置调整 |
| check | false | 启动时不检查依赖服务 |
| loadbalance | leastactive | 默认 random，长耗时用 leastactive |

---

## 五、服务网格（Service Mesh）

Dubbo 3.x 支持 Service Mesh 架构，可以将通信能力下沉到 Sidecar。

```
Dubbo 3.x 的 Mesh 架构：

应用进程
  ├── Dubbo API（业务逻辑）
  └── Dubbo SDK（服务发现、路由、协议）
        │
        ▼
Sidecar（Envoy / Proxyless）
  ├── 负载均衡
  ├── 熔断
  ├── 限流
  └── 可观测性
```

```yaml
# Proxyless 模式（Dubbo 3.x）
dubbo:
  application:
    name: user-service
  registry:
    address: nacos://nacos-cluster:8848
  protocol:
    name: tri    # Triple 协议（基于 HTTP/2）
```

---

## 六、总结

### Dubbo 生产配置速查

```yaml
dubbo:
  application:
    name: user-service
  registry:
    address: nacos://nacos-cluster:8848
    check: false
  protocol:
    name: dubbo
    port: -1
    threads: 200
  provider:
    timeout: 5000
    retries: 2
    loadbalance: leastactive
  consumer:
    timeout: 5000
    retries: 0
    check: false
```

### 集成方案速查

| 场景 | 推荐方案 |
|------|---------|
| 开发环境 | ZK / Nacos 单机 |
| 测试环境 | Nacos 集群 |
| 生产环境 | Nacos 集群（3 节点）|
| K8s 环境 | Nacos 集群 或 N/A 模式 |
| 大规模集群 | Nacos 集群 + 多注册中心 |

**上一篇：** [Dubbo（三）：源码与高级特性]({{< relref "post/dubbo-source-code-advanced" >}})
