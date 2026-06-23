---
title: "Java 多线程（四）：JMM 与并发基础"
date: 2017-12-18
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程", "JMM", "volatile", "CAS"]
---

## 前言

理解 Java 内存模型（JMM）是深入掌握多线程编程的关键。只有了解底层原理，才能在遇到并发问题时准确判断原因并选择正确的解决方案。

## 一、Java 内存模型（JMM）

### 1.1 为什么需要 JMM

现代计算机的内存结构：

```
CPU Cache (L1/L2/L3) ←→  主内存  ←→  磁盘
```

多线程程序面临的问题：
- **可见性**：一个线程修改了共享变量，其他线程可能看不到
- **有序性**：编译器/CPU 可能对指令进行重排序
- **原子性**：某些操作不是原子的

### 1.2 JMM 的抽象结构

JMM 将内存划分为主内存和工作内存：

```
线程 A                    线程 B
   │                         │
   ▼                         ▼
┌─────────┐              ┌─────────┐
│工作内存A│              │工作内存B│
└────┬────┘              └────┬────┘
     │    ↕ read/write      │
     │    ↕  主内存          │
     ▼                       ▼
   ┌─────────────────────────────────┐
   │            主内存                │
   └─────────────────────────────────┘
```

**线程的工作内存**是 CPU 寄存器和缓存的抽象，线程对变量的操作都在工作内存中进行，然后同步回主内存。

### 1.3 JMM 的三大特性

| 特性 | 说明 | 解决问题 |
|------|------|----------|
| 原子性 | 基本数据类型读写是原子的 | 基础操作的线程安全 |
| 可见性 | 线程对变量的修改对其他线程可见 | 缓存一致性问题 |
| 有序性 | 指令按程序顺序执行 | 重排序导致的乱序 |

## 二、volatile 关键字

### 2.1 volatile 的作用

volatile 是轻量级的同步机制，确保两点：

1. **保证可见性**：写操作立即刷新到主内存，读操作直接从主内存读取
2. **禁止重排序**：在 volatile 写操作前后的指令不能重排序

```java
public class VolatileDemo {
    private volatile boolean flag = false;
    
    // 线程 A
    public void writer() {
        flag = true;  // volatile 写
    }
    
    // 线程 B
    public void reader() {
        while (!flag) {  // volatile 读
            // 等待
        }
        System.out.println("flag is true");
    }
}
```

### 2.2 volatile 的内存语义

**volatile 写的内存语义**：
```
线程本地内存 → 刷新到主内存 → 其他线程本地内存失效
```

**volatile 读的内存语义**：
```
从主内存读取 → 更新线程本地内存
```

### 2.3 volatile 的使用场景

**适合使用 volatile**：
- 状态标志（如 isRunning）
- 双重检查锁定（Double-Checked Locking）
- 一次性安全发布

**不适合使用 volatile**：
- 需要原子操作的复合操作（如 `i++`）
- 依赖当前值的操作

```java
// 适合 volatile 的场景
private volatile boolean shutdown;

public void shutdown() {
    shutdown = true;
}

public void doWork() {
    while (!shutdown) {
        // 处理任务
    }
}

// 不适合 volatile 的场景
private volatile int count = 0;

public void increment() {
    count++;  // ❌ 不是原子操作！需要 synchronized 或 AtomicInteger
}
```

### 2.4 实战：单例模式的正确实现

```java
public class Singleton {
    private static volatile Singleton instance;
    
    // 双重检查锁定
    public static Singleton getInstance() {
        if (instance == null) {  // 第一次检查
            synchronized (Singleton.class) {
                if (instance == null) {  // 第二次检查
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**为什么需要 volatile？**

`instance = new Singleton()` 实际上有三个步骤：
1. 分配内存
2. 调用构造函数
3. 将引用赋值给 instance

如果没有 volatile，步骤 2 和 3 可能重排序，导致其他线程获取到未完全初始化的对象。

## 三、CAS 与原子操作

### 3.1 CAS 原理

CAS（Compare-And-Swap）是一种乐观锁机制：

```
CAS(var, expected, newValue):
    if var == expected:
        var = newValue
        return true
    else:
        return false
```

在硬件层面，CAS 由 CPU 提供原子指令支持。

### 3.2 CAS 的问题

**ABA 问题**：
```
线程 A: 读取 A → 线程 B: 改为 B → 线程 B: 改回 A
线程 A: CAS 成功，但 A 已经不是原来的 A 了
```

**解决方案**：使用版本号（AtomicStampedReference）

```java
public class ABAProblem {
    public static void main(String[] args) {
        AtomicStampedReference<Integer> ref = 
            new AtomicStampedReference<>(1, 0);
        
        int stamp = ref.getStamp();
        ref.compareAndSet(1, 2, stamp, stamp + 1);
        ref.compareAndSet(2, 1, ref.getStamp(), stamp + 1);
        
        // 即使值相同，版本号不同，CAS 失败
        boolean result = ref.compareAndSet(1, 3, stamp, stamp + 1);
        System.out.println("CAS 结果: " + result);  // false
    }
}
```

**自旋开销**：在高竞争场景下，CAS 失败会导致自旋，影响性能。

### 3.3 atomic 包常用类

```java
public class AtomicDemo {
    public static void main(String[] args) {
        // 原子整数
        AtomicInteger atomicInt = new AtomicInteger(0);
        atomicInt.incrementAndGet();  // ++i
        atomicInt.getAndIncrement();  // i++
        atomicInt.addAndGet(5);       // i += 5
        
        // 原子长整数
        AtomicLong atomicLong = new AtomicLong(0);
        
        // 原子布尔
        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        atomicBoolean.compareAndSet(false, true);
        
        // 原子引用
        AtomicReference<User> userRef = new AtomicReference<>();
        userRef.compareAndSet(null, new User("Alice"));
    }
}
```

### 3.4 实战：高性能计数器

```java
public class PerformanceCounter {
    // 使用 LongAdder 替代 AtomicLong，高并发下性能更好
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAccumulator maxLatency = 
        LongAccumulator.max((x, y) -> x > y ? x : y, 0);
    
    private final AtomicLong totalLatency = new AtomicLong(0);
    
    public void recordSuccess(long latencyMs) {
        successCount.increment();
        totalLatency.addAndGet(latencyMs);
        maxLatency.accumulate(latencyMs);
    }
    
    public void recordFailure() {
        failureCount.increment();
    }
    
    public void printStats() {
        long success = successCount.sum();
        long failure = failureCount.sum();
        long total = success + failure;
        
        System.out.println("总请求: " + total);
        System.out.println("成功: " + success + ", 失败: " + failure);
        System.out.println("成功率: " + (total > 0 ? success * 100.0 / total : 0) + "%");
        System.out.println("最大延迟: " + maxLatency.get() + "ms");
        System.out.println("平均延迟: " + (success > 0 ? totalLatency.get() / success : 0) + "ms");
    }
}
```

**LongAdder vs AtomicLong**：
- AtomicLong：CAS 实现，高竞争时有大量自旋
- LongAdder：分段 CAS，最后求和，降低竞争

## 四、线程安全的并发集合

### 4.1 集合分类

| 分类 | 并发安全 | 有序 | 适用场景 |
|------|----------|------|----------|
| ConcurrentHashMap | ✅ | 无序 | 高并发读写的 Key-Value 缓存 |
| ConcurrentLinkedQueue | ✅ | FIFO | 高性能队列 |
| ConcurrentLinkedDeque | ✅ | FILO | 高性能双端队列 |
| CopyOnWriteArrayList | ✅ | 有序 | 读多写少的列表 |
| CopyOnWriteArraySet | ✅ | 无序 | 读多写少的集合 |

### 4.2 ConcurrentHashMap 详解

**分段锁 vs JDK 8 的 CAS + synchronized**

JDK 7 使用分段锁，JDK 8 改用 CAS + synchronized：

```java
// JDK 8 的 ConcurrentHashMap 关键机制
// 1. Node 使用 volatile 保证可见性
// 2. 使用 CAS 进行初始化
// 3. 链表过长时用 synchronized 转换为红黑树
```

**实战：缓存服务的实现**

```java
public class CacheService<K, V> {
    private final ConcurrentHashMap<K, V> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, Long> expireMap = new ConcurrentHashMap<>();
    
    // 放入缓存
    public void put(K key, V value, long expireSeconds) {
        cache.put(key, value);
        expireMap.put(key, System.currentTimeMillis() + expireSeconds * 1000);
    }
    
    // 获取缓存
    public V get(K key) {
        Long expireTime = expireMap.get(key);
        if (expireTime == null) {
            return null;
        }
        
        if (System.currentTimeMillis() > expireTime) {
            // 已过期，异步清理
            expireMap.remove(key);
            cache.remove(key);
            return null;
        }
        
        return cache.get(key);
    }
    
    // computeIfAbsent 的妙用
    public V getOrCompute(K key, Function<K, V> computer) {
        // 原子操作：避免缓存击穿
        return cache.computeIfAbsent(key, k -> {
            V value = computer.apply(k);
            return value;
        });
    }
}
```

### 4.3 CopyOnWriteArrayList

**原理**：写操作时复制整个数组，读取不需要加锁。

```java
public class CopyOnWriteDemo {
    private final CopyOnWriteArrayList<String> list = 
        new CopyOnWriteArrayList<>();
    
    // 读操作 - 无锁
    public void read() {
        for (String item : list) {
            System.out.println(item);
        }
    }
    
    // 写操作 - 加锁复制
    public void write(String item) {
        list.add(item);  // 内部已加锁
    }
}
```

**适用场景**：
- 配置信息读取
- 黑白名单
- 监听器列表

**注意**：不适合写操作频繁的场景，会导致频繁复制。

### 4.4 实战：批量数据处理

```java
public class BatchDataProcessor {
    private final ConcurrentLinkedQueue<Record> queue = 
        new ConcurrentLinkedQueue<>();
    private final int batchSize;
    private final int threadCount;
    private final ExecutorService executor;
    
    public BatchDataProcessor(int batchSize, int threadCount) {
        this.batchSize = batchSize;
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount);
    }
    
    public void submit(Record record) {
        queue.offer(record);
    }
    
    public void startProcessing(Consumer<List<Record>> batchHandler) {
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                List<Record> batch = new ArrayList<>(batchSize);
                
                while (true) {
                    batch.clear();
                    
                    // 批量取出
                    Record record;
                    while (batch.size() < batchSize && (record = queue.poll()) != null) {
                        batch.add(record);
                    }
                    
                    if (batch.isEmpty()) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    
                    // 处理批次
                    try {
                        batchHandler.accept(new ArrayList<>(batch));
                    } catch (Exception e) {
                        // 处理失败，记录日志
                        handleFailure(batch);
                    }
                }
            });
        }
    }
    
    private void handleFailure(List<Record> batch) {
        // 记录或重试
    }
    
    public void shutdown() {
        executor.shutdown();
    }
}
```

## 五、happens-before 规则

理解 happens-before 是理解 JMM 的核心。

### 5.1 什么是 happens-before

如果操作 A happens-before 操作 B，那么：
- A 的结果对 B 可见
- A 的执行顺序在 B 之前

### 5.2 JDK 定义的 happens-before 规则

```java
// 1. 程序顺序规则
// 同一线程中，书写在前的操作 happens-before 书写在后的操作
int a = 1;      // A
int b = 2;      // B
// A → B

// 2. 监视器锁规则
synchronized (lock) {
    x = 10;     // A：获取锁前的操作
}               // B：释放锁
// A → B

// 3. volatile 规则
volatile boolean flag = false;  // A
// A happens-before B
while (flag) { }               // B

// 4. 线程启动规则
Thread.start()  // A
thread.run();   // B
// A → B

// 5. 线程终止规则
thread.join();  // A
// B happens-before A
```

## 总结

本文深入讲解了 Java 内存模型与并发基础：

1. **JMM**：主内存与工作内存的抽象，确保可见性、有序性、原子性
2. **volatile**：轻量级同步，保证可见性和禁止重排序
3. **CAS**：乐观锁机制，atomic 包的基础
4. **并发集合**：ConcurrentHashMap、CopyOnWriteArrayList 等

**实际项目建议**：
- volatile 适用于状态标志和一次性发布
- CAS 适用于计数器等低竞争场景，高竞争用 LongAdder
- 优先使用并发集合而非自行加锁包装
- 理解 happens-before 规则有助于排查并发问题

## 下篇预告

下一篇文章我们将深入讲解线程池的原理与配置，包括 ThreadPoolExecutor 核心参数、队列选择、拒绝策略，以及实际项目中的线程池调优经验。

---

**相关阅读**：
- [Java 多线程（一）：线程创建与生命周期]({{< relref "post/java-multithreading-01" >}})
- [Java 多线程（二）：线程同步机制]({{< relref "post/java-multithreading-02" >}})
- [Java 多线程（三）：线程间通信与协作]({{< relref "post/java-multithreading-03" >}})
