---
title: "Spring IoC（三）：选型指南与迁移实践"
date: 2018-05-06
draft: false
categories: ["Java"]
tags: ["Spring", "IoC", "配置选型", "XML迁移", "Java Config", "最佳实践"]
toc: true
---

## 前言

前两篇文章分别从使用层面和源码层面分析了三种 IoC 配置方式。这篇文章回到实践——**在真实项目中，到底该怎么选、怎么迁移、怎么混用？**

<!--more-->

## 一、选型决策树

### 1.1 项目类型决定配置方式

```
开始选型
    │
    ▼
项目是新项目还是老项目？
    │
    ├── 新项目 ────────────────────── 推荐 Java Config + 注解
    │       │
    │       ├── 用 Spring Boot？─── ✅ Java Config + 自动配置
    │       │       └── 绝大部分用 application.yml，少部分 @Configuration
    │       │
    │       └── 用 Spring Framework？─ ✅ Java Config + @ComponentScan
    │               └── AnnotationConfigApplicationContext
    │
    └── 老项目（已有大量 XML）──────── 逐步迁移，不要一步到位
            │
            ├── 混合模式：XML + Java Config + 注解并存
            └── 阶段性推进：先加注解注入 → 再加 Java Config → 最后移除 XML
```

### 1.2 场景化决策表

| 场景 | 推荐方式 | 理由 |
|------|---------|------|
| 新项目（Spring Boot） | `application.yml` + `@Configuration` | 约定大于配置，零 XML |
| 新项目（Spring Framework） | `@Configuration` + `@ComponentScan` | 类型安全，IDE 友好 |
| 遗留 XML 项目维护 | 保持 XML + 新代码用注解/Config | 最小改动，渐进迁移 |
| 第三方库集成（有 XSD） | XML（如 `tx:annotation-driven`） | XML 命名空间简洁 |
| AOP 切面定义 | `@Aspect` + `@Pointcut` | 注解显式、更易维护 |
| 条件化 Bean 注册 | `@Conditional` / `@ConditionalOnXxx` | Java Config 灵活 |
| 框架底层配置 | XML 或 Java Config | 需要在代码中动态定义 Bean |
| 单元测试 | Java Config + `@Bean` | 临时替换依赖 |

## 二、从 XML 迁移到 Java Config

### 2.1 迁移路线图

```
第一阶段：引入注解注入
  XML 保持不变，新代码用 @Autowired
  └─ <context:annotation-config/> 添加到 XML
     （不需要 <context:component-scan>，只激活 @Autowired）

第二阶段：替换 Bean 定义为 Java Config
  XML → @Configuration + @Bean
  用 @ImportResource 加载尚未迁移的 XML

第三阶段：移除 XML
  所有 Bean 定义迁移完成 → 删掉 XML → 切换到 AnnotationConfigApplicationContext
```

### 2.2 映射关系速查

```xml
<!-- XML 片段 -->
<bean id="userService" class="com.example.UserService">
    <property name="userRepository" ref="userRepository"/>
    <property name="maxRetry" value="3"/>
</bean>

<bean id="userRepository" class="com.example.UserRepository"/>
```

```java
// 等价 Java Config
@Configuration
public class AppConfig {
    
    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }
    
    @Bean
    public UserService userService() {
        UserService service = new UserService();
        service.setUserRepository(userRepository());
        service.setMaxRetry(3);
        return service;
    }
}
```

**其他 XML 元素到 Java Config 的映射：**

| XML | Java Config |
|-----|------------|
| `<property name="x" ref="y"/>` | `setX(y())` 或方法参数注入 |
| `<property name="x" value="3"/>` | `setX(3)` |
| `<constructor-arg ref="x"/>` | 构造器参数 `(X x)` |
| `<property name="list"> <list>...</list>` | `setList(Arrays.asList(...))` |
| `<context:property-placeholder location="..."/>` | `@PropertySource("classpath:...")` |
| `<context:component-scan base-package="..."/>` | `@ComponentScan("...")` |
| `<bean class="..." scope="prototype"/>` | `@Scope("prototype")` |
| `<bean class="..." lazy-init="true"/>` | `@Lazy` |
| `<bean class="..." init-method="init"/>` | `@Bean(initMethod = "init")` 或 `@PostConstruct` |
| `<bean class="..." destroy-method="cleanup"/>` | `@Bean(destroyMethod = "cleanup")` 或 `@PreDestroy` |
| `<alias name="x" alias="y"/>` | `@Bean("x", "y")` |

### 2.3 实用的迁移模式

**模式一：@ImportResource 桥接**

```java
@Configuration
// 保留未迁移的 XML 配置
@ImportResource("classpath:legacy-config.xml")
public class MigrationConfig {
    
    // 新代码用 Java Config
    @Bean
    public NewService newService() {
        return new NewService();
    }
    
    // 新 Bean 可以引用 XML 中定义的 Bean
    @Bean
    public FacadeService facadeService(LegacyService legacyService /* 来自 XML */) {
        return new FacadeService(legacyService);
    }
}
```

**模式二：逐模块迁移**

```
项目结构：
com.example
├── module-a/          → 已迁移到 Java Config + 注解
│   ├── service/
│   └── ModuleAConfig.java
├── module-b/          → 仍使用 XML
│   ├── service/
│   └── spring/module-b.xml
└── RootConfig.java    → @Import({ModuleAConfig.class})
                       → @ImportResource("classpath*:spring/module-*.xml")
```

```java
@Configuration
@Import(ModuleAConfig.class)  // 已迁移的模块
@ImportResource({
    "classpath:com/example/module-b/spring/module-b.xml",  // 未迁移的模块
    "classpath:com/example/module-c/spring/module-c.xml"
})
public class RootConfig {
    // 全局配置
}
```

## 三、混合配置策略

### 3.1 何时需要混用

```
场景一：第三方库强制要求 XML
  <tx:annotation-driven/>  → 可以用 @EnableTransactionManagement 替代
  <mvc:annotation-driven/> → 可以用 @EnableWebMvc 替代
  但有些古老的库可能只提供 XSD，没有 Java Config 等价物

场景二：运行时动态注册 Bean
  XML 做不到的 → 用 BeanDefinitionRegistryPostProcessor
  Java Config 做不到的 → 用 ImportBeanDefinitionRegistrar

场景三：外部化配置
  Spring Boot 的 application.yml 适用于大部分场景
  少数需要动态调整的参数可以用 XML 暴露
```

### 3.2 等价注解配置对照

```xml
<!-- XML 中常见的"一句话配置" -->
<context:component-scan base-package="com.example"/>
```
```java
// 等价 Java Config
@Configuration
@ComponentScan("com.example")
public class AppConfig {}
```

```xml
<context:annotation-config/>
```
```java
// 等价 — 激活 @Autowired、@PostConstruct 等
// 实际上在 @Configuration 类中默认已激活
```

```xml
<mvc:annotation-driven/>
```
```java
@EnableWebMvc
```

```xml
<tx:annotation-driven transaction-manager="transactionManager"/>
```
```java
@EnableTransactionManagement
```

```xml
<aop:aspectj-autoproxy/>
```
```java
@EnableAspectJAutoProxy
```

```xml
<cache:annotation-driven/>
```
```java
@EnableCaching
```

```xml
<task:annotation-driven/>
```
```java
@EnableScheduling
```

### 3.3 混合配置的优先级

当三种配置方式一起使用时，Bean 定义的**注册顺序**决定了覆盖关系：

```
注册顺序（由 refresh() 执行步骤决定）：
1. @ComponentScan 扫描出的 Bean（来自 ConfigurationClassPostProcessor）
2. @Bean 方法定义的 Bean
3. @ImportResource 引入的 XML Bean
4. <context:component-scan> 扫描的 Bean
5. XML 中的 <bean> 定义

覆盖规则：
- 后注册的同名 Bean 覆盖先注册的（由 allowBeanDefinitionOverriding 控制）
- 默认不允许覆盖，会抛出异常
- 可通过 spring.main.allow-bean-definition-overriding=true 开启
```

**建议：不要在同一个项目中让三种配置方式定义同名的 Bean。**

## 四、Spring Boot 时代的配置

### 4.1 Spring Boot 的主张

```
 application.yml / application.properties → 外部化配置
 @Configuration + @Bean → Java 配置类
 @ComponentScan → 组件扫描（默认扫描启动类所在包）
 @EnableAutoConfiguration → 自动配置
```

Spring Boot 简化到极致：

```java
@SpringBootApplication  // 等价于 @Configuration + @EnableAutoConfiguration + @ComponentScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4.2 Spring Boot 自动配置原理

Spring Boot 的自动配置本质上还是 Java Config——每个 `*AutoConfiguration` 类都是 `@Configuration`：

```java
@Configuration
@ConditionalOnClass(DataSource.class)  // 类路径上有 DataSource 才生效
@ConditionalOnMissingBean(DataSource.class)  // 没有自定义 DataSource 才生效
@EnableConfigurationProperties(DataSourceProperties.class)  // 绑定 application.yml 配置
public class DataSourceAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource(DataSourceProperties properties) {
        // 根据 application.yml 中的 spring.datasource.* 创建
        return properties.initializeDataSourceBuilder().build();
    }
}
```

**自动配置的大量 `@ConditionalOn*` 注解：**

| 注解 | 条件 |
|------|------|
| `@ConditionalOnClass` | 类路径存在指定类 |
| `@ConditionalOnMissingClass` | 类路径不存在指定类 |
| `@ConditionalOnBean` | 容器中有指定类型的 Bean |
| `@ConditionalOnMissingBean` | 容器中没有指定类型的 Bean |
| `@ConditionalOnProperty` | 配置项有指定值 |
| `@ConditionalOnExpression` | SpEL 表达式为 true |
| `@ConditionalOnResource` | 类路径存在指定资源 |
| `@ConditionalOnWebApplication` | 是 Web 应用 |
| `@ConditionalOnNotWebApplication` | 不是 Web 应用 |

### 4.3 Spring Boot 中如何使用 XML

虽然 Spring Boot 不推荐 XML，但也保留了支持：

```java
@SpringBootApplication
@ImportResource("classpath:legacy.xml")  // Spring Boot 中加载 XML
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

```properties
# 或者通过配置开启
spring.main.allow-bean-definition-overriding=true
```

## 五、配置方式之外的选择

### 5.1 用工厂方法替代配置

```java
// 不需要容器管理的简单对象，不需要交给 Spring
// 用静态工厂方法替代 @Bean
public class BeanFactory {
    
    public static UserService createUserService() {
        return new UserService(new UserRepository());
    }
}
```

**适用场景：** 对象依赖简单、不需要 AOP、不需要生命周期管理。

### 5.2 用 BeanDefinitionRegistryPostProcessor 动态注册

```java
@Component
public class MyBeanRegistrar implements BeanDefinitionRegistryPostProcessor {
    
    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // 动态创建 BeanDefinition
        GenericBeanDefinition bd = new GenericBeanDefinition();
        bd.setBeanClass(DynamicService.class);
        bd.getPropertyValues().add("name", "dynamic");
        
        // 注册到容器
        registry.registerBeanDefinition("dynamicService", bd);
    }
}
```

## 六、总结

### 适合新项目的配置方式

```
Spring Boot → application.yml + @Configuration + 注解
                   ↓
             零 XML、自动配置、约定大于配置

Spring Framework → @Configuration + @ComponentScan + @Bean
                   ↓
             类型安全、IDE 友好、重构友好
```

### 适合老项目的迁移策略

```
不迁移 ← 如果项目稳定运行、没有扩展需求，保持现状不折腾
                │
                ▼
最小迁移 ← 只是引入 @Autowired 减少注入代码
                │
                ▼
渐进迁移 ← 新模块用 Java Config，旧模块保持 XML
                │
                ▼
完全迁移 ← 最终统一为 Java Config + 注解，移除 XML 依赖
```

### 配置方式的选择不是非此即彼

| 层级 | 推荐方式 |
|------|---------|
| 应用层配置 | `application.yml`（外部化）|
| 框架层配置 | `@EnableXxx`（注解驱动）|
| Bean 声明 | `@Component`（自动扫描）+ `@Bean`（手动声明）|
| 依赖注入 | `@Autowired` / 构造器注入 |
| 条件注册 | `@Conditional` / `@ConditionalOnXxx` |
| 遗留集成 | `@ImportResource`（XML 桥接）|

---

**上一篇：** [Spring IoC（二）：配置方式的底层解析原理]({{< relref "post/spring-ioc-config-parsing" >}})

**系列索引：**
- [Spring IoC（一）：三种配置方式概述与对比]({{< relref "post/spring-ioc-config-overview" >}})
- [Spring IoC（二）：配置方式的底层解析原理]({{< relref "post/spring-ioc-config-parsing" >}})
- [Spring IoC（三）：选型指南与迁移实践]({{< relref "post/spring-ioc-config-migration" >}})
