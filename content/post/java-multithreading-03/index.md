---
title: "Java 多线程（三）：线程间通信与协作"
date: 2017-12-16
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程", "wait/notify", "生产者消费者"]
---

## 前言

前两篇文章分别介绍了线程的创建方式和同步机制。本篇文章将聚焦于线程间的通信与协作，这是实现复杂并发业务的核心能力。

## 一、Object 类的 wait/notify 机制

### 1.1 基本概念

Java 中，每个对象都有一把内置锁和等待队列。当线程调用对象的 `wait()` 方法时，会释放锁并进入该对象的等待池，直到其他线程调用 `notify()` 或 `notifyAll()` 将其唤醒。

**核心方法**：

| 方法 | 说明 |
|------|------|
| wait() | 当前线程进入等待状态，释放对象锁 |
| notify() | 唤醒一个等待该对象锁的线程（随机选择） |
| notifyAll() | 唤醒所有等待该对象锁的线程 |

### 1.2 必须配合 synchronized 使用

这是一个常见的错误：

```java
// 错误写法！会抛出 IllegalMonitorStateException
public void wrongUsage() {
    this.wait();  // 没有获取对象的锁
}
```

**正确写法**：

```java
public synchronized void correctUsage() {
    while (condition not met) {
        try {
            wait();  // 在持有锁的状态下调用
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    // 处理业务
    notifyAll();  // 唤醒等待的线程
}
```

### 1.3 虚假唤醒问题

`wait()` 可能会出现虚假唤醒（spurious wakeup），即没有调用 `notify()` 也会醒来。因此必须使用循环而非 if 判断：

```java
// 错误写法
synchronized (lock) {
    if (!ready) {  // ❌ 虚假唤醒会导致继续执行
        lock.wait();
    }
}

// 正确写法
synchronized (lock) {
    while (!ready) {  // ✅ 循环检查
        lock.wait();
    }
}
```

## 二、实战：生产者-消费者模式

这是最经典的线程协作案例。

### 2.1 一对一模型

```java
public class ProducerConsumer {
    private final int MAX_SIZE = 10;
    private final Queue<Integer> queue = new LinkedList<>();
    
    // 生产者
    class Producer extends Thread {
        @Override
        public void run() {
            synchronized (queue) {
                while (queue.size() >= MAX_SIZE) {
                    try {
                        queue.wait();  // 队列满了，等待消费
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                int value = (int) (Math.random() * 100);
                queue.offer(value);
                System.out.println("生产: " + value);
                
                queue.notify();  // 通知消费者
            }
        }
    }
    
    // 消费者
    class Consumer extends Thread {
        @Override
        public void run() {
            synchronized (queue) {
                while (queue.isEmpty()) {
                    try {
                        queue.wait();  // 队列空了，等待生产
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                Integer value = queue.poll();
                System.out.println("消费: " + value);
                
                queue.notify();  // 通知生产者
            }
        }
    }
}
```

### 2.2 多生产者多消费者模型

实际项目通常是多个线程并发执行：

```java
public class MultiProducerConsumer {
    private final int MAX_SIZE = 100;
    private final Queue<Integer> queue = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    
    public void produce(int value) {
        lock.lock();
        try {
            while (queue.size() >= MAX_SIZE) {
                notFull.await();  // 等待队列不满
            }
            
            queue.offer(value);
            System.out.println(Thread.currentThread().getName() + " 生产: " + value);
            
            notEmpty.signal();  // 通知队列不空
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }
    
    public int consume() {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();  // 等待队列不空
            }
            
            Integer value = queue.poll();
            System.out.println(Thread.currentThread().getName() + " 消费: " + value);
            
            notFull.signal();  // 通知队列不满
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            lock.unlock();
        }
    }
}
```

**设计要点**：
1. 使用 ReentrantLock + Condition 替代 Object 的 wait/notify
2. Condition 支持更精细的等待条件控制
3. 使用 signal() 而非 signalAll()，减少上下文切换

## 三、实战：批量任务处理与进度控制

### 3.1 带进度回调的任务处理

```java
public class BatchTaskProcessor<T, R> {
    private final ExecutorService executor;
    private final int batchSize;
    
    public BatchTaskProcessor(int threadCount, int batchSize) {
        this.executor = Executors.newFixedThreadPool(threadCount);
        this.batchSize = batchSize;
    }
    
    // 同步批量处理
    public List<R> processSync(List<T> tasks, TaskHandler<T, R> handler) 
            throws InterruptedException, ExecutionException {
        List<Future<R>> futures = new ArrayList<>();
        
        for (T task : tasks) {
            Future<R> future = executor.submit(() -> handler.handle(task));
            futures.add(future);
        }
        
        List<R> results = new ArrayList<>();
        for (Future<R> future : futures) {
            results.add(future.get());
        }
        
        return results;
    }
    
    // 带进度的异步处理
    public void processWithProgress(List<T> tasks, 
                                    TaskHandler<T, R> handler,
                                    ProgressCallback<R> callback) {
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(tasks.size());
        
        for (T task : tasks) {
            executor.submit(() -> {
                try {
                    R result = handler.handle(task);
                    int index = completed.getAndIncrement();
                    callback.onProgress(index + 1, tasks.size(), result);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // 等待所有任务完成
        executor.submit(() -> {
            try {
                latch.await();
                callback.onComplete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    @FunctionalInterface
    public interface TaskHandler<T, R> {
        R handle(T task) throws Exception;
    }
    
    @FunctionalInterface
    public interface ProgressCallback<R> {
        void onProgress(int completed, int total, R result);
        default void onComplete() {}
    }
}
```

### 3.2 使用示例

```java
public class BatchTaskDemo {
    public static void main(String[] args) {
        BatchTaskProcessor<String, Integer> processor = 
            new BatchTaskProcessor<>(4, 10);
        
        List<String> tasks = IntStream.range(0, 100)
            .mapToObj(i -> "Task-" + i)
            .collect(Collectors.toList());
        
        processor.processWithProgress(tasks, task -> {
            // 模拟处理
            Thread.sleep(100);
            return task.length();
        }, (completed, total, result) -> {
            System.out.printf("进度: %d/%d (%.1f%%)%n", 
                completed, total, completed * 100.0 / total);
        }, () -> {
            System.out.println("所有任务已完成！");
            processor.shutdown();
        });
    }
}
```

## 四、CountDownLatch 与 CyclicBarrier

### 4.1 CountDownLatch（倒计时门栓）

用于等待多个线程完成后再继续执行：

```java
public class CountDownLatchDemo {
    public static void main(String[] args) throws InterruptedException {
        int threadCount = 3;
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    System.out.println("线程 " + threadId + " 开始执行");
                    Thread.sleep((long) (Math.random() * 1000));
                    System.out.println("线程 " + threadId + " 执行完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();  // 计数减一
                }
            }).start();
        }
        
        latch.await();  // 等待所有线程完成
        System.out.println("所有线程执行完成，继续主流程");
    }
}
```

**典型应用场景**：
- 等待多个配置加载完成
- 等待多个 RPC 调用返回
- 主测试用例等待所有子测试用例完成

### 4.2 CyclicBarrier（循环栅栏）

用于多个线程互相等待，达到条件后一起继续执行：

```java
public class CyclicBarrierDemo {
    public static void main(String[] args) {
        int partyCount = 3;
        CyclicBarrier barrier = new CyclicBarrier(partyCount, () -> {
            System.out.println("所有玩家已就位，游戏开始！");
        });
        
        for (int i = 0; i < partyCount; i++) {
            final int playerId = i;
            new Thread(() -> {
                try {
                    System.out.println("玩家 " + playerId + " 正在等待其他玩家...");
                    barrier.await();  // 等待其他玩家
                    
                    System.out.println("玩家 " + playerId + " 开始行动");
                    
                    // 可以复用：CyclicBarrier 可以重置
                    barrier.await();  // 等待本轮结束
                    
                } catch (BrokenBarrierException | InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }
}
```

**典型应用场景**：
- 多线程计算，最后合并结果
- 多人游戏等待所有玩家准备
- 压力测试时等待所有请求同时发起

### 4.3 对比选择

| 特性 | CountDownLatch | CyclicBarrier |
|------|----------------|---------------|
| 能否重置 | 否 | 是 |
| 计数方向 | 递减到 0 | 递增到目标值 |
| 等待方式 | 主线程等待子线程 | 子线程之间互相等待 |
| 典型场景 | 主流程等待子任务 | 子任务互相协调 |

## 五、Semaphore 信号量

控制同时访问某个资源的线程数量：

```java
public class SemaphoreDemo {
    public static void main(String[] args) {
        // 最多允许 3 个线程同时访问
        Semaphore semaphore = new Semaphore(3);
        
        for (int i = 0; i < 10; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    semaphore.acquire();  // 获取许可证
                    System.out.println("线程 " + threadId + " 获取到许可");
                    
                    Thread.sleep(1000);  // 模拟使用资源
                    
                    System.out.println("线程 " + threadId + " 释放许可");
                    semaphore.release();  // 释放许可证
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
}
```

**典型应用场景**：
- 数据库连接池限制
- 接口限流
- 资源池管理

## 六、实战：高并发短连接处理

以即时通讯场景为例：

```java
public class ChatServer {
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor;
    private final Semaphore connectionLimit;
    
    public ChatServer() {
        this.ioExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2);
        this.connectionLimit = new Semaphore(10000);
    }
    
    public void handleMessage(String userId, Message message) {
        if (!connectionLimit.tryAcquire()) {
            System.out.println("连接数已达上限，拒绝消息");
            return;
        }
        
        ioExecutor.submit(() -> {
            try {
                Connection conn = connections.get(userId);
                if (conn != null) {
                    conn.send(message);
                }
            } finally {
                connectionLimit.release();
            }
        });
    }
    
    public void broadcast(Message message) {
        CountDownLatch latch = new CountDownLatch(connections.size());
        
        connections.forEach((userId, conn) -> {
            ioExecutor.submit(() -> {
                try {
                    conn.send(message);
                } finally {
                    latch.countDown();
                }
            });
        });
        
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

## 总结

本文详细介绍了线程间通信与协作的核心机制：

1. **wait/notify**：`Object` 类的线程等待与唤醒机制，需配合 synchronized 使用
2. **Condition**：更灵活的等待/通知，支持多个条件队列
3. **CountDownLatch**：一次性倒计时锁，用于主线程等待子任务
4. **CyclicBarrier**：可循环使用的栅栏，用于线程间互相等待
5. **Semaphore**：信号量，控制并发访问数量

**实际项目建议**：
- 优先使用高级工具类（CountDownLatch、CyclicBarrier 等）
- wait/notify 用于简单场景，复杂场景用 Condition
- 使用 Semaphore 做限流保护
- 生产者-消费者模式是队列处理的标准范式

## 下篇预告

下一篇文章我们将深入讲解 Java 内存模型（JMM）与并发基础，包括 volatile 的原理、CAS 机制、以及线程安全的并发集合。

---

**相关阅读**：
- [Java 多线程（一）：线程创建与生命周期]({{< relref "post/java-multithreading-01" >}})
- [Java 多线程（二）：线程同步机制]({{< relref "post/java-multithreading-02" >}})
