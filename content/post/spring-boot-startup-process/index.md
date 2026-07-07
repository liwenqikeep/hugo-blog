---
title: "Spring Boot（二）：启动流程"
date: 2019-03-30
draft: false
categories: ["Java"]
tags: ["Spring Boot", "SpringApplication", "启动流程", "ApplicationContextInitializer"]
toc: true
---

## 前言

`SpringApplication.run()` 是 Spring Boot 应用的入口点。从执行这个静态方法开始，到应用程序完全启动并对外提供服务，中间经历了一系列精心设计的阶段。

本文从源码入手，逐层解析 Spring Boot 的启动流程。

<!--more-->

> **源码版本：** Spring Boot 2.3.x

## 一、SpringApplication 的创建

### 1.1 启动入口

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        // 分两步：
        // 1. 创建 SpringApplication 实例
        // 2. 调用 run 方法
        SpringApplication.run(Application.class, args);
    }
}
```

### 1.2 创建过程

```java
public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
    return run(new Class<?>[] { primarySource }, args);
}

public static ConfigurableApplicationContext run(Class<?>[] primarySources, String[] args) {
    return new SpringApplication(primarySources).run(args);
}
```

```java
public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
    // 1. 保存主配置类
    this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
    
    // ★ 2. 推断 Web 应用类型（NONE / SERVLET / REACTIVE）
    this.webApplicationType = WebApplicationType.deduceFromClasspath();
    
    // ★ 3. 加载 BootstrapRegistryInitializer
    //     从 spring.factories 中获取
    this.bootstrapRegistryInitializers = getBootstrapRegistryInitializersFromSpringFactory();
    
    // ★ 4. 加载 ApplicationContextInitializer
    setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
    
    // ★ 5. 加载 ApplicationListener
    setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
    
    // 6. 推断主类（main 方法所在的类）
    this.mainApplicationClass = deduceMainApplicationClass();
}
```

---

## 二、run() 方法的执行

```java
public ConfigurableApplicationContext run(String... args) {
    // 1. 计时器
    StopWatch stopWatch = new StopWatch();
    stopWatch.start();
    
    // 2. 创建启动上下文
    DefaultBootstrapContext bootstrapContext = createBootstrapContext();
    
    // 3. 准备环境
    ConfigurableEnvironment environment = prepareEnvironment(listeners, bootstrapContext, applicationArguments);
    
    // 4. 打印 Banner
    printBanner(environment);
    
    // 5. 创建 ApplicationContext
    context = createApplicationContext();
    
    // 6. 准备上下文
    prepareContext(bootstrapContext, context, environment, listeners, applicationArguments, printedBanner);
    
    // 7. 刷新上下文（★ 核心：执行 refresh()）
    refreshContext(context);
    
    // 8. 刷新后处理
    afterRefresh(context, applicationArguments);
    
    // 9. 停止计时，发布启动完成事件
    stopWatch.stop();
    listeners.started(context);
    
    // 10. 执行 Runner
    callRunners(context, applicationArguments);
    
    return context;
}
```

### 流程概览

```
SpringApplication.run()
    │
    ├── 1. createBootstrapContext()        — 创建启动上下文
    │
    ├── 2. prepareEnvironment()            — 准备 Environment
    │      ├── 配置 PropertySource
    │      └── 激活 Profile
    │
    ├── 3. printBanner()                   — 打印启动 Banner
    │
    ├── 4. createApplicationContext()      — 创建 IoC 容器
    │      └── AnnotationConfigServletWebServerApplicationContext
    │
    ├── 5. prepareContext()                — 准备上下文
    │      ├── 设置 Environment
    │      ├── 执行 Initializer
    │      └── 加载主配置类
    │
    ├── 6. refreshContext()                — ★ 刷新容器
    │      └── AbstractApplicationContext.refresh()
    │            ├── prepareRefresh()
    │            ├── obtainFreshBeanFactory()
    │            ├── prepareBeanFactory()
    │            ├── postProcessBeanFactory()
    │            ├── invokeBeanFactoryPostProcessors()  ★ 自动配置在此加载
    │            ├── registerBeanPostProcessors()
    │            ├── initMessageSource()
    │            ├── initApplicationEventMulticaster()
    │            ├── onRefresh()             ★ 创建 Web Server
    │            ├── registerListeners()
    │            ├── finishBeanFactoryInitialization()
    │            └── finishRefresh()
    │
    ├── 7. afterRefresh()                  — 刷新后回调
    │
    ├── 8. listeners.started()             — 发布 ApplicationStartedEvent
    │
    └── 9. callRunners()                   — 执行 CommandLineRunner / ApplicationRunner
```

---

## 三、关键步骤详解

### 3.1 准备 Environment

```java
private ConfigurableEnvironment prepareEnvironment(
        SpringApplicationRunListeners listeners,
        DefaultBootstrapContext bootstrapContext,
        ApplicationArguments applicationArguments) {
    
    // 1. 创建 Environment（根据 Web 类型创建不同的 Environment）
    ConfigurableEnvironment environment = getOrCreateEnvironment();
    
    // 2. 配置 PropertySource
    //    ★ 加载 application.properties / application.yml
    //    ★ 按优先级排序 PropertySource
    configureEnvironment(environment, applicationArguments.getSourceArgs());
    
    // 3. 绑定 SpringApplication 的配置到 Environment
    ConfigurationPropertySources.attach(environment);
    
    // 4. 发布 EnvironmentPreparedEvent
    listeners.environmentPrepared(bootstrapContext, environment);
    
    return environment;
}
```

**PropertySource 加载顺序（优先级从高到低）：**

```
1. @PropertySource 注解（@SpringBootApplication 所在类）
2. application-{profile}.yml（激活的 profile）
3. application.yml（主配置文件）
4. 随机属性（random.int / random.long）
5. OS 环境变量
6. 系统属性（System.getProperties()）
7. 命令行参数（--server.port=8080）
```

### 3.2 创建 ApplicationContext

```java
protected ConfigurableApplicationContext createApplicationContext() {
    // 根据 webApplicationType 选择不同的 ApplicationContext
    switch (this.webApplicationType) {
        case SERVLET:
            // ★ 最常用的 Web 应用
            return new AnnotationConfigServletWebServerApplicationContext();
        case REACTIVE:
            return new AnnotationConfigReactiveWebServerApplicationContext();
        case NONE:
            return new AnnotationConfigApplicationContext();
    }
}
```

### 3.3 准备上下文

```java
private void prepareContext(DefaultBootstrapContext bootstrapContext,
                            ConfigurableApplicationContext context,
                            ConfigurableEnvironment environment,
                            SpringApplicationRunListeners listeners,
                            ApplicationArguments applicationArguments,
                            Banner printedBanner) {
    
    // 1. 设置 Environment
    context.setEnvironment(environment);
    
    // ★ 2. 执行 ApplicationContextInitializer
    applyInitializers(context);
    
    // 3. 发布 ContextPreparedEvent
    listeners.contextPrepared(context);
    
    // 4. 注册启动参数 Bean
    context.getBeanFactory().registerSingleton("springApplicationArguments", applicationArguments);
    
    // 5. 注册 Banner
    if (printedBanner != null) {
        context.getBeanFactory().registerSingleton("springBootBanner", printedBanner);
    }
    
    // 6. 卸载 BootstrapContext
    bootstrapContext.close(context);
    
    // ★ 7. 加载主配置类
    Set<Object> sources = getAllSources();
    load(context, sources.toArray(new Object[0]));
    
    // 8. 发布 ContextLoadedEvent
    listeners.contextLoaded(context);
}
```

### 3.4 refreshContext——最核心的步骤

```java
private void refreshContext(ConfigurableApplicationContext context) {
    // 调用 AbstractApplicationContext.refresh()
    refresh(context);
    
    // 注册 Shutdown Hook
    if (this.registerShutdownHook) {
        try {
            context.registerShutdownHook();
        } catch (AccessControlException ex) {
            // ...
        }
    }
}
```

`refresh()` 在 Spring DI（一）中已详细分析过，这里重点看 Spring Boot 添加的部分：

```
AbstractApplicationContext.refresh()
    │
    ├── ...之前的步骤省略...
    │
    ├── onRefresh()  ★ Spring Boot 在此创建 Web 服务器
    │     └── ServletWebServerApplicationContext.onRefresh()
    │           └── createWebServer()
    │                 └── factory.getWebServer(getSelfInitializer())
    │                       └── TomcatServletWebServerFactory
    │                             └── 创建 Tomcat 并启动
    │
    ├── finishRefresh()
    │     └── 启动 Web 服务器（tomcat.start()）
    │
    └── ...
```

### 3.5 createWebServer——创建嵌入式 Web 容器

```java
// ServletWebServerApplicationContext.java
private void createWebServer() {
    ServletWebServerFactory factory = getWebServerFactory();
    
    // 获取 ServletContextInitializer（包括 DispatcherServlet 等）
    SelfInitializer initializer = getSelfInitializer();
    
    // ★ 创建并启动 Web 服务器
    this.webServer = factory.getWebServer(initializer);
}
```

**TomcatServletWebServerFactory 的创建过程：**

```java
public WebServer getWebServer(ServletContextInitializer... initializers) {
    // 1. 创建 Tomcat 实例
    Tomcat tomcat = new Tomcat();
    
    // 2. 配置端口、协议等
    tomcat.setPort(serverProperties.getPort());  // 默认 8080
    
    // 3. 设置基础目录
    File baseDir = (this.baseDirectory != null) ? this.baseDirectory : createTempDir("tomcat");
    tomcat.setBaseDir(baseDir.getAbsolutePath());
    
    // 4. 添加 Connector
    Connector connector = new Connector(this.protocol);
    tomcat.getService().addConnector(connector);
    
    // 5. 配置 Host、Engine 等
    // ...
    
    // ★ 6. 注册 DispatcherServlet
    prepareContext(tomcat.getHost(), initializers);
    
    // 7. 返回 TomcatWebServer
    return new TomcatWebServer(tomcat, getPort() >= 0);
}
```

---

## 四、SpringApplicationRunListeners

### 4.1 事件发布时机

| 阶段 | 事件 | 说明 |
|------|------|------|
| Environment 就绪 | `ApplicationEnvironmentPreparedEvent` | 配置已加载 |
| ApplicationContext 就绪 | `ApplicationContextInitializedEvent` | 容器创建完成 |
| BeanDefinition 加载 | `ApplicationPreparedEvent` | 配置类已加载 |
| refresh 完成 | `ApplicationStartedEvent` | 容器刷新完成 |
| 启动完成 | `ApplicationReadyEvent` | 业务可以开始 |
| 启动失败 | `ApplicationFailedEvent` | 启动异常 |

### 4.2 监听器的执行

```java
// SpringApplicationRunListeners 封装了多个监听器
// 在每个关键阶段调用对应的方法

interface SpringApplicationRunListener {
    void starting(DefaultBootstrapContext bootstrapContext);              // 启动开始
    void environmentPrepared(DefaultBootstrapContext bootstrapContext,    // Environment 就绪
                              ConfigurableEnvironment environment);
    void contextPrepared(ConfigurableApplicationContext context);         // Context 已创建
    void contextLoaded(ConfigurableApplicationContext context);           // BeanDefinition 加载完成
    void started(ConfigurableApplicationContext context);                 // 启动完成
    void running(ConfigurableApplicationContext context);                 // 正在运行
    void failed(ConfigurableApplicationContext context, Throwable exception);  // 启动失败
}
```

---

## 五、CommandLineRunner 与 ApplicationRunner

### 5.1 启动后执行

```java
// Spring Boot 启动完成后，会执行所有的 Runner

// ApplicationRunner（推荐）
@Component
public class MyApplicationRunner implements ApplicationRunner {
    
    @Override
    public void run(ApplicationArguments args) {
        System.out.println("应用启动完成，参数：" + args.getOptionNames());
        // 执行初始化代码
    }
}

// CommandLineRunner（简单）
@Component
public class MyCommandLineRunner implements CommandLineRunner {
    
    @Override
    public void run(String... args) {
        System.out.println("应用启动完成");
    }
}
```

### 5.2 @Order 控制执行顺序

```java
@Component
@Order(1)
public class CacheInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 优先执行缓存预热
    }
}

@Component
@Order(2)
public class DataInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 再执行数据初始化
    }
}
```

---

## 六、异常处理

```java
// Spring Boot 启动失败的统一处理

public ConfigurableApplicationContext run(String... args) {
    try {
        // ... 启动流程 ...
        return context;
    } catch (Throwable ex) {
        // 处理启动异常
        handleRunFailure(context, ex, listeners);
        throw new IllegalStateException(ex);
    }
}

// 自定义启动失败处理器
@Bean
public SpringBootExceptionReporter springBootExceptionReporter() {
    return exception -> {
        log.error("应用启动失败", exception);
        // 发送告警、记录日志等
        return true;
    };
}
```

---

## 七、总结

### 启动流程速记

```
new SpringApplication() → 初始化元信息
    ↓
run()
    ├── 准备 Environment（加载配置）
    ├── 创建 ApplicationContext
    ├── 执行 Initializer
    ├── refreshContext（★ 核心）
    │     └── onRefresh() → 创建 Web 服务器
    └── 执行 Runner → 应用就绪
```

### 关键扩展点

| 扩展点 | 时机 | 用途 |
|--------|------|------|
| ApplicationContextInitializer | context 创建后 | 修改容器配置 |
| ApplicationListener | 各阶段 | 监听启动事件 |
| ApplicationRunner | 启动完成后 | 初始化业务 |
| CommandLineRunner | 启动完成后 | 简单初始化 |
| SpringBootExceptionReporter | 启动失败 | 异常处理 |

**上一篇：** [Spring Boot（一）：自动配置原理]({{< relref "post/spring-boot-auto-configuration" >}})

**下一篇：** [Spring Boot（三）：外部化配置]({{< relref "post/spring-boot-external-config" >}})
