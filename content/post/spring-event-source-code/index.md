---
title: "Spring 事件（二）：事件发布与监听源码深度解析"
date: 2018-06-14
draft: false
categories: ["Java"]
tags: ["Spring", "事件机制", "ApplicationEventMulticaster", "@EventListener", "源码分析", "观察者模式"]
toc: true
---

## 前言

上一篇覆盖了 Spring 事件机制的使用。这篇文章深入源码，追踪**一个 `publishEvent()` 调用，是如何找到所有匹配的监听器并逐个调用的**。

核心思路：Spring 事件机制 = **事件发布器** → **事件广播器（ApplicationEventMulticaster）** → **匹配监听器** → **调用监听器方法**。

<!--more-->

> **源码版本：** Spring Framework 5.x

## 一、事件机制的整体架构

```
ApplicationEventPublisher.publishEvent()
    │
    ▼
AbstractApplicationContext.publishEvent()
    │
    ▼
ApplicationEventMulticaster.multicastEvent()
    │
    ├── 获取所有匹配的 ApplicationListener
    │     ├── 根据事件类型过滤
    │     └── 支持泛型类型匹配
    │
    └── 逐个调用监听器
          ├── 同步：直接调用 onApplicationEvent()
          └── 异步：如果监听器有 @Async，提交线程池
```

**核心接口：**

| 接口/类 | 作用 |
|---------|------|
| `ApplicationEventPublisher` | 发布事件（入口）|
| `ApplicationEventMulticaster` | 事件广播器（核心调度）|
| `ApplicationListener` | 监听器接口 |
| `@EventListener` | 注解式监听器 |
| `ApplicationEvent` | 事件基类 |

## 二、事件的发布

### 2.1 从 publishEvent 开始

当调用 `applicationEventPublisher.publishEvent(event)` 时，最终进入 `AbstractApplicationContext`：

```java
// AbstractApplicationContext.java
@Override
public void publishEvent(Object event) {
    publishEvent(event, null);
}

protected void publishEvent(Object event, ResolvableType eventType) {
    // 1. 将事件转为 ApplicationEvent
    ApplicationEvent applicationEvent;
    if (event instanceof ApplicationEvent) {
        applicationEvent = (ApplicationEvent) event;
    } else {
        // 普通对象 → 包装为 PayloadApplicationEvent
        applicationEvent = new PayloadApplicationEvent<>(this, event);
        if (eventType == null) {
            eventType = ((PayloadApplicationEvent<?>) applicationEvent).getResolvableType();
        }
    }

    // 2. 在发布前可以修改事件（留给子类扩展）
    if (this.earlyApplicationEvents != null) {
        this.earlyApplicationEvents.add(applicationEvent);
    } else {
        // ★ 3. 获取 ApplicationEventMulticaster 并广播事件
        getApplicationEventMulticaster().multicastEvent(applicationEvent, eventType);
    }

    // 4. 对实现了 ApplicationEventPublisherAware 的监听器，
    //    通过父容器也发布一遍（父子容器场景）
    if (this.parent != null) {
        if (this.parent instanceof AbstractApplicationContext) {
            ((AbstractApplicationContext) this.parent).publishEvent(event, eventType);
        } else {
            this.parent.publishEvent(event);
        }
    }
}
```

### 2.2 事件广播器的初始化

`ApplicationEventMulticaster` 在 `refresh()` 阶段初始化：

```java
// AbstractApplicationContext.refresh()
protected void initApplicationEventMulticaster() {
    ConfigurableListableBeanFactory beanFactory = getBeanFactory();
    
    // 1. 如果用户自定义了 ApplicationEventMulticaster，使用自定义的
    if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
        this.applicationEventMulticaster = 
                beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
    } else {
        // 2. 默认使用 SimpleApplicationEventMulticaster
        this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
        beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
    }
}
```

## 三、ApplicationEventMulticaster 的事件广播

### 3.1 SimpleApplicationEventMulticaster

默认的广播器是 `SimpleApplicationEventMulticaster`，它的 `multicastEvent()` 是事件分发的核心：

```java
// SimpleApplicationEventMulticaster.java
public class SimpleApplicationEventMulticaster extends AbstractApplicationEventMulticaster {
    
    private Executor taskExecutor;  // 异步执行器（默认 null = 同步）
    private ErrorHandler errorHandler;
    
    @Override
    public void multicastEvent(ApplicationEvent event, ResolvableType eventType) {
        // 1. 确定事件类型
        ResolvableType type = (eventType != null ? eventType : resolveDefaultEventType(event));
        
        // 2. 获取当前线程的 Executor
        Executor executor = getTaskExecutor();
        
        // 3. ★ 获取所有匹配的监听器
        for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {
            // 4a. 有 Executor → 异步调用
            if (executor != null) {
                executor.execute(() -> invokeListener(listener, event));
            } else {
                // 4b. 无 Executor → 同步调用
                invokeListener(listener, event);
            }
        }
    }
    
    // 设置异步执行器（暴露 setter，可通过配置注入）
    public void setTaskExecutor(Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }
}
```

### 3.2 获取匹配的监听器——getApplicationListeners

这是事件分发的核心——**高效地查找匹配的监听器**：

```java
// AbstractApplicationEventMulticaster.java
protected Collection<ApplicationListener<?>> getApplicationListeners(
        ApplicationEvent event, ResolvableType eventType) {
    
    Object source = event.getSource();
    Class<?> sourceType = (source != null ? source.getClass() : null);
    
    // 1. 构造缓存的 key（事件类型 + 来源类型 + 来源类型）
    ListenerCacheKey cacheKey = new ListenerCacheKey(eventType, sourceType, "");
    
    // 2. 从缓存中查找
    CachedListenerRetriever cachedRetriever = this.retrieverCache.get(cacheKey);
    if (cachedRetriever != null) {
        return cachedRetriever.getApplicationListeners();
    }
    
    // 3. ★ 遍历所有注册的监听器，逐个检查是否匹配
    Collection<ApplicationListener<?>> listeners = retrieveApplicationListeners(eventType, sourceType, null);
    
    // 4. 写入缓存
    if (listeners.size() > 0) {
        this.retrieverCache.put(cacheKey, new CachedListenerRetriever(true, listeners));
    }
    
    return listeners;
}
```

**retrieveApplicationListeners 检查匹配：**

```java
// AbstractApplicationEventMulticaster.java
private Collection<ApplicationListener<?>> retrieveApplicationListeners(
        ResolvableType eventType, Class<?> sourceType, ListenerRetriever retriever) {
    
    List<ApplicationListener<?>> allListeners = new ArrayList<>();
    
    // 1. 获取所有注册的监听器
    Set<ApplicationListener<?>> listeners = new LinkedHashSet<>(this.defaultRetriever.applicationListeners);
    for (ApplicationListener<?> listener : listeners) {
        // 2. ★ 检查监听器是否支持该事件类型
        if (supportsEvent(listener, eventType, sourceType)) {
            if (retriever != null) {
                retriever.applicationListeners.add(listener);
            }
            allListeners.add(listener);
        }
    }
    
    // 3. 排序
    AnnotationAwareOrderComparator.sort(allListeners);
    return allListeners;
}
```

### 3.3 事件类型匹配——supportsEvent

```java
// AbstractApplicationEventMulticaster.java
private boolean supportsEvent(ApplicationListener<?> listener, 
                               ResolvableType eventType, Class<?> sourceType) {
    
    // 1. 如果是 GenericApplicationListener，直接调用 supportsEventType
    if (listener instanceof GenericApplicationListener) {
        return ((GenericApplicationListener) listener).supportsEventType(eventType);
    }
    
    // 2. 如果是 SmartApplicationListener，调用 supportsEventType
    if (listener instanceof SmartApplicationListener) {
        Class<?> eventClass = eventType.resolve();
        return (eventClass != null && ((SmartApplicationListener) listener).supportsEventType(eventClass));
    }
    
    // 3. 如果是标准 ApplicationListener，检查泛型参数
    //    如：class MyListener implements ApplicationListener<OrderCreatedEvent>
    //    → 通过泛型解析判断类型是否匹配
    Class<?> declaredEventType = getDeclaredEventType(listener);
    return (declaredEventType == null || declaredEventType.isAssignableFrom(eventType.resolve()));
}
```

## 四、监听器的注册

### 4.1 两种注册方式

```java
// 方式一：ApplicationListener 接口
@Component
public class MyListener implements ApplicationListener<OrderCreatedEvent> {
    @Override
    public void onApplicationEvent(OrderCreatedEvent event) {
        // 处理事件
    }
}

// 方式二：@EventListener 注解
@Component
public class MyService {
    
    @EventListener
    public void handleEvent(OrderCreatedEvent event) {
        // 处理事件
    }
}
```

### 4.2 接口监听器的注册

所有实现了 `ApplicationListener` 接口的 Bean，在容器启动时通过 `ApplicationListenerDetector` 自动注册：

```java
// ApplicationListenerDetector.java（BeanPostProcessor）
public class ApplicationListenerDetector implements DestructionAwareBeanPostProcessor {
    
    private final AbstractApplicationContext applicationContext;
    
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ApplicationListener) {
            // ★ 将 ApplicationListener Bean 添加到容器的事件多播器中
            this.applicationContext.addApplicationListener((ApplicationListener<?>) bean);
        }
        return bean;
    }
}
```

### 4.3 @EventListener 的注册

`@EventListener` 注解的方法通过 `EventListenerMethodProcessor` 处理，它也是一个 `BeanPostProcessor`：

```java
// EventListenerMethodProcessor.java
public class EventListenerMethodProcessor 
        implements SmartInitializingSingleton, ApplicationContextAware {
    
    @Override
    public void afterSingletonsInstantiated() {
        // 容器启动后，处理所有 Bean 中的 @EventListener 方法
        String[] beanNames = applicationContext.getBeanNamesForType(Object.class);
        
        for (String beanName : beanNames) {
            Class<?> type = applicationContext.getType(beanName);
            if (type != null && !type.isInterface()) {
                // 检查该 Bean 中是否有 @EventListener 方法
                processBean(beanName, type);
            }
        }
    }
    
    private void processBean(String beanName, Class<?> targetType) {
        // 1. 查找类中所有标注了 @EventListener 的方法
        Map<Method, EventListener> annotatedMethods = 
                MethodIntrospector.selectMethods(targetType, 
                        (MethodIntrospector.MetadataLookup<EventListener>) method -> 
                                AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
        
        if (annotatedMethods.isEmpty()) return;
        
        // 2. 每个 @EventListener 方法 → 创建一个 ApplicationListenerMethodAdapter
        for (Method method : annotatedMethods.keySet()) {
            // ★ 将注解方法包装为 ApplicationListener
            ApplicationListener<?> listener = createApplicationListener(beanName, targetType, method);
            
            // 3. 注册到容器
            context.addApplicationListener(listener);
        }
    }
}
```

### 4.4 ApplicationListenerMethodAdapter

`@EventListener` 方法被包装为 `ApplicationListenerMethodAdapter`，它实现了 `GenericApplicationListener`：

```java
// ApplicationListenerMethodAdapter.java
public class ApplicationListenerMethodAdapter implements GenericApplicationListener {
    
    private final String beanName;        // Bean 名
    private final Method method;          // @EventListener 方法
    private final Class<?> targetClass;   // 目标类
    private final EventListener annotation; // @EventListener 注解
    
    // 从 @EventListener 中解析的条件（condition）
    private final ConditionEvaluation condition;
    
    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        // 检查方法参数类型是否匹配事件类型
        // 如：handleEvent(OrderCreatedEvent event) → 只匹配 OrderCreatedEvent
        return method.getParameterTypes()[0].isAssignableFrom(eventType.resolve());
    }
    
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // 1. 从事件中提取载荷（如果是 PayloadApplicationEvent）
        Object actualEvent = event;
        if (event instanceof PayloadApplicationEvent) {
            actualEvent = ((PayloadApplicationEvent<?>) event).getPayload();
        }
        
        // 2. 检查 SpEL condition
        if (condition != null && !condition.evaluate(actualEvent)) {
            return;  // 条件不满足，跳过
        }
        
        // 3. 执行 @EventListener 方法
        Object bean = applicationContext.getBean(beanName);
        method.invoke(bean, actualEvent);
    }
}
```

## 五、@TransactionalEventListener 的事件绑定

`@TransactionalEventListener` 在 `ApplicationListenerMethodAdapter` 的基础上增加了事务绑定：

```java
// TransactionalApplicationListenerMethodAdapter.java
public class TransactionalApplicationListenerMethodAdapter 
        extends ApplicationListenerMethodAdapter {
    
    private final TransactionPhase transactionPhase;  // 绑定的事务阶段
    
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // ★ 不在事务中直接执行监听器
        // 而是注册一个 TransactionSynchronization
        // 在指定的事务阶段回调
        
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 当前有事务 → 注册事务同步回调
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionalEventSynchronization<>(event, this));
        } else {
            if (this.annotation.fallbackExecution()) {
                // 没有事务但设置了 fallbackExecution → 直接执行
                processEvent(event);
            }
            // 没有事务且没有 fallbackExecution → 丢弃
        }
    }
    
    // 事务同步器
    private class TransactionalEventSynchronization implements TransactionSynchronization {
        
        private final ApplicationEvent event;
        private final TransactionalApplicationListenerMethodAdapter listener;
        
        @Override
        public void afterCommit() {
            // ★ 事务提交后执行
            listener.processEvent(event);
        }
        
        @Override
        public void afterCompletion(int status) {
            // 根据 status 判断是提交还是回滚
            if (status == STATUS_ROLLED_BACK 
                    && listener.transactionPhase == TransactionPhase.AFTER_ROLLBACK) {
                listener.processEvent(event);
            }
        }
    }
}
```

## 六、异步事件的实现

当 `@EventListener` 方法同时标注了 `@Async` 时，Spring 的异步事件通过 `SimpleApplicationEventMulticaster` 的 `taskExecutor` 实现。

```java
// 实际上 @Async 并不在事件机制中直接处理
// 而是通过 AOP 代理 @EventListener 方法本身
// 当调用 onApplicationEvent() 时，@Async 的 AOP 拦截器拦截调用，
// 提交到线程池执行

// 另一种方式：直接给 SimpleApplicationEventMulticaster 设置 Executor
@Configuration
public class EventConfig {
    
    @Bean
    public ApplicationEventMulticaster applicationEventMulticaster() {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        // ★ 设置异步执行器 → 所有事件都异步广播
        multicaster.setTaskExecutor(Executors.newFixedThreadPool(5));
        return multicaster;
    }
}
```

## 七、完整链路总结

```
publishEvent(new OrderCreatedEvent())
    │
    ▼
AbstractApplicationContext.publishEvent()
    │
    ├── 包装为 ApplicationEvent（如果不是）
    │
    ▼
SimpleApplicationEventMulticaster.multicastEvent()
    │
    ├── 从缓存查找匹配的监听器
    │     ├── 遍历所有注册的 ApplicationListener
    │     ├── supportsEventType() → 检查泛型类型匹配
    │     └── supportsSourceType() → 检查事件来源
    │
    ├── 排序（@Order）
    │
    ├── 同步/异步分发
    │     ├── 同步：new Listener().onApplicationEvent(event)
    │     └── 异步：executor.execute(() → listener.onApplicationEvent(event))
    │
    ▼
监听器执行
    ├── ApplicationListener 接口 → onApplicationEvent()
    └── @EventListener 方法 → ApplicationListenerMethodAdapter
          ├── 检查 condition（SpEL）
          ├── 如果是 @TransactionalEventListener → 注册事务同步
          └── 执行目标方法
```

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `AbstractApplicationContext` | `publishEvent()` 入口 |
| `SimpleApplicationEventMulticaster` | 事件广播核心，支持同步/异步 |
| `AbstractApplicationEventMulticaster` | 监听器匹配、缓存 |
| `ApplicationListenerDetector` | 自动注册 ApplicationListener Bean |
| `EventListenerMethodProcessor` | 处理 @EventListener 注解 |
| `ApplicationListenerMethodAdapter` | 将 @EventListener 方法适配为 ApplicationListener |
| `TransactionalApplicationListenerMethodAdapter` | 事务绑定的事件监听器 |
| `TransactionSynchronization` | 事务回调接口 |

### 设计模式

| 模式 | 体现 |
|------|------|
| 观察者模式 | 发布者 → 广播器 → 监听者 |
| 适配器模式 | `@EventListener` 方法 → ApplicationListener 适配 |
| 模板方法 | ApplicationEventMulticaster 的广播流程 |

---

**上一篇：** [Spring 事件（一）：@EventListener 使用与异步事件]({{< relref "post/spring-event-usage" >}})
