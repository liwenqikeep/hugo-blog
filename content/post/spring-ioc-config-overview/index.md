---
title: "Spring IoC（一）：三种配置方式概述与对比"
date: 2018-05-02
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "XML配置", "注解配置", "Java Config", "配置方式"]
toc: true
---

## 前言

Spring IoC 容器支持三种配置方式：**XML 配置**、**注解驱动**和 **Java Config**。它们不是相互替代的关系，而是 Spring 在不同时期的演进产物。

理解这三种方式的本质区别和底层原理，有助于在项目中做出合理的技术选型，也能在阅读不同时期的 Spring 项目时快速上手。

<!--more-->

## 一、配置方式的演进史

### 1.1 Spring 时代的划分

```
                        Spring 1.x
  XML 配置（一切皆 XML）
  ─────────────────────────────────
  • 所有 Bean 在 applicationContext.xml 中定义
  • Setter 注入 / 构造器注入
  • <property> <constructor-arg>
                        │
                        ▼
                        Spring 2.x
  引入注解（@Autowired、@Component）
  ─────────────────────────────────
  • 仍需要 XML 开启注解扫描：<context:component-scan>
  • @Autowired 简化注入
  • XML 作为"主配置"，注解作为"补充"
                        │
                        ▼
                        Spring 3.x
  Java Config（@Configuration、@Bean）
  ─────────────────────────────────
  • 完全用 Java 类替代 XML
  • @Configuration + @Bean + @Import
  • AnnotationConfigApplicationContext
                        │
                        ▼
                        Spring Boot
  自动配置（Auto Configuration）
  ─────────────────────────────────
  • @EnableAutoConfiguration
  • 约定大于配置
  • 三种方式并存，但自动化为主
```

### 1.2 核心配置方式概览

```
┌────────────────┬────────────────┬────────────────┐
│   XML 配置      │   注解驱动      │   Java Config   │
├────────────────┼────────────────┼────────────────┤
│ 诞生：Spring 1.x│ 诞生：Spring 2.x│ 诞生：Spring 3.x│
│                │                │                │
│ 从零到一的配置    │ 用于注入和声明式   │ 替代 XML 的类型  │
│ 模式，所有定义    │ 服务的简化        │ 安全配置方式     │
│ 写在 XML 中     │                │                │
├────────────────┼────────────────┼────────────────┤
│ 集中管理         │ 分散在类上        │ 集中管理         │
│ 可外部化         │ 零配置（纯代码）   │ 类型安全         │
│ 运行时热修改      │ 简洁直观          │ 可 IDE 重构      │
│ 啰嗦冗长         │ 受限于第三方 jar  │ Java 代码       │
└────────────────┴────────────────┴────────────────┘
```

## 二、XML 配置

### 2.1 基本形态

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="
           http://www.springframework.org/schema/beans
           http://www.springframework.org/schema/beans/spring-beans.xsd">
    
    <!-- Bean 定义 -->
    <bean id="userService" class="com.example.UserService">
        <property name="userRepository" ref="userRepository"/>
        <property name="maxRetry" value="3"/>
    </bean>
    
    <bean id="userRepository" class="com.example.UserRepository"/>
    
</beans>
```

**启动容器：**

```java
ClassPathXmlApplicationContext context = 
    new ClassPathXmlApplicationContext("applicationContext.xml");
```

### 2.2 XML 的能力与局限

**能力：**
- `id` + `class` → 最基础的 Bean 声明
- `property` / `constructor-arg` → 属性注入
- `scope` → 作用域
- `lazy-init` → 懒加载
- `init-method` / `destroy-method` → 生命周期回调
- `<list>` / `<map>` / `<set>` → 集合注入
- `<alias>` → 别名
- `<import>` → 多配置文件导入

**局限：**
- **啰嗦**：每声明一个 Bean 需要大量 XML 标签
- **类型不安全**：`class` 属性是字符串，IDE 无法做类型检查
- **运行时才发现错误**：配置错误要等到容器启动时才能发现
- **难以调试**：XML 解析错误信息不够直观

### 2.3 XML 从未消失

事实上，Spring Boot 仍然保留了 XML 支持。很多 Spring 基础设施（如 `AspectJAutoProxyBeanDefinitionParser`）在底层仍然是 XML 解析驱动的——只不过这些 XML 是 Spring 框架自带的 schema 定义，用户不需要手写。

## 三、注解驱动

### 3.1 基本形态

```java
// 仍然需要一点点 XML 来开启注解扫描
// applicationContext.xml
<context:component-scan base-package="com.example"/>
```

```java
@Component  // 声明组件
public class UserService {
    
    @Autowired  // 依赖注入
    private UserRepository userRepository;
    
    @Value("${max.retry:3}")  // 属性注入
    private int maxRetry;
    
    @PostConstruct
    public void init() {
        // 初始化逻辑
    }
}
```

**启动容器（与 XML 共存）：**

```java
ClassPathXmlApplicationContext context = 
    new ClassPathXmlApplicationContext("applicationContext.xml");
```

### 3.2 注解驱动的能力与局限

**注册 Bean 的注解：**

| 注解 | 用途 | 典型对象 |
|------|------|---------|
| `@Component` | 通用组件 | 任意类 |
| `@Service` | 业务层 | Service |
| `@Repository` | 数据访问层 | DAO / Repository |
| `@Controller` | Web 层 | Controller |
| `@Configuration` | 配置类 | 配置 |

**注入相关的注解：**

| 注解 | 注入方式 | 支持来源 |
|------|---------|---------|
| `@Autowired` | 按类型注入 | Spring 原生 |
| `@Resource` | 按名称注入（JSR-250）| JDK 自带 |
| `@Inject` | 按类型注入（JSR-330）| javax.inject |
| `@Value` | 属性注入 | Spring 原生 |
| `@Qualifier` | 限定符（解决歧义）| Spring 原生 |

### 3.3 注解驱动的核心演进

```
Spring 2.0：@Autowired、@Component 诞生
  仍需 XML：<context:component-scan>

Spring 2.5：@Service、@Repository、@Controller 加入
  简化了分层架构的组件声明

Spring 3.0：@Configuration、@Bean 加入
  Java Config 时代开始

Spring 4.0：@Conditional 加入
  条件化的 Bean 注册

Spring Boot：@EnableAutoConfiguration
  全自动配置时代
```

## 四、Java Config

### 4.1 基本形态

```java
@Configuration
@ComponentScan(basePackages = "com.example")
@PropertySource("classpath:app.properties")
public class AppConfig {
    
    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }
    
    @Bean
    public UserService userService() {
        UserService service = new UserService();
        service.setUserRepository(userRepository());
        return service;
    }
}
```

**启动容器：**

```java
AnnotationConfigApplicationContext context = 
    new AnnotationConfigApplicationContext(AppConfig.class);
```

### 4.2 Java Config 的能力

- **类型安全**：Bean 的类型是方法返回类型，编译期可检查
- **IDE 友好**：重构、跳转、代码补全都可用
- **条件注册**：结合 `@Conditional` 实现条件化配置
- **灵活**：可以在 `@Bean` 方法中写任意 Java 逻辑

```java
@Configuration
public class DataSourceConfig {
    
    @Bean
    @ConditionalOnMissingBean  // 条件注册
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource dataSource() {
        // 可以写任意逻辑
        if (profile.equals("dev")) {
            return new EmbeddedDatabaseBuilder().build();
        }
        return DataSourceBuilder.create().build();
    }
}
```

### 4.3 @Configuration 与 @Component 的区别

```java
// ⚠️ 重要区别：@Configuration 和 @Component 都可用于声明 @Bean 方法
// 但行为不同

@Configuration
public class ConfigA {
    
    @Bean
    public ServiceA serviceA() {
        // 直接调用另一个 @Bean 方法
        // 由于 @Configuration 是 CGLib 代理的，返回的是单例
        return new ServiceA(serviceB());
    }
    
    @Bean
    public ServiceB serviceB() {
        return new ServiceB();
    }
}
// → 多次调用 serviceB() 返回同一个单例对象


@Component
public class ConfigB {
    
    @Bean
    public ServiceA serviceA() {
        // 由于 @Component 不是 CGLib 代理
        // 每次调用 serviceB() 都会创建一个新实例
        return new ServiceA(serviceB());
    }
    
    @Bean
    public ServiceB serviceB() {
        return new ServiceB();
    }
}
// → 多次调用 serviceB() 返回不同实例！
```

**根本原因：** `@Configuration` 类被 CGLib 代理拦截了 `@Bean` 方法的调用，确保单例语义。而 `@Component` 类没有这个代理，方法的每次调用都是独立的。

## 五、三种方式的横向对比

### 5.1 维度对比

| 维度 | XML | 注解驱动 | Java Config |
|------|-----|---------|------------|
| **Bean 声明** | `<bean>` | `@Component` 等 | `@Bean` |
| **注入** | `<property>` / `<constructor-arg>` | `@Autowired` / `@Resource` | 方法参数注入 |
| **外部配置** | `<context:property-placeholder>` | `@Value` | `@PropertySource` + `@Value` |
| **条件注册** | Spring 4+ 支持 | `@Conditional` | `@Conditional` / `@ConditionalOnXxx` |
| **类型安全** | ❌（字符串） | ⚠️（部分）| ✅（编译期）|
| **集中管理** | ✅ | ❌（分散在各 Class） | ✅ |
| **可重构** | ❌ | ⚠️（IDE 可查找使用）| ✅ |
| **学习成本** | 中 | 低 | 中 |
| **启动速度** | 快 | 中（需扫描）| 中（需解析注解）|
| **调试友好** | 差（XML 错误） | 好 | 好 |

### 5.2 适用场景

```
          XML 为主
        ┌─────────────────────┐
        │ • 有现成的 XML 配置 │
        │   （遗留项目维护）    │
        │ • AOP 切面定义      │
        │   （AspectJ 语法）  │
        │ • 第三方集成（XSD）  │
        └─────────────────────┘

         注解 + Java Config（主流）
        ┌─────────────────────┐
        │ • 新项目            │
        │ • Spring Boot 项目  │
        │ • 微服务            │
        └─────────────────────┘

          混合配置
        ┌─────────────────────┐
        │ • 老项目迁移中       │
        │ • 第三方库需 XML    │
        │   + 自有代码用注解  │
        └─────────────────────┘
```

### 5.3 混合配置举例

```xml
<!-- XML 中加载 Java Config 配置类 -->
<bean class="com.example.AppConfig"/>
```

```java
// Java Config 中引入 XML 配置
@Configuration
@ImportResource("classpath:old-beans.xml")
public class HybridConfig {
    
    @Bean
    public NewService newService() {
        return new NewService();
    }
}
```

## 六、总结

### 演进路线

```
XML 配置 ──▶ XML + 注解 ──▶ Java Config ──▶ Spring Boot 自动配置
(Spr 1.x)    (Spr 2.x)      (Spr 3.x)       (Spring Boot)
```

### 一句话总结

- **XML**：最原始的 IoC 表达方式，啰嗦但集中
- **注解**：简化了 Bean 声明和注入，松散但简洁
- **Java Config**：类型安全的配置方式，填补了注解的不足
- **三者在底层统一为 BeanDefinition**，只是"怎么写"的区别

---

**下一篇：** [Spring IoC（二）：配置方式的底层解析原理]({{< relref "post/spring-ioc-config-parsing" >}})
