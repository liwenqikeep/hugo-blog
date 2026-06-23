---
title: "Java 多线程（五）：线程池深入"
date: 2017-12-20
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程", "线程池", "Executor"]
---

## 前言

线程的创建和销毁是有开销的。在高并发场景下，频繁创建销毁线程会严重影响性能。线程池正是为了解决这一问题而生，它是实际项目中最常用的并发组件。

## 一、线程池核心原理

### 1.1 为什么要用线程池

**直接创建线程的问题**：
- 线程创建有开销（分配栈空间、初始化）
- 无限制创建线程会导致 OOM
- 难以管理线程生命周期

**线程池的优势**：
- 重用已有线程，减少创建开销
- 控制并发数量，保护系统资源
- 统一管理线程生命周期

### 1.2 线程池工作流程

```
                    ┌─────────────────────┐
                    │  提交任务 (submit)  │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼──────────┐
                    │  是否 < corePoolSize  │
                    └──────────┬──────────┘
                         是 ↙    ↘ 否
                    ┌───────────┐   ┌──────────────────┐
                    │ 创建新线程 │   │ 队列是否已满？    │
                    └─────┬─────┘   └────────┬─────────┘
                          │              是 ↙    ↘ 否
                    ┌─────▼─────┐      ┌───────────┐   ┌────────────────┐
                    │ 执行任务  │      │ 创建新线程 │   │ 执行拒绝策略   │
                    └─────┬─────┘      └─────┬─────┘   └────────────────┘
                          │                  │
                          └───────┬──────────┘
                                  │
                          ┌───────▼───────┐
                          │  线程复用执行  │
                          │  更多任务      │
                          └───────────────┘
```

### 1.3 ThreadPoolExecutor 构造方法

```java
public ThreadPoolExecutor(
    int corePoolSize,              // 核心线程数
    int maximumPoolSize,           // 最大线程数
    long keepAliveTime,            // 空闲线程存活时间
    TimeUnit unit,                 // 时间单位
    BlockingQueue<Runnable> workQueue,  // 任务队列
    ThreadFactory threadFactory,   // 线程工厂
    RejectedExecutionHandler handler   // 拒绝策略
)
```

## 二、核心参数详解

### 2.1 corePoolSize（核心线程数）

**核心线程的特点**：
- 即使空闲也不会被回收（除非设置 allowCoreThreadTimeOut）
- 用于处理日常任务

**如何设置**：
- CPU 密集型任务：CPU 核心数 + 1
- IO 密集型任务：CPU 核心数 × 2（或根据 IO 等待比例调整）
- 混合型：根据实际情况压测调整

```java
// 获取 CPU 核心数
int cpuCores = Runtime.getRuntime().availableProcessors();

// CPU 密集型
int corePoolSize = cpuCores + 1;

// IO 密集型（假设 IO 等待时间占比 80%）
int corePoolSize = cpuCores * 2;
```

### 2.2 maximumPoolSize（最大线程数）

**与核心线程数的区别**：
- 核心线程：常驻线程
- 最大线程：紧急情况下的扩展能力

**设置建议**：
- 不宜过大，否则线程切换开销大
- 通常设置为 corePoolSize 的 1.5~2 倍

### 2.3 keepAliveTime（空闲线程存活时间）

**作用**：非核心线程空闲后的存活时间

```java
// 示例：核心线程永不过期，非核心线程 60 秒后回收
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    10,                    // 核心 10 个
    20,                    // 最大 20 个
    60L,                   // 60 秒
    TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100)
);

// 设置核心线程也会过期
executor.allowCoreThreadTimeOut(true);
```

### 2.4 workQueue（任务队列）

| 队列类型 | 说明 | 特点 |
|----------|------|------|
| LinkedBlockingQueue | 无界队列 | 永不拒绝任务，可能 OOM |
| ArrayBlockingQueue | 有界队列 | 固定大小，需设置合理容量 |
| SynchronousQueue | 同步队列 | 不存储任务，直接交付 |
| PriorityBlockingQueue | 优先级队列 | 按优先级执行 |

**队列选择原则**：
```java
// 场景 1：任务量可控，使用有界队列
new ArrayBlockingQueue<>(1000)

// 场景 2：需要快速响应，使用 SynchronousQueue
// 直接创建线程，不排队
new SynchronousQueue<>()

// 场景 3：任务重要不允许丢弃，使用无界队列（谨慎！）
new LinkedBlockingQueue<>()
```

## 三、拒绝策略

### 3.1 内置拒绝策略

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| AbortPolicy | 抛出 RejectedExecutionException | 默认策略，需显式处理 |
| CallerRunsPolicy | 由提交任务的线程执行 | 压力分散到调用方 |
| DiscardPolicy | 直接丢弃任务 | 允许任务丢失 |
| DiscardOldestPolicy | 丢弃队列最旧的任务 | 优先处理新任务 |

### 3.2 实战：自定义拒绝策略

```java
public class CustomRejectedPolicy implements RejectedExecutionHandler {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ConcurrentHashMap<String, AtomicInteger> rejectedCounter;
    
    public CustomRejectedPolicy() {
        this.rejectedCounter = new ConcurrentHashMap<>();
    }
    
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        String taskName = r.getClass().getSimpleName();
        
        // 记录被拒绝的任务
        rejectedCounter.computeIfAbsent(taskName, k -> new AtomicInteger())
                      .incrementAndGet();
        
        // 记录日志
        log.warn("任务被拒绝: {}, 队列状态: active={}, queue={}, completed={}",
            taskName,
            executor.getActiveCount(),
            executor.getQueue().size(),
            executor.getCompletedTaskCount());
        
        // 策略 1：降级处理
        if (r instanceof Callable) {
            try {
                // 同步执行
                ((Callable<?>) r).call();
                return;
            } catch (Exception e) {
                log.error("降级执行失败", e);
            }
        }
        
        // 策略 2：抛出异常（默认行为）
        throw new RejectedExecutionException("Task " + r + " rejected");
    }
    
    public Map<String, Integer> getRejectedStats() {
        Map<String, Integer> stats = new HashMap<>();
        rejectedCounter.forEach((k, v) -> stats.put(k, v.get()));
        return stats;
    }
}
```

## 四、常见线程池对比

### 4.1 Executors 提供的四种线程池

```java
// 1. 固定线程数线程池
ExecutorService fixed = Executors.newFixedThreadPool(10);
// 特点：核心线程数=最大线程数，无界队列

// 2. 单线程线程池
ExecutorService single = Executors.newSingleThreadExecutor();
// 特点：保证顺序执行，适合任务串行化

// 3. 缓存线程池
ExecutorService cached = Executors.newCachedThreadPool();
// 特点：按需创建，最大线程数 Integer.MAX_VALUE，有 OOM 风险！

// 4. 定时线程池
ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(5);
scheduled.scheduleAtFixedRate(task, 1, 10, TimeUnit.SECONDS);
```

### 4.2 为什么不推荐直接使用 Executors

```java
// 问题 1：FixedThreadPool 和 SingleThreadPool 使用无界队列
ExecutorService fixed = Executors.newFixedThreadPool(10);
// 如果任务持续增加，队列会无限增长，最终 OOM

// 问题 2：CachedThreadPool 最大线程数无限制
ExecutorService cached = Executors.newCachedThreadPool();
// 如果提交大量短任务，会创建大量线程，最终 OOM
```

### 4.3 推荐的线程池创建方式

```java
public class ThreadPoolBuilder {
    
    // IO 密集型线程池
    public static ExecutorService ioIntensivePool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores * 2,
            cores * 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),  // 设置有界队列
            new CustomThreadFactory("io-pool"),
            new CustomRejectedPolicy()
        );
    }
    
    // CPU 密集型线程池
    public static ExecutorService cpuIntensivePool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores,
            cores + 1,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            new CustomThreadFactory("cpu-pool"),
            new CustomRejectedPolicy()
        );
    }
    
    // 混合型线程池
    public static ExecutorService mixedPool() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores,
            cores * 2,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            new CustomThreadFactory("mixed-pool"),
            new CustomRejectedPolicy()
        );
    }
}
```

## 五、实战：线程池配置调优

### 5.1 如何确定线程池参数

**压测方法**：
```java
public class ThreadPoolTuner {
    
    public static void tune(ExecutorService executor, 
                           Runnable task, 
                           int totalTasks) {
        
        long start = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(totalTasks);
        
        for (int i = 0; i < totalTasks; i++) {
            executor.submit(() -> {
                task.run();
                latch.countDown();
            });
        }
        
        try {
            latch.await();
            long duration = System.currentTimeMillis() - start;
            
            System.out.println("总任务: " + totalTasks);
            System.out.println("耗时: " + duration + "ms");
            System.out.println("QPS: " + (totalTasks * 1000.0 / duration));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

### 5.2 分层线程池设计

```java
public class分层线程池设计 {
    
    private final ExecutorService ioPool;
    private final ExecutorService cpuPool;
    private final ScheduledExecutorService schedulePool;
    
    public 分层线程池设计() {
        int cores = Runtime.getRuntime().availableProcessors();
        
        // IO 密集型任务池
        this.ioPool = new ThreadPoolExecutor(
            cores * 2, cores * 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactoryBuilder().setNameFormat("io-pool-%d").build(),
            new AbortPolicy()
        );
        
        // CPU 密集型任务池
        this.cpuPool = new ThreadPoolExecutor(
            cores, cores + 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            new ThreadFactoryBuilder().setNameFormat("cpu-pool-%d").build(),
            new AbortPolicy()
        );
        
        // 定时任务池
        this.schedulePool = Executors.newScheduledThreadPool(2,
            new ThreadFactoryBuilder().setNameFormat("schedule-pool-%d").build()
        );
    }
    
    // 根据任务类型提交到不同线程池
    public void submit(TaskType type, Runnable task) {
        switch (type) {
            case IO:
                ioPool.submit(task);
                break;
            case CPU:
                cpuPool.submit(task);
                break;
        }
    }
    
    public <T> Future<T> submitIoTask(Callable<T> task) {
        return ioPool.submit(task);
    }
    
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, 
                                                  long initialDelay,
                                                  long period) {
        return schedulePool.scheduleAtFixedRate(task, initialDelay, period,
            TimeUnit.SECONDS);
    }
}
```

### 5.3 监控线程池状态

```java
public class ThreadPoolMonitor {
    
    private final ThreadPoolExecutor executor;
    private final String name;
    
    public ThreadPoolMonitor(ThreadPoolExecutor executor, String name) {
        this.executor = executor;
        this.name = name;
    }
    
    public void printStatus() {
        System.out.println("=== " + name + " ===");
        System.out.println("核心线程数: " + executor.getCorePoolSize());
        System.out.println("最大线程数: " + executor.getMaximumPoolSize());
        System.out.println("活跃线程数: " + executor.getActiveCount());
        System.out.println("已完成任务: " + executor.getCompletedTaskCount());
        System.out.println("队列大小: " + executor.getQueue().size());
        System.out.println("总提交任务: " + executor.getTaskCount());
    }
    
    // 定时输出监控信息
    public ScheduledFuture<?> startMonitor(long interval) {
        return Executors.newSingleThreadScheduledExecutor()
            .scheduleAtFixedRate(this::printStatus, 0, interval, TimeUnit.SECONDS);
    }
}
```

## 六、线程池的正确关闭

### 6.1 关闭方法对比

| 方法 | 说明 | 效果 |
|------|------|------|
| shutdown() | 平滑关闭 | 不再接受新任务，等待已提交任务完成 |
| shutdownNow() | 强制关闭 | 尝试停止所有任务，返回等待执行的任务列表 |
| awaitTermination() | 等待终止 | 阻塞直到所有任务完成或超时 |

### 6.2 正确关闭模式

```java
public class GracefulShutdown {
    
    public void shutdown(ExecutorService executor) {
        // 1. 拒绝新任务
        executor.shutdown();
        
        try {
            // 2. 等待已有任务完成，最多等 60 秒
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                // 3. 超时后强制关闭
                executor.shutdownNow();
                
                // 4. 再等一下
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    System.err.println("线程池未能正常关闭");
                }
            }
        } catch (InterruptedException e) {
            // 5. 被中断时强制关闭
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

### 6.3 Spring 中的线程池配置

```java
@Configuration
public class ThreadPoolConfig {
    
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
```

## 七、实战：异步任务处理系统

```java
public class AsyncTaskSystem {
    
    private final Map<String, ExecutorService> pools = new ConcurrentHashMap<>();
    
    public void init() {
        pools.put("email", createPool(5, 10));
        pools.put("sms", createPool(3, 6));
        pools.put("push", createPool(10, 20));
    }
    
    private ExecutorService createPool(int core, int max) {
        return new ThreadPoolExecutor(
            core, max, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactoryBuilder()
                .setNameFormat("async-" + core + "-%d")
                .build(),
            (r, e) -> {
                // 自定义拒绝策略：写入日志
                System.err.println("任务被拒绝: " + r);
            }
        );
    }
    
    public <T> CompletableFuture<T> submitAsync(String poolName, 
                                                  Supplier<T> supplier) {
        ExecutorService pool = pools.get(poolName);
        if (pool == null) {
            throw new IllegalArgumentException("Unknown pool: " + poolName);
        }
        
        return CompletableFuture.supplyAsync(supplier, pool);
    }
    
    public void shutdown() {
        pools.values().forEach(this::shutdownPool);
    }
    
    private void shutdownPool(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }
    }
}
```

## 总结

本文深入讲解了线程池的配置与调优：

1. **核心参数**：corePoolSize、maximumPoolSize、keepAliveTime、queue
2. **队列选择**：根据任务特性选择有界/无界/同步队列
3. **拒绝策略**：内置策略 + 自定义策略处理拒绝任务
4. **配置调优**：通过压测找到最佳线程数
5. **监控关闭**：监控线程池状态，平滑关闭线程池

**实际项目建议**：
- 避免使用 Executors 创建线程池，使用 ThreadPoolExecutor
- 根据任务类型（CPU/IO）配置不同线程池
- 设置有界队列，防止 OOM
- 实现线程池监控，及时发现问题
- 确保应用关闭时正确关闭线程池

## 下篇预告

下一篇文章是本系列最后一篇，我们将讲解异步编程与高级主题，包括 CompletableFuture 异步编排、Fork/Join 框架，以及 ThreadLocal 内存泄漏问题。

---

**相关阅读**：
- [Java 多线程（一）：线程创建与生命周期]({{< relref "post/java-multithreading-01" >}})
- [Java 多线程（二）：线程同步机制]({{< relref "post/java-multithreading-02" >}})
- [Java 多线程（三）：线程间通信与协作]({{< relref "post/java-multithreading-03" >}})
- [Java 多线程（四）：JMM 与并发基础]({{< relref "post/java-multithreading-04" >}})
