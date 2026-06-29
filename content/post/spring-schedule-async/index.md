---
title: "Spring 定时任务与异步：@Scheduled / @Async 使用与源码"
date: 2018-06-16
draft: false
categories: ["Java"]
tags: ["Spring", "@Scheduled", "@Async", "TaskScheduler", "线程池", "定时任务", "异步"]
toc: true
---

## 前言

Spring 提供了两个非常实用的注解来简化企业级开发中的常见需求：

- **`@Scheduled`** — 定时任务，替代 Timer、Quartz 的简单场景
- **`@Async`** — 异步执行，将方法调用放到独立线程中执行

两者底层都依赖 **TaskExecutor** 和 **TaskScheduler** 抽象，且都通过 AOP 代理机制实现。本文从使用到源码，一次性覆盖这两个注解。

<!--more-->

## 一、开启异步与定时任务

```java
@Configuration
@EnableAsync      // 开启 @Async
@EnableScheduling  // 开启 @Scheduled
public class AppConfig {
    
    // 自定义线程池（可选）
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
    
    // 自定义定时任务线程池（可选）
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.initialize();
        return scheduler;
    }
}
```

**Spring Boot 中**，`@EnableAsync` 和 `@EnableScheduling` 一般不需要手动开启——可以通过配置直接使用：

```properties
# Spring Boot 自动配置了 TaskExecutor 和 TaskScheduler
spring.task.execution.pool.core-size=5
spring.task.execution.pool.max-size=10
spring.task.execution.pool.queue-capacity=100
spring.task.execution.thread-name-prefix=async-

spring.task.scheduling.pool.size=5
spring.task.scheduling.thread-name-prefix=scheduled-
```

---

## 二、@Scheduled 定时任务

### 2.1 基本使用

```java
@Component
public class ScheduledTasks {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    // ★ 固定延迟：上次执行完 → 下次开始（单位毫秒）
    @Scheduled(fixedDelay = 5000)
    public void runWithFixedDelay() {
        log.info("fixedDelay 任务执行");
        Thread.sleep(2000);  // 假设任务执行 2 秒
        // 下次执行在 5+2=7 秒后
    }
    
    // ★ 固定频率：每 5 秒执行一次，不管上次是否完成
    @Scheduled(fixedRate = 5000)
    public void runWithFixedRate() {
        log.info("fixedRate 任务执行");
        // 如果任务执行超过 5 秒，下次执行会重叠（取决于 @Async）
    }
    
    // ★ 初始延迟 + 固定延迟
    @Scheduled(initialDelay = 10000, fixedDelay = 5000)
    public void runWithInitialDelay() {
        log.info("延迟 10 秒后首次执行，之后每 5 秒执行");
    }
    
    // ★ Cron 表达式（最灵活）
    @Scheduled(cron = "0 0 2 * * ?")  // 每天凌晨 2 点执行
    public void runWithCron() {
        log.info("每日凌晨 2 点执行的任务");
    }
    
    // 带时区的 Cron
    @Scheduled(cron = "0 0 9 * * ?", zone = "Asia/Shanghai")
    public void runWithTimezone() {
        log.info("北京时间每天 9 点执行");
    }
}
```

### 2.2 Cron 表达式

```java
// ┌───────── 秒 (0-59)
// │ ┌───────── 分 (0-59)
// │ │ ┌───────── 时 (0-23)
// │ │ │ ┌───────── 日 (1-31)
// │ │ │ │ ┌───────── 月 (1-12)
// │ │ │ │ │ ┌───────── 周 (0-7, 0和7都是周日)
// * * * * * *

@Scheduled(cron = "0 0 2 * * ?")       // 每天 02:00
@Scheduled(cron = "0 0/30 9-17 * * ?") // 9:00-17:00 每 30 分钟
@Scheduled(cron = "0 0 9,18 * * ?")    // 每天 9:00 和 18:00
@Scheduled(cron = "0 0 0 1 * ?")       // 每月 1 日 00:00
@Scheduled(cron = "0 0 9 * * MON-FRI") // 工作日 9:00
```

### 2.3 编程式 TaskScheduler

```java
@Component
public class DynamicScheduler {
    
    @Autowired
    private TaskScheduler taskScheduler;
    
    public void scheduleDynamicTask() {
        // 一次性任务：5 秒后执行
        taskScheduler.schedule(() -> {
            System.out.println("一次性任务执行");
        }, new Date(System.currentTimeMillis() + 5000));
        
        // Cron 触发
        taskScheduler.schedule(() -> {
            System.out.println("Cron 任务执行");
        }, new CronTrigger("0 0/5 * * * ?"));
        
        // 固定延迟
        taskScheduler.scheduleWithFixedDelay(() -> {
            System.out.println("固定延迟任务");
        }, 5000);
    }
}
```

### 2.4 @Scheduled 常见坑

```java
@Component
public class ScheduledPitfalls {
    
    // ❌ 坑 1：@Scheduled 方法不能有返回值（必须 void）
    @Scheduled(fixedRate = 5000)
    public String wrongReturnType() {
        return "error";  // 编译报错
    }
    
    // ❌ 坑 2：@Scheduled 方法不能有参数
    @Scheduled(fixedRate = 5000)
    public void wrongParams(String param) {
        // 不会执行
    }
    
    // ✅ 正确形式
    @Scheduled(fixedRate = 5000)
    public void correct() {
        doSomething();
    }
    
    // ❌ 坑 3：同一个类中 @Scheduled 方法互相调用（自调用问题）
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyTask() {
        // 这里调用 subTask() 是 this.subTask()，不经过代理
        subTask();  // @Scheduled 生效，但 @Async 不会生效
    }
    
    @Async
    public void subTask() {
        // 如果是自调用，@Async 不会异步执行
    }
}
```

---

## 三、@Async 异步执行

### 3.1 基本使用

```java
@Component
public class AsyncService {
    
    // 无返回值
    @Async
    public void sendNotification(Long userId) {
        // 在独立线程中执行，调用者立即返回
        Thread current = Thread.currentThread();
        System.out.println("异步执行线程: " + current.getName());
        // 模拟耗时操作
        Thread.sleep(3000);
    }
    
    // 有返回值（Future）
    @Async
    public Future<String> processOrder(Long orderId) {
        String result = doProcess(orderId);
        return new AsyncResult<>(result);  // 包装返回
    }
    
    // 有返回值（CompletableFuture，推荐）
    @Async
    public CompletableFuture<User> findUser(Long id) {
        User user = userRepository.findById(id);
        return CompletableFuture.completedFuture(user);
    }
}
```

### 3.2 指定线程池

```java
// 方式一：定义多个线程池
@Configuration
@EnableAsync
public class AsyncConfig {
    
    @Bean("emailExecutor")
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }
    
    @Bean("reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setThreadNamePrefix("report-");
        executor.initialize();
        return executor;
    }
}

// 方式二：@Async 指定线程池名称
@Component
public class AsyncService {
    
    @Async("emailExecutor")  // 使用 emailExecutor 线程池
    public void sendEmail() { ... }
    
    @Async("reportExecutor")  // 使用 reportExecutor 线程池
    public void generateReport() { ... }
    
    @Async  // 使用默认线程池
    public void defaultPool() { ... }
}
```

### 3.3 @Async 的异常处理

```java
// 无返回值时，异常默认被吞掉
@Async
public void sendNotification() {
    throw new RuntimeException("异常!");  // 被 AOP 拦截器捕获，不抛出
}

// 如果需要处理异常，自定义 AsyncUncaughtExceptionHandler
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.initialize();
        return executor;
    }
    
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("异步方法异常: {}#{}, 参数: {}", 
                     method.getDeclaringClass().getName(), 
                     method.getName(), params, ex);
        };
    }
}
```

---

## 四、@Scheduled 源码解析

### 4.1 @EnableScheduling 入口

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(SchedulingConfiguration.class)
public @interface EnableScheduling {}

// SchedulingConfiguration 注册了一个 BeanPostProcessor
@Configuration
public class SchedulingConfiguration {
    
    @Bean(name = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
    @Role(BeanDefinitionRole.INFRASTRUCTURE)
    public ScheduledAnnotationBeanPostProcessor scheduledAnnotationProcessor() {
        return new ScheduledAnnotationBeanPostProcessor();
    }
}
```

### 4.2 ScheduledAnnotationBeanPostProcessor

```java
// ScheduledAnnotationBeanPostProcessor.java
public class ScheduledAnnotationBeanPostProcessor 
        implements BeanPostProcessor, DestructionAwareBeanPostProcessor,
                   Ordered, EmbeddedValueResolverAware {
    
    // ★ 扫描 @Scheduled 并注册定时任务
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        
        // 1. 查找类中所有标注了 @Scheduled 的方法
        Map<Method, Set<Scheduled>> annotatedMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<Set<Scheduled>>) method -> {
                    Set<Scheduled> scheduledMethods = AnnotatedElementUtils.getMergedRepeatableAnnotations(
                            method, Scheduled.class, Schedules.class);
                    return (!scheduledMethods.isEmpty() ? scheduledMethods : null);
                });
        
        // 2. 注册每个 @Scheduled 方法
        for (Map.Entry<Method, Set<Scheduled>> entry : annotatedMethods.entrySet()) {
            Method method = entry.getKey();
            for (Scheduled scheduled : entry.getValue()) {
                // ★ 将 @Scheduled 方法注册到 TaskScheduler
                processScheduled(scheduled, method, bean);
            }
        }
        
        return bean;
    }
}
```

### 4.3 注册定时任务

```java
// ScheduledAnnotationBeanPostProcessor.java
protected void processScheduled(Scheduled scheduled, Method method, Object bean) {
    try {
        Runnable runnable = createRunnable(bean, method);
        
        // 1. 获取用于注册的 TaskScheduler（从容器或自定义）
        ScheduledTaskRegistrar registrar = this.registrar;
        
        // 2. 处理 fixedDelay
        long fixedDelay = scheduled.fixedDelay();
        if (fixedDelay >= 0) {
            registrar.addFixedDelayTask(new FixedDelayTask(runnable, fixedDelay, scheduled.initialDelay()));
        }
        
        // 3. 处理 fixedRate
        long fixedRate = scheduled.fixedRate();
        if (fixedRate >= 0) {
            registrar.addFixedRateTask(new FixedRateTask(runnable, fixedRate, scheduled.initialDelay()));
        }
        
        // 4. ★ 处理 Cron 表达式
        String cron = scheduled.cron();
        if (StringUtils.hasText(cron)) {
            String resolvedCron = this.embeddedValueResolver.resolveStringValue(cron);
            registrar.addCronTask(new CronTask(runnable, resolvedCron));
        }
        
    } catch (IllegalArgumentException ex) {
        throw new IllegalStateException("...");
    }
}
```

### 4.4 任务执行

`TaskScheduler` 的实现类 `ConcurrentTaskScheduler` 底层使用 `ScheduledExecutorService`：

```java
// ConcurrentTaskScheduler.java
@Override
public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
    // 委托给 ScheduledExecutorService
    return this.scheduledExecutor.schedule(
            task, 
            trigger.nextExecutionTime(taskContext),
            TimeUnit.MILLISECONDS);
}

// 对于 fixedRate
@Override
public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
    return this.scheduledExecutor.scheduleAtFixedRate(task, period, period, TimeUnit.MILLISECONDS);
}

// 对于 fixedDelay
@Override
public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
    return this.scheduledExecutor.scheduleWithFixedDelay(task, delay, delay, TimeUnit.MILLISECONDS);
}
```

---

## 五、@Async 源码解析

### 5.1 @EnableAsync 入口

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(AsyncConfigurationSelector.class)
public @interface EnableAsync {
    boolean proxyTargetClass() default false;
    AdviceMode mode() default AdviceMode.PROXY;
}

// AsyncConfigurationSelector 导入 ProxyAsyncConfiguration
// → 注册 AsyncAnnotationBeanPostProcessor（即 AsyncExecutionInterceptor）
```

### 5.2 AsyncExecutionInterceptor——AOP 拦截器

`@Async` 通过 AOP 实现。`AsyncExecutionInterceptor` 是一个 `MethodInterceptor`：

```java
// AsyncExecutionInterceptor.java
public class AsyncExecutionInterceptor extends AsyncExecutionAspectSupport
        implements MethodInterceptor {
    
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // 1. 确定目标方法
        Class<?> targetClass = (invocation.getThis() != null 
                ? AopUtils.getTargetClass(invocation.getThis()) : null);
        Method specificMethod = ClassUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        
        // 2. ★ 确定使用的线程池
        AsyncTaskExecutor executor = determineAsyncExecutor(specificMethod);
        
        // 3. 将方法调用包装为 Callable
        Callable<Object> task = () -> {
            try {
                Object result = invocation.proceed();
                if (result instanceof Future) {
                    return ((Future<?>) result).get();
                }
                return result;
            } catch (Throwable ex) {
                // 异常处理
                handleError(ex, method, params);
                throw ex;
            }
        };
        
        // 4. ★ 提交到线程池执行
        return doSubmit(task, executor, invocation.getMethod().getReturnType());
    }
}
```

### 5.3 确定线程池

```java
// AsyncExecutionAspectSupport.java
protected AsyncTaskExecutor determineAsyncExecutor(Method method) {
    // 1. 检查缓存
    AsyncTaskExecutor executor = this.executors.get(method);
    if (executor == null) {
        // 2. 从 @Async 注解获取线程池名称
        String qualifier = getExecutorQualifier(method);
        
        if (StringUtils.hasLength(qualifier)) {
            // @Async("myExecutor") → 从容器中查找指定名称的 Bean
            executor = findOnBeanQualifiers(qualifier);
        }
        
        if (executor == null) {
            // 3. 没有指定名称 → 使用默认线程池
            executor = this.defaultExecutor;
            if (executor == null) {
                executor = getDefaultExecutor(this.beanFactory);
            }
        }
        
        this.executors.put(method, executor);
    }
    return executor;
}

// 获取默认线程池
protected Executor getDefaultExecutor(BeanFactory beanFactory) {
    // 1. 查找容器中唯一的 TaskExecutor Bean
    if (beanFactory != null) {
        try {
            return beanFactory.getBean(TaskExecutor.class);
        } catch (NoUniqueBeanDefinitionException ex) {
            // 有多个 → 尝试查找 "taskExecutor"
            return beanFactory.getBean("taskExecutor", Executor.class);
        }
    }
    return new SimpleAsyncTaskExecutor();  // 兜底
}
```

### 5.4 提交到线程池

```java
// AsyncExecutionAspectSupport.java
protected Object doSubmit(Callable<Object> task, AsyncTaskExecutor executor, Class<?> returnType) {
    
    if (CompletableFuture.class.isAssignableFrom(returnType)) {
        // ★ 返回 CompletableFuture → 提交到线程池，返回 CompletableFuture
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Throwable ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    } else if (Future.class.isAssignableFrom(returnType)) {
        // ★ 返回 Future → 提交到线程池，返回 Future
        return executor.submit(task);
    } else if (void.class.isAssignableFrom(returnType)) {
        // ★ 返回 void → 提交到线程池，不等待结果
        executor.submit(task);
        return null;
    } else {
        // ★ 其他返回类型 → 提交并等待结果
        Future<Object> future = executor.submit(task);
        try {
            return future.get();
        } catch (ExecutionException ex) {
            handleError(ex.getCause(), ...);
        }
    }
}
```

---

## 六、线程池配置详解

### 6.1 ThreadPoolTaskExecutor

```java
@Bean
public ThreadPoolTaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    
    executor.setCorePoolSize(5);          // 核心线程数
    executor.setMaxPoolSize(10);          // 最大线程数
    executor.setKeepAliveSeconds(60);     // 空闲线程存活时间
    executor.setQueueCapacity(100);       // 工作队列容量
    executor.setThreadNamePrefix("my-");  // 线程名前缀
    
    // 拒绝策略
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    // AbortPolicy（默认）→ 抛 RejectedExecutionException
    // CallerRunsPolicy → 调用者线程执行
    // DiscardPolicy → 丢弃不抛异常
    // DiscardOldestPolicy → 丢弃队列中最老的任务
    
    executor.initialize();
    return executor;
}
```

### 6.2 Spring Boot 配置

```properties
# 异步任务线程池
spring.task.execution.pool.core-size=5
spring.task.execution.pool.max-size=10
spring.task.execution.pool.queue-capacity=100
spring.task.execution.pool.keep-alive=60s
spring.task.execution.thread-name-prefix=async-task-

# 定时任务线程池
spring.task.scheduling.pool.size=5
spring.task.scheduling.thread-name-prefix=scheduled-task-
```

### 6.3 合理配置建议

| 场景 | 核心线程 | 最大线程 | 队列 | 拒绝策略 |
|------|---------|---------|------|---------|
| 定时任务 | `pool.size=2~5` | — | — | — |
| 一般异步 | `5~10` | `10~20` | `100~500` | CallerRuns |
| IO 密集型 | `CPU*2` | `CPU*2+1` | 大 | CallerRuns |
| CPU 密集型 | `CPU+1` | `CPU+1` | 小 | Abort |

---

## 七、总结

### @Scheduled 属性速查

| 属性 | 示例 | 说明 |
|------|------|------|
| `fixedRate` | `5000` | 每 5 秒执行一次 |
| `fixedDelay` | `5000` | 上次执行完 5 秒后执行 |
| `initialDelay` | `10000` | 首次延迟 10 秒 |
| `cron` | `"0 0 2 * * ?"` | Cron 表达式 |
| `zone` | `"Asia/Shanghai"` | 时区 |

### @Async 要点

- 方法必须 **public**，**不能自调用**
- **void** 返回类型：异常被 AOP 拦截（需自定义 `AsyncUncaughtExceptionHandler`）
- **Future/CompletableFuture** 返回：可获取结果和异常
- **指定线程池**：`@Async("beanName")`

### 核心源码文件索引

| 类 | 作用 |
|---|------|
| `ScheduledAnnotationBeanPostProcessor` | 扫描 @Scheduled 注册定时任务 |
| `ConcurrentTaskScheduler` | 委托 JDK ScheduledExecutorService 执行 |
| `AsyncExecutionInterceptor` | @Async 的 AOP 拦截器 |
| `AsyncExecutionAspectSupport` | 确定线程池、提交执行 |
| `ThreadPoolTaskExecutor` | Spring 封装的线程池 |
| `ThreadPoolTaskScheduler` | Spring 封装的定时任务线程池 |

---

**相关阅读：**

- [Spring AOP（三）：JDK 代理 vs CGLib 与高级主题]({{< relref "post/spring-aop-proxy" >}})
- [Spring 事件（一）：@EventListener 使用与异步事件]({{< relref "post/spring-event-usage" >}})
