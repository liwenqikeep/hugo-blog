---
title: "Java 多线程（二）：线程同步机制"
date: 2017-12-14
draft: false
categories: ["Java"]
tags: ["Java SE", "多线程", "并发编程", "synchronized", "Lock"]
---

## 前言

上一篇文章介绍了线程的创建方式和生命周期。在多线程环境中，如果多个线程同时访问共享资源，就会产生线程安全问题。本篇文章将深入讲解 Java 中的线程同步机制，包括 synchronized 和 Lock 的使用与底层原理。

## 一、为什么需要同步？

先看一个经典的安全问题示例：

```java
public class Counter {
    private int count = 0;
    
    public void increment() {
        count++;  // 不是原子操作！
    }
    
    public int getCount() {
        return count;
    }
}
```

`count++` 看似简单，实际上包含三个操作：
1. 读取 count 的值
2. 将 count 加 1
3. 写回 count

如果两个线程同时执行，可能出现：
- 线程 A 读取 count=0
- 线程 B 读取 count=0
- 线程 A 写入 count=1
- 线程 B 写入 count=1

最终结果应该是 2，但实际得到 1。这就是典型的**竞态条件（Race Condition）**。

## 二、synchronized 关键字

### 2.1 基本用法

synchronized 是 Java 最基本的同步方式，有三种使用场景：

```java
public class SynchronizedDemo {
    
    // 1. 修饰实例方法 - 锁住整个对象
    public synchronized void method1() {
        // 同一时刻只有一个线程能执行这个方法
    }
    
    // 2. 修饰静态方法 - 锁住整个类
    public static synchronized void method2() {
        // 同一时刻只有一个线程能执行这个静态方法
    }
    
    // 3. 修饰代码块 - 指定锁对象
    public void method3() {
        synchronized (this) {
            // 锁住当前对象
        }
    }
    
    public void method4() {
        synchronized (SynchronizedDemo.class) {
            // 锁住类对象
        }
    }
}
```

### 2.2 synchronized 的锁升级过程

JVM 对 synchronized 做了大量优化，从低到高有四种锁状态：

```
无锁 → 偏向锁 → 轻量级锁 → 重量级锁
```

| 锁状态 | 适用场景 | 优点 | 缺点 |
|--------|----------|------|------|
| 偏向锁 | 单线程访问同步块 | 无自旋开销 | 多线程竞争有撤销成本 |
| 轻量级锁 | 短时间多线程交替执行 | 避免操作系统 mutex | 自旋消耗 CPU |
| 重量级锁 | 多线程长时间竞争 | 不消耗 CPU | 用户态到内核态切换 |

**实际项目建议**：
- 大部分情况下让 JVM 自动优化，不必刻意关注
- 避免锁粒度过大，减少锁竞争
- 热点数据单独加锁，而非整个方法

### 2.3 实战：安全的账户转账

```java
public class Account {
    private String accountId;
    private double balance;
    
    // 使用对象锁，保证转账操作的原子性
    private final Object lock = new Object();
    
    public void transferTo(Account target, double amount) {
        // 防止死锁：按固定顺序获取锁
        String firstId = this.accountId.compareTo(target.accountId) < 0 
                         ? this.accountId : target.accountId;
        String secondId = this.accountId.compareTo(target.accountId) < 0 
                          ? target.accountId : this.accountId;
        
        // 模拟转账处理时间
        try {
            System.out.println(Thread.currentThread().getName() 
                             + " 准备转账 " + amount);
            
            synchronized (firstId.intern()) {
                synchronized (secondId.intern()) {
                    if (this.balance >= amount) {
                        this.balance -= amount;
                        target.balance += amount;
                        System.out.println(Thread.currentThread().getName() 
                                         + " 转账成功，余额: " + this.balance);
                    } else {
                        System.out.println(Thread.currentThread().getName() 
                                         + " 余额不足，转账失败");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

**设计要点**：
1. 使用对象锁而非方法锁，粒度更细
2. 按账户 ID 顺序获取锁，避免死锁
3. 使用 `intern()` 确保字符串常量池中的唯一性

## 三、Lock 接口

### 3.1 Lock vs synchronized

| 特性 | synchronized | Lock |
|------|--------------|------|
| 获取锁方式 | 自动获取/释放 | 手动调用 |
| 尝试非阻塞获取 | 否 | 是（tryLock()） |
| 超时等待 | 否 | 是（tryLock(timeout)） |
| 公平锁 | 否 | 可选择（ReentrantLock(true)） |
| 多条件等待 | 否 | 是（Condition） |

### 3.2 ReentrantLock 的使用

```java
public class LockDemo {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    
    private boolean ready = false;
    
    public void await() throws InterruptedException {
        lock.lock();
        try {
            while (!ready) {
                condition.await();  // 等待
            }
            System.out.println("继续执行...");
        } finally {
            lock.unlock();
        }
    }
    
    public void signal() {
        lock.lock();
        try {
            ready = true;
            condition.signal();  // 唤醒一个等待线程
            // 或 condition.signalAll() 唤醒所有
        } finally {
            lock.unlock();
        }
    }
}
```

**重要原则**：锁的获取和释放必须在 try-finally 块中，确保异常时也能释放锁。

### 3.3 实战：可重入的缓存服务

```java
public class CacheService {
    private final Map<String, Object> cache = new HashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    
    // 读操作：多线程并发读
    public Object get(String key) {
        rwLock.readLock().lock();
        try {
            return cache.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }
    
    // 写操作：独占写
    public void put(String key, Object value) {
        rwLock.writeLock().lock();
        try {
            cache.put(key, value);
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    // 批量写入
    public void putAll(Map<String, Object> entries) {
        rwLock.writeLock().lock();
        try {
            cache.putAll(entries);
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
```

**设计要点**：
1. 使用读写锁，读操作之间不互斥，提高并发度
2. 写操作独占，保证数据一致性
3. 读写锁支持锁的降级（从写锁降级到读锁）

## 四、如何选择 synchronized 和 Lock

### 4.1 推荐原则

**优先使用 synchronized**：
- 简单场景，代码更简洁
- 无需尝试获取锁、超时等复杂需求
- JVM 自动优化，性能差距已很小

**使用 Lock**：
- 需要尝试非阻塞获取锁
- 需要公平锁（先来先服务）
- 需要多个条件变量
- 需要精细控制锁的获取顺序

### 4.2 实战：限时抢购场景

```java
public class FlashSaleService {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Integer> stock = new ConcurrentHashMap<>();
    
    public boolean flashSale(Long userId, Long productId) {
        // 尝试获取锁，最多等待 100 毫秒
        if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            System.out.println("系统繁忙，请稍后重试");
            return false;
        }
        
        try {
            Integer left = stock.get(productId);
            if (left == null || left <= 0) {
                System.out.println("商品已售罄");
                return false;
            }
            
            if (hasUserPurchased(userId, productId)) {
                System.out.println("您已购买过此商品");
                return false;
            }
            
            stock.put(productId, left - 1);
            recordPurchase(userId, productId);
            System.out.println("购买成功！");
            return true;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    private boolean hasUserPurchased(Long userId, Long productId) {
        return false;  // 简化实现
    }
    
    private void recordPurchase(Long userId, Long productId) {
        // 记录购买
    }
}
```

## 五、死锁问题与排查

### 5.1 死锁的四个必要条件

1. **互斥**：资源一次只能被一个线程持有
2. **持有并等待**：线程持有资源的同时请求其他资源
3. **不可抢占**：已持有的资源不能被强制释放
4. **循环等待**：形成资源获取的环形链

### 5.2 避免死锁的策略

```java
// 错误的做法：按不同顺序获取锁
public void wrongOrder(Account a, Account b) {
    synchronized (a) {
        synchronized (b) {
            // 转账操作
        }
    }
}

// 正确的做法：按固定顺序获取锁
public void correctOrder(Account a, Account b) {
    if (a.hashCode() < b.hashCode()) {
        synchronized (a) {
            synchronized (b) {
                // 转账操作
            }
        }
    } else {
        synchronized (b) {
            synchronized (a) {
                // 转账操作
            }
        }
    }
}
```

### 5.3 排查死锁

```bash
# 使用 jstack 查看线程状态
jstack <pid>

# 输出示例
Found one Java-level deadlock:
=========================
"Thread-1":
  waiting for ownable synchronizer 0x00000000d8300b80, 
  (a java.util.concurrent.locks.ReentrantLock$NonfairSync),
  which is held by "Thread-0"
"Thread-0":
  waiting for ownable synchronizer 0x00000000d8300c00, 
  (a java.util.concurrent.locks.ReentrantLock$NonfairSync),
  which is held by "Thread-1"
```

## 总结

本文详细讲解了 Java 的线程同步机制：

1. **synchronized**：JVM 内置语法糖，经过大量优化，适用于大多数场景
2. **Lock 接口**：更灵活的控制能力，支持尝试获取、公平锁、多条件等待
3. **读写锁**：读写分离，提高并发读的性能
4. **死锁避免**：按固定顺序获取锁是最有效的策略

**实际项目建议**：
- 优先使用 synchronized，代码简洁且性能优秀
- 复杂场景使用 ReentrantLock 或读写锁
- 始终注意锁的粒度，避免过度同步
- 使用 jstack 定期排查潜在死锁

## 下篇预告

下一篇文章我们将讲解线程间通信与协作，包括 wait/notify 机制、生产者-消费者模式，以及实际项目中的批量任务处理场景。

---

**相关阅读**：
- [Java 多线程（一）：线程创建与生命周期]({{< relref "post/java-multithreading-01" >}})
