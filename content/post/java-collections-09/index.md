---
title: "Java集合框架(九) ConcurrentHashMap并发原理"
date: 2018-01-19
draft: false
categories: ["Java"]
tags: ["ConcurrentHashMap", "CAS", "synchronized", "分段锁", "并发"]
series: ["Java集合系列"]
[params.toc]
  startLevel = 2
  endLevel = 3
---

## 前言

前面几篇文章我们分析了 HashMap 的原理。HashMap 不是线程安全的，在多线程环境下会导致数据错乱甚至死循环。Java 提供了 `ConcurrentHashMap` 作为线程安全的解决方案。这篇文章我们来深入理解它的并发原理。

<!--more-->

## 演进历史：JDK 1.7 vs 1.8

### JDK 1.7：分段锁（Segmentation）

```
┌─────────────────────────────────────────────────────────────────┐
│                     JDK 1.7 分段锁                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Segment[]  segments                                             │
│  ┌────────┬────────┬────────┬────────┐                         │
│  │ Seg-0  │ Seg-1  │ Seg-2  │ Seg-3  │                         │
│  └────────┴────────┴────────┴────────┘                         │
│       │          │          │          │                        │
│       ▼          ▼          ▼          ▼                        │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐              │
│  │ Hash-0 │  │ Hash-1 │  │ Hash-2 │  │ Hash-3 │              │
│  │ (lock) │  │ (lock) │  │ (lock) │  │ (lock) │              │
│  └────────┘  └────────┘  └────────┘  └────────┘              │
│                                                                  │
│  每个 Segment 独立加锁                                           │
│  不同 Segment 可以并发访问                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

默认 16 个 Segment，支持最多 16 个线程并发写
```

### JDK 1.8：CAS + synchronized

```
┌─────────────────────────────────────────────────────────────────┐
│                  JDK 1.8 CAS + Synchronized                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Node[]  table                                                   │
│  ┌────────┬────────┬────────┬────────┬────────┐                │
│  │ Bucket │ Bucket │ Bucket │ Bucket │ Bucket │                │
│  │  (CAS) │  (CAS) │  (CAS) │  (CAS) │  (CAS) │                │
│  └────────┴────────┴────────┴────────┴────────┘                │
│       │          │          │          │                        │
│       ▼          ▼          ▼          ▼                        │
│    链表/红黑树（synchronized）                                    │
│                                                                  │
│  • 空桶：CAS 设置头节点                                          │
│  • 有冲突：synchronized 锁住桶                                   │
│  • 并发度更高（每个桶独立锁）                                     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## 核心字段

```java
public class ConcurrentHashMap<K,V> extends AbstractMap<K,V>
        implements ConcurrentMap<K,V>, Serializable {
    
    // 核心数组（volatile 保证可见性）
    transient volatile Node<K,V>[] table;
    
    // 下一个新数组（扩容时使用）
    private transient volatile Node<K,V>[] nextTable;
    
    // 基本计数器（用于 size()）
    private transient volatile long baseCount;
    
    // 扩容戳（标记是否在扩容）
    private transient volatile int sizeCtl;
    
    // 扩容转移进度
    private transient volatile int transferIndex;
    
    // 并发级别（只用于兼容 JDK 1.7）
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
}
```

### sizeCtl 的多重含义

```java
sizeCtl 的值表示不同状态：

┌─────────────────────────────────────────────────────────────┐
│  sizeCtl < 0                                                │
│  • -1：正在初始化                                            │
│  • -N：正在扩容，高 N-1 位是扩容编号，低 N 位是线程数          │
│                                                              │
│  sizeCtl == 0                                                │
│  • 还未初始化，使用默认容量                                    │
│                                                              │
│  sizeCtl > 0                                                  │
│  • 初始化容量                                                │
│  • 或者 扩容阈值 = capacity * loadFactor                      │
└─────────────────────────────────────────────────────────────┘
```

## CAS 操作

### 什么是 CAS？

```
CAS = Compare And Swap（比较并交换）

三个参数：
• 内存位置 V
• 预期值 A
• 新值 B

语义：
if (V == A) {
    V = B;
    return true;
} else {
    return false;
}
```

### CAS 在 ConcurrentHashMap 中的应用

```java
// 使用 CAS 设置数组元素
static final <K,V> boolean casTabAt(Node<K,V>[] tab, int i,
                                     Node<K,V> c, Node<K,V> v) {
    return U.compareAndSwapObject(tab, i, c, v);
}

// 使用 CAS 设置 baseCount
static final long updateBaseCount(long val) {
    int赌;
    long base;
    do {
        base = baseCount;  // 当前值
        next = base + val;  // 新值
    } while (!U.compareAndSwapLong(this, BASECOUNT, base, next));
    // 如果 baseCount 没变，设置为 next；否则重试
    return next;
}
```

### 自旋的意义

```
CAS + 自旋 = 乐观锁

线程 A： CAS 失败，循环重试
        ↓
   ┌────┴────┐
   │ V == A? │
   └────┬────┘
      Yes │ No
        │   │
        ▼   ▼
    V = B   重试
    成功

优点：不阻塞线程
缺点：竞争激烈时 CPU 开销大
```

## put() 方法

```java
public V put(K key, V value) {
    return putVal(key, value, false);
}

final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    
    int hash = spread(key.hashCode());
    int binCount = 0;
    
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f;
        int n, i, fh;
        
        // 1. 初始化数组
        if (tab == null || (n = tab.length) == 0) {
            tab = initTable();
        }
        // 2. CAS 设置头节点（桶为空）
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null))) {
                break;
            }
        }
        // 3. 发现正在扩容，帮助扩容
        else if ((fh = f.hash) == MOVED) {
            tab = helpTransfer(tab, f);
        }
        // 4. 桶已存在，发生冲突
        else {
            V oldVal = null;
            // 锁住桶（synchronized）
            synchronized (f) {
                // 双重检查
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        // 链表处理
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            if (e.hash == hash &&
                                ((k = e.key) == key ||
                                 (key != null && key.equals(k)))) {
                                oldVal = e.value;
                                if (!onlyIfAbsent) {
                                    e.value = value;
                                }
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key, value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {
                        // 红黑树处理
                        binCount = 2;
                        TreeNode<K,V> r = ((TreeBin<K,V>)f).putTreeVal(hash, key, value);
                    }
                }
            }
            
            // 5. 链表长度 >= 8，树化
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD) {
                    treeifyBin(tab, i);
                }
                if (oldVal != null) {
                    return oldVal;
                }
                break;
            }
        }
    }
    
    // 6. 添加计数
    addCount(1L, binCount);
    return null;
}
```

### put() 流程图

```
                        ┌─────────────────┐
                        │   开始 put       │
                        └────────┬────────┘
                                 │
                        ┌────────▼────────────────────────┐
                        │ table == null?                  │
                        └────────┬────────────────────────┘
                      ┌───────┐   │   ┌─────────────┐
                     Yes      No  │   │ CAS 设置头节点 │
              ┌───────▼─────┐ │   │   │ (桶为空)     │
              │ initTable() │ │   │   └──────┬──────┘
              └─────────────┘ │   │          │
                             │    │   ┌──────▼──────┐
                             │    │   │  CAS 成功?   │
                             │    │   └──────┬──────┘
                             │    │    ┌─────┴─────┐
                             │    │   Yes          No
                             │    │    │             │
                             │    │    │    ┌────────▼──────┐
                             │    │    │    │ f.hash==MOVED? │
                             │    │    │    │ (正在扩容)     │
                             │    │    │    └───────┬───────┘
                             │    │    │      ┌────┴────┐
                             │    │    │     Yes       No
                             │    │    │      │         │
                             │    │    │      │    ┌─────▼─────┐
                             │    │    │      │    │ synchronized│
                             │    │    │      │    │ 锁住桶 f   │
                             │    │    │      │    └─────┬─────┘
                             │    │    │      │          │
                             │    │    │      │   ┌─────▼─────┐
                             │    │    │      │   │ 链表/红黑树 │
                             │    │    │      │   │ 查找/插入  │
                             │    │    │      │   └─────┬─────┘
                             │    │    │      │          │
                             │    │    │      │   ┌─────▼─────┐
                             │    │    │      │   │ 链表>8?   │
                             │    │    │      │   │ 树化     │
                             │    │    │      │   └─────┬─────┘
                             │    │    │      │          │
                             └────┴────┴──────┴──────┬──┴───┘
                                                     │
                                              ┌──────▼──────┐
                                              │ addCount()  │
                                              │ 更新计数     │
                                              └─────────────┘
```

## get() 方法

```java
public V get(Object key) {
    Node<K,V>[] tab;
    Node<K,V> e, p;
    int n, eh;
    K ek;
    
    int h = spread(key.hashCode());
    
    // 1. 定位桶
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) {
        
        // 2. 第一个节点就是
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek))) {
                return e.value;
            }
        }
        // 3. 正在迁移或红黑树
        else if (eh < 0) {
            return (p = e.find(h, key)) != null ? p.value : null;
        }
        // 4. 链表遍历
        while ((e = e.next) != null) {
            if (e.hash == h &&
                ((ek = e.key) == key || (ek != null && key.equals(ek)))) {
                return e.value;
            }
        }
    }
    return null;
}
```

### get() 的特点

```
✅ get() 不需要加锁！

原因：
1. Node 的 value 用 volatile 修饰
2. table 用 volatile 修饰
3. 构造函数 Happens-Before 所有 put 操作

所以读取线程总能读到最新写入的值
（volatile 的 Happens-Before 语义保证）
```

## 并发扩容

### 核心思想

```
多线程并发扩容，转移部分桶

┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  原数组（oldTable）        新数组（nextTable）                    │
│                                                                  │
│  ┌───┬───┬───┬───┐       ┌───┬───┬───┬───┬───┬───┬───┬───┐      │
│  │ A │ B │ C │ D │  →    │   │   │   │ A │   │ B │ C │ D │      │
│  └───┴───┴───┴───┘       └───┴───┴───┴───┴───┴───┴───┴───┘      │
│    0   1   2   3           0   1   2   3   4   5   6   7        │
│                            ↑                   ↑                 │
│                        转移 0,4              转移 1,5           │
│                                                                  │
│  每个线程负责一段（stride），并发转移                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 扩容步骤

```java
final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
    Node<K,V>[] nextTab;
    int sc;
    
    // 1. 确认正在扩容
    if (tab != null && f instanceof ForwardingNode &&
        (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) {
        
        int rs = resizeStamp(tab.length);
        
        // 2. 循环帮助扩容
        while (nextTab == nextTable && tab == table &&
               (sc = sizeCtl) < 0) {
            
            // 条件判断省略...
            
            // 3. 调用 transfer
            if (transfer(tab, nextTab)) {
                return nextTable;
            }
        }
    }
    return table;
}
```

### ForwardingNode

```java
// 特殊的转发节点，标记正在迁移的桶
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;
    
    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null);
        this.nextTable = tab;
    }
    
    // find 方法重定向到新数组
    Node<K,V> find(int h, Object k) {
        outer: for (Node<K,V>[] tab = nextTable;;) {
            // 在 nextTable 中查找
        }
    }
}
```

## 与 JDK 1.7 的对比

| 维度 | JDK 1.7 分段锁 | JDK 1.8 CAS + synchronized |
|------|--------------|----------------------------|
| 并发度 | 固定 16 | 动态（每个桶独立锁） |
| 锁粒度 | Segment（桶数组） | Bucket（链表/红黑树头） |
| 锁类型 | ReentrantLock | synchronized + CAS |
| 扩容 | 单线程扩容 | 多线程并发扩容 |
| 实现复杂度 | 复杂（Segment 嵌套） | 较简单 |
| 读并发 | 读不需要锁 | 读不需要锁 |

## 常见面试题

### Q1：ConcurrentHashMap 和 HashTable 的区别？

**答**：
- HashTable 使用 synchronized 锁整个方法，并发度低
- ConcurrentHashMap 使用 CAS + synchronized，分段加锁，并发度高
- JDK 1.8 后使用数组+链表/红黑树，锁粒度细化到桶

### Q2：ConcurrentHashMap 的 get() 为什么不需要加锁？

**答**：因为 Node 的 value 和 next 用 volatile 修饰，table 用 volatile 修饰，根据 Happens-Before 原则，put 操作 Happens-Before 后续的 get 操作，所以读取线程能看到最新值。

### Q3：JDK 1.8 的 ConcurrentHashMap 如何保证线程安全？

**答**：
- 空桶：用 CAS 设置头节点
- 非空桶：用 synchronized 锁住头节点
- 扩容：多线程并发转移桶

### Q4：ConcurrentHashMap 能否存 null 值？

**答**：不能。ConcurrentHashMap 的 key 和 value 都不允许为 null，这是为了和 HashMap 区分，防止多线程下的二义性。

---

## 总结

1. **JDK 1.7**：分段锁，固定 16 个 Segment，并发度固定
2. **JDK 1.8**：CAS + synchronized，锁粒度细化到桶，并发度更高
3. **CAS**：乐观锁，空桶时用 CAS 设置
4. **synchronized**：有冲突时锁住桶
5. **并发扩容**：多线程分工转移桶
6. **get 不加锁**：volatile 保证可见性

---

## 下篇预告

这是系列的最后一篇：**集合选型与实战总结**，包括：
- 各集合的适用场景
- 性能优化技巧
- 常见踩坑汇总

敬请期待！

---

> 📚 **推荐阅读**
> - [集合选型与实战总结]({{< relref "post/java-collections-10" >}})
> - [HashMap 红黑树原理]({{< relref "post/java-collections-08" >}})
> - [Java 集合框架全景图]({{< relref "post/java-collections-01" >}})
