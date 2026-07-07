---
title: "Spring Cloud 配置中心：Nacos Config 动态配置管理"
date: 2019-04-13
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Nacos", "配置中心", "动态配置"]
toc: true
---

## 前言

在微服务架构中，配置管理是一个痛点——每个服务都有自己的配置，修改配置往往需要重启服务。配置中心将配置从应用中剥离出来，集中管理并支持动态刷新。

Nacos Config 是 Nacos 提供的配置管理功能，与服务发现共享同一个平台。

<!--more-->

## 一、配置中心解决的问题

```
传统配置的问题：

1. 配置分散在各服务中
   改一个数据库连接需要在所有服务中修改

2. 修改配置需要重启
   生产环境重启涉及灰度发布、审批流程

3. 缺乏版本管理
   配置变更不可追溯，回滚困难

Nacos Config 的解决方案：
├── 配置集中管理
├── 动态刷新（无需重启）
├── 版本管理和回滚
├── 环境隔离（Namespace / Group）
└── 权限控制
```

---

## 二、快速入门

### 2.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

### 2.2 bootstrap.yml 配置

```yaml
# bootstrap.yml（引导配置文件，优先级最高）
spring:
  application:
    name: user-service
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml          # 配置文件格式
        refresh-enabled: true          # 开启动态刷新
```

### 2.3 在 Nacos 控制台创建配置

```yaml
# Nacos 控制台 → 配置管理 → 创建配置
# Data ID: user-service.yaml
# Group: DEFAULT_GROUP

app:
  name: user-service
  version: 1.0.0

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/user_db
    username: root
    password: root

config:
  thread-pool-size: 10
  max-retry: 3
  features:
    enable-notification: true
```

---

## 三、配置模型

### 3.1 三要素

```
Nacos 配置的三要素：

Data ID = {prefix}-{spring.profiles.active}.{file-extension}
  ├── prefix：spring.application.name（默认）
  ├── spring.profiles.active：激活的 Profile（可选）
  └── file-extension：yaml / properties（由配置指定）

Group = DEFAULT_GROUP（默认，用于逻辑分组）

Namespace = public（默认，用于环境隔离）
```

**加载顺序（优先级从高到低）：**

```
1. ${spring.application.name}-{profile}.yaml（带 Profile）
2. ${spring.application.name}.yaml（通用配置）
3. application.yaml（本地配置）
```

### 3.2 共享配置

```yaml
spring:
  cloud:
    nacos:
      config:
        # 多个服务共享的配置
        shared-configs:
          - data-id: datasource.yaml
            group: COMMON_GROUP
            refresh: true
          - data-id: redis.yaml
            group: COMMON_GROUP
            refresh: true
        
        # 扩展配置
        extension-configs:
          - data-id: custom.yaml
            group: DEFAULT_GROUP
            refresh: true
```

---

## 四、动态刷新

### 4.1 @RefreshScope

```java
@RestController
@RequestMapping("/api/config")
@RefreshScope  // 配置变更时刷新 Bean
public class ConfigController {
    
    @Value("${config.thread-pool-size:10}")
    private int threadPoolSize;
    
    @Value("${config.features.enable-notification:false}")
    private boolean notificationEnabled;
    
    @GetMapping
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("threadPoolSize", threadPoolSize);
        config.put("notificationEnabled", notificationEnabled);
        return config;
    }
}

// 使用 @ConfigurationProperties 也可以动态刷新
@Component
@ConfigurationProperties(prefix = "config")
@RefreshScope
public class AppConfig {
    private int threadPoolSize;
    // getter / setter
}
```

### 4.2 监听配置变更

```java
@Component
public class NacosConfigListener {
    
    @Autowired
    private AppConfig appConfig;
    
    @NacosConfigListener(dataId = "user-service.yaml", groupId = "DEFAULT_GROUP")
    public void onConfigChange(String config) {
        System.out.println("配置变更: " + config);
        // 自定义处理逻辑
    }
}
```

### 4.3 动态刷新原理

```
Nacos Config 动态刷新机制：

1. 应用启动时从 Nacos 拉取配置
2. 客户端建立长轮询（Long Polling）连接
3. Nacos 端配置变更时，推送变更通知
4. 客户端收到通知后拉取最新配置
5. 更新 Environment 中的 PropertySource
6. @RefreshScope 标注的 Bean 重新创建
```

---

## 五、多环境配置

### 5.1 通过 Profile 隔离

```yaml
# bootstrap.yml
spring:
  application:
    name: user-service
  profiles:
    active: dev    # 启动参数控制
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
```

```
# Nacos 中的配置：

Data ID: user-service-dev.yaml    → 开发环境
Data ID: user-service-test.yaml   → 测试环境
Data ID: user-service-prod.yaml   → 生产环境

Group: DEFAULT_GROUP
```

```bash
# 启动时指定环境
java -jar user-service.jar --spring.profiles.active=prod
```

### 5.2 通过 Namespace 隔离

```yaml
spring:
  cloud:
    nacos:
      config:
        namespace: dev-namespace    # 开发环境 Namespace ID
        # server-addr 和 Namespace 配合可实现多环境
```

```
Nacos Namespace：

dev（开发环境）
  ├── DEFAULT_GROUP
  │     ├── user-service.yaml
  │     └── order-service.yaml

prod（生产环境）
  ├── DEFAULT_GROUP
  │     ├── user-service.yaml
  │     └── order-service.yaml

不同 Namespace 的配置完全隔离，互不可见。
```

---

## 六、配置版本管理

```bash
# Nacos 控制台支持配置的版本管理

# 每次修改配置都会生成一个新版本
# 可以查看配置的历史版本和变更差异
# 支持一键回滚到任意历史版本

# CI/CD 中可以通过 Nacos OpenAPI 更新配置
curl -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  -d "dataId=user-service.yaml&group=DEFAULT_GROUP&content=$(cat config.yaml)"
```

---

## 七、Spring Cloud Config 对比

| 对比 | Nacos Config | Spring Cloud Config |
|------|-------------|-------------------|
| 存储后端 | Nacos 自身 | Git / SVN |
| 动态刷新 | 内置支持 | 需集成 Spring Cloud Bus |
| 控制台 | ✅ 自带 | ❌ 无 |
| 服务发现 | ✅ 一体化 | ❌ 需注册中心 |
| 推送机制 | 长轮询 | Webhook + Bus |
| 学习成本 | 低 | 中 |

---

## 八、总结

### 配置中心核心概念

| 概念 | 说明 |
|------|------|
| Data ID | 配置的唯一标识 = 服务名-环境.后缀 |
| Group | 逻辑分组 |
| Namespace | 环境隔离 |
| @RefreshScope | 动态刷新 Bean |
| Long Polling | 客户端长轮询检测配置变更 |

### 配置速查

```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
        shared-configs:
          - data-id: common.yaml
            refresh: true
```

**上一篇：** [Spring Cloud Gateway：网关路由与过滤器]({{< relref "post/spring-cloud-gateway" >}})

**下一篇：** [Spring Cloud 熔断限流：Sentinel 流量治理]({{< relref "post/spring-cloud-sentinel" >}})
