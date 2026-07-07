---
title: "Dubbo（三）：源码与高级特性"
date: 2021-08-14
draft: false
categories: ["分布式"]
tags: ["Dubbo", "源码", "SPI", "代理", "Filter", "高级特性"]
toc: true
---

## 前言

本文从源码层面深入 Dubbo 的核心机制：**SPI 扩展机制的实现**、**服务导出的完整链路**、**Filter 链的构建和执行**，以及**隐式参数**和**回声测试**等高级特性。

<!--more-->

## 一、SPI 扩展机制源码

### 1.1 ExtensionLoader

```java
// ExtensionLoader 是 Dubbo SPI 的核心类
// 类似于 Java SPI 的 ServiceLoader

public class ExtensionLoader<T> {
    
    // 缓存：扩展点实现类
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();
    
    // 缓存：扩展点实例
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = 
            new ConcurrentHashMap<>();
    
    // 获取某个扩展点的实现
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 检查是否为接口
        // 检查是否有 @SPI 注解
        // 从缓存获取或创建 ExtensionLoader
    }
    
    // 获取实现类名称
    private Map<String, Class<?>> getExtensionClasses() {
        // 从 META-INF/services/、META-INF/dubbo/、META-INF/dubbo/internal/ 加载
        // 格式：key=value（如 dubbo=org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol）
    }
}
```

### 1.2 自适应扩展点

```java
// @Adaptive 的作用：根据 URL 参数动态选择实现

// 例如：Protocol 接口
@SPI("dubbo")
public interface Protocol {
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker);
    
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url);
}

// 当调用 protocol.refer() 时：
// 1. 从 URL 中获取 protocol 参数（默认 dubbo）
// 2. 根据参数值获取对应的 Protocol 实现
// 3. 调用该实现的方法

// ExtensionLoader 会在运行时生成自适应代理类
// 类似以下代码：
public class Protocol$Adaptive implements Protocol {
    public Invoker refer(Class type, URL url) {
        String extName = url.getParameter("protocol", "dubbo");
        Protocol extension = ExtensionLoader.getExtensionLoader(Protocol.class)
                .getExtension(extName);
        return extension.refer(type, url);
    }
}
```

---

## 二、服务导出源码

当 Spring 容器启动时，Dubbo 扫描到 `@DubboService` 注解后执行服务导出。

```java
// 服务导出的核心流程

// 1. ServiceBean.afterPropertiesSet() — Spring 初始化回调
//    将 @DubboService 的配置转换为 ServiceConfig

// 2. ServiceConfig.export()
//    检查配置，调用 doExportUrls()

// 3. ServiceConfig.doExportUrls()
//    遍历注册中心列表，为每个注册中心导出服务

// 4. ServiceConfig.doExportUrlsFor1Protocol(protocolConfig, registryURLs)
//    a. 将服务配置转为 URL
//    b. 通过 ProxyFactory 创建 Invoker
//    c. 通过 Protocol.export() 导出

// 5. DubboProtocol.export()
//    a. 启动 Netty Server（如果未启动）
//    b. 将 Invoker 注册到 exporterMap
//    c. 返回 Exporter

// 6. RegistryProtocol.export()
//    将 URL 注册到注册中心（Nacos/ZooKeeper）
```

### 流程图

```
@DubboService
    │
    ▼
ServiceConfig.export()
    │
    ├── ProxyFactory.getInvoker() → 通过 Javassist 生成 Invoker
    │
    ├── Protocol.export() → DubboProtocol
    │     ├── openServer() → 启动 Netty Server（端口复用）
    │     └── exporterMap.put(key, exporter)
    │
    └── RegistryProtocol.export()
          └── registry.register(url) → 向 Nacos 注册
```

---

## 三、服务引用源码

```java
// @DubboReference 注解 — 从远程生成代理对象

// 1. ReferenceBean.afterPropertiesSet()
//    将 @DubboReference 配置转换为 ReferenceConfig

// 2. ReferenceConfig.createProxy(map)
//    a. 从注册中心获取服务 URL
//    b. 通过 Protocol.refer() 创建 Invoker
//    c. 通过 ProxyFactory.getProxy() 创建代理

// 3. 代理对象的方法调用流程
//    代理 → Invoker.invoke() → RPC 通信 → Provider
```

---

## 四、Filter 链

Dubbo 的 Filter 机制类似于 Servlet 的 Filter 链，用于在 RPC 调用的前后插入通用逻辑。

### 4.1 Filter 接口

```java
@SPI
public interface Filter {
    
    Result invoke(Invoker<?> invoker, Invocation invocation);
    
    // 生命周期回调
    default Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        return result;
    }
}
```

### 4.2 内置 Filter

```java
// Consumer 端
@Activate(group = CONSUMER)
public class ConsumerContextFilter implements Filter {
    // 设置调用上下文（RpcContext）
}

@Activate(group = CONSUMER)
public class FutureFilter implements Filter {
    // 异步回调处理
}

// Provider 端
@Activate(group = PROVIDER)
public class ContextFilter implements Filter {
    // 恢复调用上下文
}

@Activate(group = PROVIDER)
public class ExceptionFilter implements Filter {
    // 异常处理
}

// 通用
@Activate(group = {CONSUMER, PROVIDER})
public class TimeoutFilter implements Filter {
    // 超时控制
}
```

### 4.3 Filter 链构建

```java
// ProtocolFilterWrapper 负责构建 Filter 链

public class ProtocolFilterWrapper implements Protocol {
    
    private final Protocol protocol;
    
    public <T> Exporter<T> export(Invoker<T> invoker) {
        if (REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        // 构建过滤器链
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }
    
    private static <T> Invoker<T> buildInvokerChain(Invoker<T> invoker, 
                                                      String key, String group) {
        // 1. 通过 SPI 获取所有 Filter
        // 2. 按 @Activate 的条件和 group 过滤
        // 3. 按 @Activate 的 order 排序
        // 4. 构建 Filter 链（责任链模式）
        
        Invoker<T> last = invoker;
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class)
                .getActivateExtension(invoker.getUrl(), key, group);
        
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter filter = filters.get(i);
            Invoker<T> next = last;
            last = new FilterInvoker<>(filter, next);
        }
        return last;
    }
}
```

### 4.4 自定义 Filter

```java
@Activate(group = {CONSUMER, PROVIDER}, order = 10000)
public class CustomLogFilter implements Filter {
    
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        long start = System.currentTimeMillis();
        
        // 调用前
        System.out.println("调用: " + invocation.getMethodName());
        
        try {
            Result result = invoker.invoke(invocation);
            return result;
        } finally {
            // 调用后
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("耗时: " + elapsed + "ms");
        }
    }
}
```

```xml
<!-- 注册自定义 Filter -->
<dubbo:provider filter="customLog"/>
<dubbo:consumer filter="customLog"/>
<!-- 或者 application.yml -->
dubbo:
  provider:
    filter: customLog
```

---

## 五、隐式参数

### 5.1 传递隐式参数

```java
// Consumer 端设置（通过 RpcContext）
@DubboReference
private UserService userService;

public void call() {
    // 设置隐式参数（只对下一次调用有效）
    RpcContext.getClientAttachment()
            .setAttachment("trace-id", "abc123");
    
    // 远程调用时，Provider 端可以获取这些参数
    userService.getUser(1L);
}

// Provider 端获取
RpcContext.getServerAttachment().getAttachment("trace-id");
```

---

## 六、回声测试

```java
// 不引入服务接口，直接通过 Dubbo 协议测试服务是否可用

// 通过服务名和版本直接调用
EchoService echoService = (EchoService) applicationContext
        .getBean("echoService");
Object status = echoService.$echo("test");
// 返回 test 说明服务正常
```

---

## 七、总结

### 核心源码速查

| 类 | 作用 |
|----|------|
| ExtensionLoader | SPI 核心，管理所有扩展点 |
| ServiceConfig | 服务导出配置 |
| ReferenceConfig | 服务引用配置 |
| ProtocolFilterWrapper | Filter 链构建 |
| DubboProtocol | 协议通信实现 |
| RegistryProtocol | 注册中心协议 |

**上一篇：** [Dubbo（二）：快速入门与配置]({{< relref "post/dubbo-quick-start" >}})

**下一篇：** [Dubbo（四）：集成与部署]({{< relref "post/dubbo-integration-deployment" >}})
