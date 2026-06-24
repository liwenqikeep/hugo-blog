---
title: "Java集合框架(七) HashMap扩容机制深度解析"
date: 2018-01-15
draft: false
categories: ["Java"]
tags: ["HashMap", "扩容", "resize", "迁移", "死循环"]
series: ["Java集合系列"]
---

## 前言

上一篇文章我们分析了 HashMap 的核心原理，包括 put/get 的完整流程。这篇文章我们来深入理解 HashMap 的扩容机制，这是面试中的高频考点，也是 HashMap 性能优化的关键。

<!--more-->

## 扩容触发条件

### 扩容时机

```java
// HashMap 中有三个关键参数
threshold = capacity * loadFactor  // 扩容阈值

// 默认值
capacity = 16      // 初始容量
loadFactor = 0.75  // 负载因子
threshold = 16 * 0.75 = 12  // 当 size > 12 时触发扩容
```

### 扩容时机图解

```
初始状态：
┌─────────────────────────────────────────────────────┐
│  capacity = 16, loadFactor = 0.75, threshold = 12  │
│  size = 0                                           │
│                                                      │
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐  │
│  │  │  │  │  │  │  │  │  │  │  │  │  │  │  │  │  │  │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘  │
│                                                      │
│  size: ████░░░░░░░░░░░░░░░░░░░░░░░ 0/12             │
└─────────────────────────────────────────────────────┘

添加第 12 个元素后：
┌─────────────────────────────────────────────────────┐
│  size = 12, 触发扩容！                              │
│                                                      │
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐  │
│  │██│██│██│██│██│██│██│██│██│██│██│██│  │  │  │  │  │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘  │
│                                                      │
│  size: ████████████████████████████████░░░░ 12/12   │
└─────────────────────────────────────────────────────┘

扩容后：
┌─────────────────────────────────────────────────────┐
│  capacity = 32, threshold = 24                      │
│  size = 12 (元素重新分配后)                         │
│                                                      │
│  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐  │
│  │██│  │  │██│  │  │██│  │  │██│  │  │██│  │  │██│  │  │██│  │  │██│  │  │  │  │  │  │  │  │  │  │
│  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘  │
│                                                      │
│  size: ████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  12/24 │
└─────────────────────────────────────────────────────┘
```

### 负载因子的影响

| 负载因子 | 扩容阈值 | 空间利用率 | 查找/插入性能 |
|---------|---------|-----------|-------------|
| 0.5 | capacity * 0.5 | 低（浪费空间） | 快（冲突少） |
| **0.75** | capacity * 0.75 | 平衡（推荐） | 平衡 |
| 1.0 | capacity * 1.0 | 高（省空间） | 慢（冲突多） |

> **结论**：默认的 0.75 是时间和空间的平衡点，HashMap 作者的经验值。

## 扩容计算

### resize() 方法

```java
final Node<K,V>[] resize() {
    Node<K,V>[] oldTab = table;
    int oldCap = (oldTab == null) ? 0 : oldTab.length;
    int oldThr = threshold;
    
    int newCap, newThr = 0;
    
    if (oldCap > 0) {
        // 1. 已达最大容量，不再扩容
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return oldTab;
        }
        // 2. 新容量 = 旧容量 * 2
        else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                 oldCap >= DEFAULT_INITIAL_CAPACITY) {
            newThr = oldThr << 1;  // 新阈值 = 旧阈值 * 2
        }
    }
    else if (oldThr > 0) {
        // 3. 第一次 put，使用构造时的初始容量
        newCap = oldThr;
    }
    else {
        // 4. 使用默认值
        newCap = DEFAULT_INITIAL_CAPACITY;  // 16
        newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);  // 12
    }
    
    // 计算新的阈值
    if (newThr == 0) {
        float ft = (float)newCap * loadFactor;
        newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                  (int)ft : Integer.MAX_VALUE);
    }
    
    threshold = newThr;
    
    // 创建新数组
    @SuppressWarnings({"rawtypes","unchecked"})
    Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
    table = newTab;
    
    // 迁移元素
    if (oldTab != null) {
        for (int j = 0; j < oldCap; ++j) {
            Node<K,V> e;
            if ((e = oldTab[j]) != null) {
                oldTab[j] = null;
                if (e.next == null) {
                    // 单个节点，直接移动
                    newTab[e.hash & (newCap - 1)] = e;
                }
                else if (e instanceof TreeNode) {
                    // 红黑树迁移
                    ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                }
                else {
                    // 链表迁移（重点！）
                    // 优化：分为高位链和低位链
                    ...
                }
            }
        }
    }
    return newTab;
}
```

### 容量翻倍

```
扩容前：capacity = 16 (二进制 10000)
        threshold = 12

扩容后：capacity = 32 (二进制 100000)
        threshold = 24

规律：每次扩容容量翻倍（left shift 1）
      threshold 也翻倍
```

## 元素迁移机制

### JDK 1.7 的链表迁移

```java
// JDK 1.7 旧数组长度
int newCapacity = oldCapacity << 1;  // 16 → 32

// 迁移链表
void transfer(Entry[] newTable) {
    Entry[] src = table;
    int newCapacity = newTable.length;
    
    for (int j = 0; j < src.length; j++) {
        Entry<K,V> e = src[j];
        if (e != null) {
            src[j] = null;  // 释放旧表
            
            do {
                Entry<K,V> next = e.next;
                // 重新计算索引
                int i = indexFor(e.hash, newCapacity);
                e.next = newTable[i];
                newTable[i] = e;
                e = next;
            } while (e != null);
        }
    }
}
```

### JDK 1.8 的优化：高低位链表

```java
// JDK 1.8 优化的链表迁移
else {
    // loHead 和 hiHead 分别是低位链和高位链的头
    Node<K,V> loHead = null, loTail = null;
    Node<K,V> hiHead = null, hiTail = null;
    Node<K,V> next;
    
    do {
        next = e.next;
        // 核心：判断是否需要移动到高位
        // oldCap = 16, (e.hash & oldCap) 有两种结果：0 或 oldCap
        if ((e.hash & oldCap) == 0) {
            // 保持在原位置（原索引）
            if (loTail == null) {
                loHead = e;
            } else {
                loTail.next = e;
            }
            loTail = e;
        } else {
            // 移动到高位（原索引 + oldCap）
            if (hiTail == null) {
                hiHead = e;
            } else {
                hiTail.next = e;
            }
            hiTail = e;
        }
    } while ((e = next) != null);
    
    // 放置低位链
    if (loTail != null) {
        loTail.next = null;
        newTab[j] = loHead;
    }
    
    // 放置高位链
    if (hiTail != null) {
        hiTail.next = null;
        newTab[j + oldCap] = hiHead;
    }
}
```

### 高低位链原理

```
原数组容量：oldCap = 16 (二进制 10000)

假设某个 key 的 hash = 27
hash & oldCap = 27 & 16 = 0  → 留在原位置
                   11011
                 & 10000
                 ------
                   00000

假设某个 key 的 hash = 43
hash & oldCap = 43 & 16 = 16 → 移动到原位置 + oldCap
                   101011
                 &  10000
                 -------
                   10000 = 16

┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  原索引 0 上的链表：                                          │
│                                                              │
│  扩容前 (capacity=16):                                       │
│  ┌───┐                                                      │
│  │ A │ → B → C                                              │
│  └───┘                                                      │
│   0                                                         │
│                                                              │
│  扩容后 (capacity=32):                                      │
│  ┌───┐                    ┌───┐                            │
│  │ A │ → B                │ C │                              │
│  └───┘                    └───┘                              │
│   0                        16                                │
│  (hash&16=0)             (hash&16=16)                       │
│                                                              │
│  原理：只有 hash 值在 oldCap 位为 1 时，才会移动到高位        │
│        例如 oldCap=16，第 5 位(16)                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 扩容迁移图解

```
扩容前 (capacity = 16)：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │ A │   │ B │   │   │ C │   │   │ D │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15

假设 A、C、D 的 hash&16=0
假设 B 的 hash&16=16

扩容后 (capacity = 32)：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │ A │   │   │   │ B │   │   │ C │   │   │   │ D │   │   │   │   │   │   │   │   │   │   │   │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20  21  22  23  24  25  26  27  28  29  30  31
                                                        ↑
                                                        B 移动到这里
```

## 多线程下的扩容问题

### JDK 1.7 的死循环

HashMap 在 JDK 1.7 时，扩容操作不是线程安全的。在多线程环境下，可能导致死循环。

```java
// JDK 1.7 迁移代码简化
void transfer(Entry[] newTable) {
    Entry[] src = table;
    int newCapacity = newTable.length;
    
    for (int j = 0; j < src.length; j++) {
        Entry<K,V> e = src[j];
        if (e != null) {
            do {
                Entry<K,V> next = e.next;
                int i = indexFor(e.hash, newCapacity);
                e.next = newTable[i];  // 头插法！
                newTable[i] = e;
                e = next;
            } while (e != null);
        }
    }
}
```

### 死循环成因图解

```
初始状态（假设两个线程同时扩容）：
线程 A 和 B 都看到同样的链表结构

原链表： A → B → null
        ↓
      e=B, next=A
      
线程 A 执行 transfer()，头插法：

第一次：e=B
        B.next = newTable[i]
        newTable[i] = B
        结果：newTable[i] = B → null

第二次：e=A
        A.next = newTable[i] = B
        newTable[i] = A
        结果：newTable[i] = A → B → null

但如果线程 A 被挂起，线程 B 先完成：

线程 B 完成后的结构：
newTable[i] = A → B → null

线程 A 恢复，继续执行 transfer()：

第一次：e=B（从 src[j] 读取）
        B.next = newTable[i] = A → B
        newTable[i] = B
        结果：newTable[i] = B → A → B → ...（循环！）
        
第二次：e=A（next = B）
        A.next = newTable[i] = B → A → B ...
        newTable[i] = A
        结果：newTable[i] = A → B → A → B → ...（死循环！）
```

### 解决方案

```java
// ❌ 不要在多线程环境下使用 HashMap
HashMap<String, String> map = new HashMap<>();
map.put("key", "value");  // 可能死循环或数据丢失！

// ✅ 方案 1：使用 Collections.synchronizedMap
Map<String, String> map = Collections.synchronizedMap(new HashMap<>());

// ✅ 方案 2：使用 ConcurrentHashMap（推荐）
ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
```

## 扩容的性能优化

### 预估容量

```java
// ❌ 低效：频繁扩容
Map<String, String> map = new HashMap<>();
for (int i = 0; i < 100000; i++) {
    map.put("key" + i, "value");
}
// 会触发多次扩容

// ✅ 高效：预估容量
Map<String, String> map = new HashMap<>(131072);  // 2^17
for (int i = 0; i < 100000; i++) {
    map.put("key" + i, "value");
}
// 只在最后可能触发一次扩容
```

### 容量计算建议

```java
// 预估元素数量 n，反推 HashMap 容量
// 目标：size < threshold = capacity * loadFactor
// capacity = n / loadFactor * 1.2（留 20% 余量）
// 然后向上取整到 2 的幂次

int n = 100000;
int capacity = (int) (n / 0.75 * 1.2);  // 160000
// 向上取整到 2 的幂次
int size = 1;
while (size < capacity) {
    size <<= 1;
}
// size = 262144 = 2^18

HashMap<String, String> map = new HashMap<>(size);
```

### LinkedHashMap 的 LRU 示例

```java
// 利用 LinkedHashMap 实现 LRU 缓存
public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int maxSize;
    
    public LRUCache(int maxSize) {
        super(16, 0.75f, true);  // accessOrder = true
        this.maxSize = maxSize;
    }
    
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}

// 使用
LRUCache<String, String> cache = new LRUCache<>(1000);
cache.put("key", "value");  // 自动淘汰最老的条目
```

## 常见面试题

### Q1：HashMap 的扩容机制？

**答**：
1. 当 size > capacity * loadFactor 时触发
2. 创建新数组，容量和阈值都翻倍
3. 将原数组的元素迁移到新数组
4. JDK 1.8 优化：高低位链表，避免重新计算 hash

### Q2：JDK 1.8 对扩容做了哪些优化？

**答**：
1. 利用 (e.hash & oldCap) 判断是否需要移动到高位，避免重新计算 hash
2. 将链表分为高低位两个链表，头插法改为尾插法顺序不变
3. 红黑树有专门的 split() 方法处理

### Q3：HashMap 在 JDK 1.7 多线程下会死循环？

**答**：是的。JDK 1.7 的扩容采用头插法，多线程下可能导致链表成环，形成死循环。JDK 1.8 已修复（但仍不是线程安全的）。

### Q4：负载因子为什么是 0.75？

**答**：这是时间和空间的平衡。太小（如 0.5）空间利用率低，太大（如 1.0）冲突多性能差。0.75 是一个经验值。

### Q5：HashMap 的容量为什么必须是 2 的幂次？

**答**：便于用 (n-1) & hash 快速计算索引，而且扩容时可以用 (e.hash & oldCap) 判断高位，简化迁移逻辑。

---

## 总结

1. **扩容触发**：`size > capacity * loadFactor`
2. **扩容计算**：容量翻倍，阈值也翻倍
3. **JDK 1.8 优化**：高低位链表，避免重新计算 hash
4. **多线程问题**：JDK 1.7 可能死循环，JDK 1.8 不会但仍非线程安全
5. **性能优化**：预估容量，避免频繁扩容

---

## 下篇预告

下一篇文章我们将讲解 **HashMap 红黑树原理**，包括：
- 链表树化条件
- 红黑树的特性
- 为什么用红黑树而不是其他平衡树

敬请期待！

---

> 📚 **推荐阅读**
> - [HashMap 红黑树原理]({{< relref "post/java-collections-08" >}})
> - [ConcurrentHashMap 并发原理]({{< relref "post/java-collections-09" >}})
> - [HashMap 核心原理]({{< relref "post/java-collections-06" >}})
