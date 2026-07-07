---
title: "Spring Cloud 熔断限流：Sentinel 流量治理"
date: 2019-04-15
draft: false
categories: ["Spring Cloud"]
tags: ["Spring Cloud", "Sentinel", "限流", "熔断", "流量治理"]
toc: true
---

## 前言

Sentinel 是阿里巴巴开源的流量治理组件，以"流量"为切入点，提供**流量控制**、**熔断降级**、**系统保护**等功能。相比 Hystrix，Sentinel 有更丰富的流量治理能力和更好的性能。

<!--more-->

## 一、Sentinel 核心概念

### 1.1 三大核心功能

```
1. 流量控制（Flow Control）
   控制进入系统的请求速率
   支持：QPS 限流、线程数限流、Warm Up、排队等待

2. 熔断降级（Circuit Breaking）
   当资源不稳定时（慢调用、异常比例高），熔断对该资源的调用
   支持：慢调用比例、异常比例、异常数

3. 系统保护（System Protection）
   当系统负载过高时，自动限制入口流量
   支持：Load、CPU、RT、线程数、入口 QPS
```

### 1.2 资源概念

```
在 Sentinel 中，一切皆资源（Resource）。

资源可以是：
├── HTTP 接口（/api/users）
├── 方法调用（Service.method()）
├── SQL 查询
└── 自定义代码块
```

---

## 二、快速入门

### 2.1 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

### 2.2 配置

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080     # Sentinel 控制台地址
        port: 8719                     # 客户端端口
      eager: true                      # 立即连接控制台
      datasource:                      # 动态规则源（Nacos）
        flow:
          nacos:
            server-addr: 127.0.0.1:8848
            data-id: ${spring.application.name}-sentinel-flow
            group-id: SENTINEL_GROUP
            data-type: json
            rule-type: flow
```

### 2.3 启动控制台

```bash
# 下载 Sentinel Dashboard
wget https://github.com/alibaba/Sentinel/releases/download/1.8.4/sentinel-dashboard-1.8.4.jar

# 启动控制台
java -Dserver.port=8080 \
     -jar sentinel-dashboard-1.8.4.jar

# 访问 http://localhost:8080
# 默认账号：sentinel / sentinel
```

---

## 三、流量控制

### 3.1 控制台配置限流规则

```
在 Sentinel Dashboard 中配置：

资源名：/api/users/list
阈值类型：QPS
单机阈值：100
流控模式：直接
流控效果：快速失败
```

### 3.2 代码配置限流规则

```java
@Configuration
public class SentinelConfig {
    
    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        
        FlowRule rule = new FlowRule();
        rule.setResource("/api/users/list");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);  // QPS 限流
        rule.setCount(100);                          // 阈值
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);  // 快速失败
        
        rules.add(rule);
        FlowRuleManager.loadRules(rules);
    }
}

// 启动后，在 Sentinel Dashboard 可以看到资源实时监控
```

### 3.3 @SentinelResource 注解

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @GetMapping("/list")
    @SentinelResource(
        value = "/api/users/list",
        blockHandler = "listBlockHandler",       // 限流后的处理方法
        fallback = "listFallback"                 // 异常后的处理方法
    )
    public List<User> list(@RequestParam(defaultValue = "1") int page) {
        // 正常业务逻辑
        return userService.findPage(page);
    }
    
    // blockHandler：限流/熔断时调用（需在同一类中，参数多一个 BlockException）
    public List<User> listBlockHandler(int page, BlockException ex) {
        log.warn("被限流了: {}", ex.getMessage());
        return Collections.emptyList();
    }
    
    // fallback：业务异常时调用
    public List<User> listFallback(int page, Throwable ex) {
        log.error("查询失败: {}", ex.getMessage());
        return Collections.emptyList();
    }
}
```

---

## 四、熔断降级

### 4.1 熔断规则

```java
// 慢调用比例熔断
DegradeRule slowRule = new DegradeRule();
slowRule.setResource("/api/orders/create");
slowRule.setGrade(RuleConstant.DEGRADE_GRADE_RT);    // 响应时间
slowRule.setCount(200);                               // 超过 200ms 算慢
slowRule.setSlowRatioThreshold(0.5);                  // 慢调用比例阈值 50%
slowRule.setMinRequestAmount(10);                     // 最小请求数
slowRule.setStatIntervalMs(1000);                     // 统计时长
slowRule.setTimeWindow(10);                           // 熔断时长（秒）

// 异常比例熔断
DegradeRule exceptionRule = new DegradeRule();
exceptionRule.setResource("/api/orders/create");
exceptionRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);  // 异常比例
exceptionRule.setCount(0.2);                        // 异常比例阈值 20%
exceptionRule.setMinRequestAmount(10);
exceptionRule.setTimeWindow(30);

List<DegradeRule> rules = new ArrayList<>();
rules.add(slowRule);
rules.add(exceptionRule);
DegradeRuleManager.loadRules(rules);
```

### 4.2 熔断状态机

```
Sentinel 熔断器的三种状态：

CLOSED（关闭）：
  ├── 正常请求通过
  └── 异常比例/慢调用 > 阈值 → OPEN

OPEN（打开）：
  ├── 请求快速失败
  └── 时间窗口后 → HALF_OPEN

HALF_OPEN（半开）：
  ├── 放行少量请求探测
  ├── 成功 → CLOSED
  └── 失败 → OPEN
```

---

## 五、热点参数限流

```java
@GetMapping("/search")
@SentinelResource(
    value = "/api/users/search",
    blockHandler = "searchBlockHandler"
)
public List<User> search(
    @RequestParam String keyword,
    @RequestParam(defaultValue = "1") int page) {
    return userService.search(keyword, page);
}
```

```java
// 热点规则配置
ParamFlowRule rule = new ParamFlowRule();
rule.setResource("/api/users/search");
rule.setParamIdx(0);                    // 对第一个参数限流
rule.setCount(5);                       // 每秒 5 次
rule.setDurationInSec(1);

// 参数例外项
ParamFlowItem item = new ParamFlowItem();
item.setObject("hot-keyword");          // 这个参数单独限流
item.setClassType(String.class.getName());
item.setCount(2);                       // 每秒只能 2 次
rule.setParamFlowItemList(List.of(item));

ParamFlowRuleManager.loadRules(List.of(rule));
```

---

## 六、系统保护

```java
// 系统规则：当系统 Load 过高时自动拒绝请求
SystemRule rule = new SystemRule();
rule.setHighestSystemLoad(10.0);         // Load 阈值
rule.setAvgRt(1000);                     // 平均 RT
rule.setMaxQps(10000);                   // 入口 QPS
rule.setHighestCpuUsage(0.8);           // CPU 使用率

SystemRuleManager.loadRules(List.of(rule));
```

---

## 七、整合 Feign

```yaml
# application.yml
feign:
  sentinel:
    enabled: true     # 开启 Feign 的 Sentinel 支持
```

```java
@FeignClient(
    name = "user-service",
    fallbackFactory = UserFallbackFactory.class
)
public interface UserFeignClient {
    
    @GetMapping("/api/users/{id}")
    User getUser(@PathVariable("id") Long id);
}

@Component
public class UserFallbackFactory implements FallbackFactory<UserFeignClient> {
    
    @Override
    public UserFeignClient create(Throwable cause) {
        return id -> {
            // 熔断降级后的返回
            User empty = new User();
            empty.setId(id);
            return empty;
        };
    }
}
```

---

## 八、Sentinel vs Hystrix

| 对比 | Sentinel | Hystrix（维护模式）|
|------|----------|:-----------------:|
| 限流 | ✅ QPS/线程数/Warm Up | ❌ 不支持 |
| 熔断 | ✅ 慢调用/异常比例/异常数 | ✅ 仅异常比例 |
| 控制台 | ✅ 实时监控 + 规则配置 | ❌ |
| 动态规则 | ✅ Nacos/APOLLO/ZK | ❌ |
| 系统保护 | ✅ Load/CPU | ❌ |

---

## 九、总结

### 核心规则速查

| 规则类型 | 作用 | 关键参数 |
|---------|------|---------|
| FlowRule | 流量控制 | grade(QPS/线程数)、count |
| DegradeRule | 熔断降级 | grade(RT/异常比例)、timeWindow |
| SystemRule | 系统保护 | highestSystemLoad、avgRt |
| ParamFlowRule | 热点限流 | paramIdx、count |

### 注解速查

```java
@SentinelResource(
    value = "resource-name",
    blockHandler = "限流处理方法",
    fallback = "异常处理方法"
)
```

**上一篇：** [Spring Cloud 配置中心：Nacos Config 动态配置管理]({{< relref "post/spring-cloud-config-nacos" >}})

**下一篇：** [Spring Cloud 链路追踪：Sleuth + Zipkin 全链路监控]({{< relref "post/spring-cloud-sleuth" >}})
