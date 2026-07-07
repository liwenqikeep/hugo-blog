---
title: "Nacos 综合实战：注册中心 + 配置中心一体化"
date: 2019-04-19
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Nacos", "注册中心", "配置中心", "综合实战"]
toc: true
---

## 前言

Nacos 是阿里巴巴开源的一体化平台，**集服务发现和配置管理于一身**。前面几篇文章分别从服务发现和配置管理的角度介绍了 Nacos，本文将它们结合起来，展示 Nacos 在企业级项目中的综合使用。

<!--more-->

## 一、Nacos 整体架构

### 1.1 功能模型

```
Nacos Platform
    │
    ├── 服务发现（Discovery）
    │     ├── 服务注册
    │     ├── 健康检查
    │     ├── 负载均衡
    │     └── 服务路由
    │
    └── 配置管理（Config）
          ├── 配置存储
          ├── 动态刷新
          ├── 版本管理
          └── 灰度配置
```

### 1.2 部署方式

```bash
# 单机模式（开发测试）
sh startup.sh -m standalone

# 集群模式（生产环境，最少 3 节点）
# Nacos 集群使用 Raft 协议保证一致性

# application.properties - Nacos 集群配置
nacos.inetutils.ip-address=192.168.1.10
cluster.conf:
192.168.1.10:8848
192.168.1.11:8848
192.168.1.12:8848

# nginx 反向代理（客户端的统一入口）
upstream nacos-cluster {
    server 192.168.1.10:8848;
    server 192.168.1.11:8848;
    server 192.168.1.12:8848;
}

# 客户端连接 Nginx
spring.cloud.nacos.server-addr=http://nacos.company.com:80
```

---

## 二、项目整体配置

### 2.1 父 POM 管理

```xml
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
```

### 2.2 服务完整配置

```yaml
# bootstrap.yml — 引导配置（优先加载）
spring:
  application:
    name: user-service
  cloud:
    nacos:
      config:
        server-addr: nacos.company.com:80
        namespace: prod-namespace-id
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
        shared-configs:
          - data-id: common-datasource.yaml
            group: COMMON_GROUP
            refresh: true
          - data-id: common-redis.yaml
            group: COMMON_GROUP
            refresh: true

---
# application.yml — 应用配置
spring:
  cloud:
    nacos:
      discovery:
        server-addr: nacos.company.com:80
        namespace: prod-namespace-id
        group: DEFAULT_GROUP
        cluster-name: BJ
        ephemeral: true
        metadata:
          version: v1
          region: beijing
```

---

## 三、配置管理最佳实践

### 3.1 配置分类

```
Nacos 配置管理推荐方案：

按生效范围：
├── 共享配置（common-*.yaml）
│     ├── common-datasource.yaml     ← 所有服务共享的数据库配置
│     ├── common-redis.yaml          ← Redis 配置
│     └── common-mq.yaml             ← 消息队列配置
│
├── 服务配置（${service-name}.yaml）
│     ├── user-service.yaml          ← 用户服务特有配置
│     └── order-service.yaml         ← 订单服务特有配置
│
└── 环境配置（${service-name}-${profile}.yaml）
      ├── user-service-dev.yaml
      └── user-service-prod.yaml
```

### 3.2 配置隔离

```yaml
# 建议的 Namespace 方案

Namespace: dev
  ├── user-service.yaml
  └── order-service.yaml

Namespace: test
  ├── user-service.yaml
  └── order-service.yaml

Namespace: prod
  ├── user-service.yaml
  └── order-service.yaml
```

```yaml
# bootstrap.yml 中指定环境
spring:
  profiles:
    active: ${ENV:dev}      # 通过环境变量控制
  cloud:
    nacos:
      config:
        namespace: ${ENV}-namespace-id
```

---

## 四、服务发现最佳实践

### 4.1 健康检查配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        ephemeral: true
        # 心跳配置
        heart-beat-interval: 5       # 心跳间隔（秒）
        heart-beat-timeout: 15       # 心跳超时（秒）
        ip-delete-timeout: 30        # 实例删除超时（秒）
```

### 4.2 权重与灰度

```yaml
# 新版本上线先设置小权重
spring:
  cloud:
    nacos:
      discovery:
        weight: 0.1     # 只接收 10% 流量
        
# 验证没问题后改为 1.0
```

### 4.3 保护阈值

```yaml
spring:
  cloud:
    nacos:
      discovery:
        protect-threshold: 0.8
```

---

## 五、Nacos 生产环境配置

### 5.1 JVM 配置

```bash
# Nacos Server JVM 参数
# 根据集群规模调整
MODE=cluster

# 堆内存配置
JVM_XMS=4g
JVM_XMX=4g
JVM_XMN=2g

# 日志配置
JVM_LOGGC="-Xloggc:${BASE_DIR}/logs/gc.log"

# 启动
sh startup.sh
```

### 5.2 持久化配置

```sql
-- Nacos 默认使用 Derby 内嵌数据库（单机模式）
-- 集群模式需要 MySQL

-- application.properties
spring.datasource.platform=mysql
db.url.0=jdbc:mysql://mysql-host:3306/nacos?characterEncoding=utf8
db.user=root
db.password=root
```

### 5.3 启动配置

```yaml
# Nacos 端关键配置
# conf/application.properties

# 认证
nacos.core.auth.enabled=true
nacos.core.auth.token.secret.key=YourSecretKeyBase64Encoded

# MySQL 数据源
spring.datasource.platform=mysql
db.url.0=jdbc:mysql://10.0.0.1:3306/nacos?characterEncoding=utf8
db.pool.config.maximum-pool-size=20

# 集群配置
nacos.inetutils.ip-address=10.0.0.1
```

---

## 六、Nacos 集群监控

### 6.1 健康检查接口

```bash
# Nacos Server 健康检查
curl http://nacos:8848/nacos/v1/console/health/readiness

# 查看集群节点
curl http://nacos:8848/nacos/v1/ns/operator/servers

# 查看服务列表
curl http://nacos:8848/nacos/v1/ns/service/list
```

### 6.2 客户端指标

```bash
# Nacos 客户端会暴露以下指标到 Spring Boot Actuator
# /actuator/nacos-discovery
# /actuator/nacos-config

# 启用指标
management.endpoints.web.exposure.include=nacos-discovery,nacos-config
```

### 6.3 常见问题排查

```bash
# 查看 Nacos Server 日志
tail -f /usr/local/nacos/logs/nacos.log

# 查看客户端注册信息
curl http://nacos:8848/nacos/v1/ns/instance/list?serviceName=user-service

# 查看配置
curl http://nacos:8848/nacos/v1/cs/configs?dataId=user-service.yaml&group=DEFAULT_GROUP
```

---

## 七、总结

### Nacos 最佳实践清单

| 类别 | 建议 |
|------|------|
| 部署 | 集群至少 3 节点，使用 MySQL 持久化 |
| 环境隔离 | 使用 Namespace 隔离不同环境 |
| 配置共享 | 使用 shared-configs 管理公共配置 |
| 配置规范 | Data ID = 服务名.后缀，Group 区分业务域 |
| 健康检查 | 使用心跳 + 主动探测结合 |
| 保护阈值 | 设置为 0.8，防止实例全挂 |
| 灰度发布 | 通过 weight 控制实例流量比例 |

**上一篇：** [Spring Cloud 链路追踪：Sleuth + Zipkin 全链路监控]({{< relref "post/spring-cloud-sleuth" >}})
