---
title: "Java 多线程（一）：线程创建与生命周期"
date: 2017-12-12
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程"]
---

## 前言

在实际项目中，多线程编程是提升系统性能和响应速度的重要手段。然而，很多开发者对线程的理解仅限于「启动一个线程」，缺乏系统性认知。

本系列文章将带你从原理到实践，系统掌握 Java 多线程编程。今天是第一篇：线程创建与生命周期。

## 一、线程的创建方式

Java 中有三种主要的线程创建方式，它们各有特点和适用场景。

### 1.1 继承 Thread 类

```java
public class MyThread extends Thread {
    @Override
    public void run() {
        System.out.println("Thread is running...");
    }
}

// 使用
MyThread thread = new MyThread();
thread.start();
```

**特点**：
- 简单直接，代码编写方便
- 但 Java 单继承的限制会约束类的设计
- 适合简单的线程任务

### 1.2 实现 Runnable 接口

```java
public class MyRunnable implements Runnable {
    @Override
    public void run() {
        System.out.println("Runnable is running...");
    }
}

// 使用
Thread thread = new Thread(new MyRunnable());
thread.start();
```

**特点**：
- 不占用类的继承位置，更加灵活
- 是实际项目中最常用的方式
- 便于多个线程共享同一任务对象

### 1.3 实现 Callable 接口

```java
public class MyCallable implements Callable<Integer> {
    @Override
    public Integer call() throws Exception {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += i;
        }
        return sum;
    }
}

// 使用
ExecutorService executor = Executors.newSingleThreadExecutor();
Future<Integer> future = executor.submit(new MyCallable());
Integer result = future.get();  // 阻塞等待结果
executor.shutdown();
```

**特点**：
- 可以返回执行结果
- 可以抛出异常
- 需要配合线程池或 Future 使用

### 1.4 FutureTask 包装器

如果你已经有一个 Callable 对象，可以通过 FutureTask 将其包装成 Runnable：

```java
Callable<Integer> callable = () -> {
    Thread.sleep(1000);
    return 42;
};

FutureTask<Integer> futureTask = new FutureTask<>(callable);
new Thread(futureTask).start();

Integer result = futureTask.get();  // 获取结果
```

## 二、三种方式的对比与选择

| 特性 | Thread | Runnable | Callable |
|------|--------|----------|----------|
| 是否能返回值 | 否 | 否 | 是 |
| 是否能抛异常 | 否 | 否 | 是 |
| 是否占用继承位置 | 是 | 否 | 否 |
| 实际项目使用频率 | 低 | 高 | 高 |

**选择建议**：
- 简单任务、快速启动 → Thread
- 通用场景、需要复用 → Runnable + ThreadPoolExecutor
- 需要返回值、处理业务结果 → Callable + Future

## 三、线程的 lifecycle 与状态转换

理解线程的状态是排查并发问题的前提。Java 线程有 6 种状态：

```
NEW → RUNNABLE → BLOCKED/WAITING/TIMED_WAITING → TERMINATED
```

### 3.1 线程状态详解

| 状态 | 说明 |
|------|------|
| NEW | 线程创建但未启动 |
| RUNNABLE | 正在 JVM 中执行或等待 CPU 时间片 |
| BLOCKED | 等待获取监视器锁（进入 synchronized 块） |
| WAITING | 无限期等待（调用 wait()、join()） |
| TIMED_WAITING | 有限期等待（sleep()、wait(timeout)、join(timeout)） |
| TERMINATED | 线程执行完毕 |

### 3.2 状态转换图

```
                    ┌──────────────────────────────────────┐
                    │                                      │
                    ▼                                      │
    NEW ──→ RUNNABLE ──→ TERMINATED                        │
                    │                  ▲                   │
                    │                  │                   │
                    ▼                  │                   │
              BLOCKED ◄────────────────┤ (获取到锁)         │
                    │                                      │
                    ▼                                      │
              WAITING ◄──── (wait/join)                    │
                    │                                      │
                    ▼                                      │
          TIMED_WAITING ◄── (sleep/timeout wait)            │
                    │                                      │
                    └──────────────────────────────────────┘
```

### 3.3 实战：观察线程状态

```java
public class ThreadStateDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread thread = new Thread(() -> {
            System.out.println("线程状态: " + Thread.currentThread().getState());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        
        System.out.println("创建后: " + thread.getState());  // NEW
        thread.start();
        System.out.println("启动后: " + thread.getState());   // RUNNABLE
        
        Thread.sleep(100);
        System.out.println("休眠后: " + thread.getState());  // TIMED_WAITING
        
        thread.join();
        System.out.println("结束后: " + thread.getState());  // TERMINATED
    }
}
```

## 四、start() 与 run() 的区别

这是一个常见的面试题：

- `start()`：启动新线程，线程进入 RUNNABLE 状态，JVM 调用 run() 方法
- `run()`：普通方法调用，在当前线程执行，不会创建新线程

**错误示例**：
```java
Thread thread = new Thread(() -> {
    // 耗时任务
});
thread.run();  // ❌ 这样不会创建新线程！
```

**正确示例**：
```java
thread.start();  // ✅ 正确启动方式
```

## 五、实战：电商场景中的线程创建

在实际项目中，我们经常需要处理批量订单。假设有这样的场景：

```java
public class OrderProcessor {
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    
    public void processOrders(List<Order> orders) {
        List<Future<Boolean>> futures = orders.stream()
            .map(order -> executor.submit(() -> processOrder(order)))
            .collect(Collectors.toList());
        
        // 等待所有订单处理完成
        for (Future<Boolean> future : futures) {
            try {
                Boolean result = future.get();
                if (!result) {
                    // 处理失败的订单
                    handleFailedOrder(future);
                }
            } catch (Exception e) {
                log.error("订单处理异常", e);
            }
        }
    }
    
    private boolean processOrder(Order order) {
        // 模拟订单处理逻辑
        return true;
    }
}
```

**设计要点**：
1. 使用线程池而非手动创建线程，避免资源耗尽
2. 通过 Callable 返回处理结果，便于统计
3. 集中管理线程生命周期

## 六、注意事项

### 6.1 不要重复调用 start()

每个线程只能启动一次。重复调用会抛出 `IllegalThreadStateException`。

### 6.2 线程的随机性

多个线程启动后，执行顺序完全由 CPU 调度决定，不要依赖执行顺序。

### 6.3 守护线程

通过 `thread.setDaemon(true)` 可以将线程设置为守护线程。JVM 会在所有非守护线程结束后自动退出。

## 总结

本文介绍了 Java 线程的三种创建方式和线程的六种状态：

1. **Thread** - 简单直接，适合快速原型
2. **Runnable** - 灵活通用，是实际项目首选
3. **Callable + Future** - 支持返回值和异常处理

理解线程的生命周期对于排查并发问题至关重要，特别是在分析死锁、饥饿等问题时。

## 下篇预告

下一篇文章我们将深入讲解线程同步机制，包括 synchronized 的底层原理、Lock 接口的使用，以及如何在实际项目中选择合适的同步方式。

---

**相关阅读**：
- [Java 基础语法]({{< relref "post/java-basics" >}})
- [Java 集合框架]({{< relref "post/java-collections" >}})
