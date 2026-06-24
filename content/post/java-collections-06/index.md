---
title: "Java集合框架(六) HashMap核心原理"
date: 2018-01-13
draft: false
categories: ["Java"]
tags: ["HashMap", "哈希", "hashCode", "put", "get"]
series: ["Java集合系列"]
[params.toc]
  startLevel = 2
  endLevel = 3
---

## 前言

从这篇文章开始，我们进入 **Map 专题**。HashMap 是 Java 中最常用的键值对存储结构，也是面试中的高频考点。这篇文章我们来深入理解 HashMap 的核心原理。

<!--more-->

## HashMap 的底层结构

### JDK 1.7 vs 1.8+

```
JDK 1.7 之前：数组 + 链表
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│   table (Entry[]数组)                                            │
│   ┌───┬───┬───┬───┬───┬───┬───┬───┐                            │
│   │   │   │   │   │   │   │   │   │                            │
│   └───┴───┴───┴───┴───┴───┴───┴───┘                            │
│       │                                                     │
│       │  hash 冲突时，链表存储                                  │
│       ▼                                                     │
│   ┌───▼───┐                                                   │
│   │ Entry │──→ null                                           │
│   └───┬───┘                                                   │
│       │                                                       │
│       ▼                                                       │
│   ┌───▼───┐                                                   │
│   │ Entry │──→ null                                           │
│   └───┬───┘                                                   │
│       │                                                       │
│       ▼                                                       │
│   ┌───▼───┐                                                   │
│   │ Entry │──→ null                                           │
│   └───┬───┘                                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

JDK 1.8+  ：数组 + 链表/红黑树（链表长度 > 8 时转为红黑树）
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│   table (Node[]数组)                                             │
│   ┌───┬───┬───┬───┬───┬───┬───┬───┐                            │
│   │   │   │   │   │   │   │   │   │                            │
│   └───┴───┴───┴───┴───┴───┴───┴───┘                            │
│       │                                                     │
│       │  链表长度 ≤ 8 时                                       │
│       ▼                                                     │
│   ┌───▼───┐                                                   │
│   │ Node  │──→ Node ──→ Node ──→ null                         │
│   └───┬───┘                                                   │
│                                                                  │
│       │  链表长度 > 8 时，转化为红黑树                          │
│       ▼                                                     │
│   ┌───▼───┐                                                   │
│   │ Node  │                                                   │
│   └───┬───┘                                                   │
│       │                                                       │
│       ▼                                                       │
│   ┌───▼───┐                                                   │
│   │ TreeNode │ ←→ TreeNode ←→ TreeNode                        │
│   └─────────┘   (红黑树结构)                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## HashMap 的核心字段

```java
public class HashMap<K,V> extends AbstractMap<K,V>
        implements Map<K,V>, Cloneable, Serializable {
    
    // 默认初始容量：16
    static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;
    
    // 最大容量：2^30
    static final int MAXIMUM_CAPACITY = 1 << 30;
    
    // 默认负载因子：0.75（当 size > capacity * 0.75 时触发扩容）
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    
    // 链表转红黑树的阈值：8
    static final int TREEIFY_THRESHOLD = 8;
    
    // 红黑树退化为链表的阈值：6
    static final int UNTREEIFY_THRESHOLD = 6;
    
    // 最小树形化容量阈值：64（桶数量 < 64 时优先扩容）
    static final int MIN_TREEIFY_CAPACITY = 64;
    
    // 存储数据的数组
    transient Node<K,V>[] table;
    
    // 键值对数量
    transient int size;
    
    // 结构性修改次数（用于 fail-fast）
    transient int modCount;
    
    // 扩容阈值：capacity * loadFactor
    int threshold;
    
    // 负载因子
    final float loadFactor;
}
```

## 哈希函数的设计

### hash() 方法

```java
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

### 哈希计算图解

```
为什么需要扰动？

key = "Hello"
key.hashCode() = 0xBBF30E1D  （32 位）

原始 hashCode：
┌────────────────────────────────┐
│ 10111011 11110011 00001110 00011101 │
└────────────────────────────────┘
          高16位          低16位

h >>> 16（无符号右移16位）：
┌────────────────────────────────┐
│ 00000000 00000000 10111011 11110011 │
└────────────────────────────────┘

h ^ (h >>> 16)（异或）：
┌────────────────────────────────┐
│ 10111011 11110011 10100101 11101110 │
└────────────────────────────────┘
         混合了高位和低位信息
```

### put() 定位桶位置

```java
// put 方法中的索引计算
int index = (n - 1) & hash  // n 是 table 长度

// 等价于：hash % n（但 & 运算更快）

// 前提：n 必须是 2 的幂次
// 例如 n = 16 (二进制 10000)
//     n - 1 = 15 (二进制 01111)

// 所以 (n - 1) & hash 只会看 hash 的低几位
// 配合扰动函数，将高位信息混合到低位
```

### 为什么容量必须是 2 的幂次？

```
n = 16 (10000)
n - 1 = 15 (01111)

hash = 27 (11011)
     & 01111
     ------
       11011 & 01111 = 01011 = 11

hash = 43 (101011)
     & 01111
     ------
    0101011 & 001111 = 001011 = 11  ← 冲突！

如果不用扰动函数，高位信息丢失，
不同的高位可能产生相同的低位，导致冲突

扰动后：
hash = 27 (11011)  → 扰动后 → 01011 = 11
hash = 43 (101011) → 扰动后 → 11101 = 29  ← 不冲突了！

结论：扰动函数 + 2 的幂次容量 = 减少哈希冲突
```

## put() 方法完整流程

```java
public V put(K key, V value) {
    return putVal(hash(key), key, value, false, true);
}

final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
               boolean evict) {
    
    Node<K,V>[] tab;
    Node<K,V> p;
    int n, i;
    
    // 1. 第一次 put 时，初始化 table
    if ((tab = table) == null || (n = tab.length) == 0) {
        n = (tab = resize()).length;
    }
    
    // 2. 计算索引，如果该桶为空，直接创建新节点
    if ((p = tab[i = (n - 1) & hash]) == null) {
        tab[i] = newNode(hash, key, value, null);
    } else {
        // 3. 该桶已存在（发生 hash 冲突）
        Node<K,V> e; K k;
        
        // 情况 A：第一个节点的 key 相同（hash 相同且 equals 相同）
        if (p.hash == hash && ((k = p.key) == key || key.equals(k))) {
            e = p;
        }
        // 情况 B：该桶是红黑树
        else if (p instanceof TreeNode) {
            e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
        }
        // 情况 C：该桶是链表
        else {
            for (int binCount = 0; ; ++binCount) {
                // 遍历到链表末尾，添加新节点
                if ((e = p.next) == null) {
                    p.next = newNode(hash, key, value, null);
                    
                    // 链表长度 > 8，转化为红黑树
                    if (binCount >= TREEIFY_THRESHOLD - 1) {
                        treeifyBin(tab, hash);
                    }
                    break;
                }
                
                // 遍历过程中发现相同 key，覆盖
                if (e.hash == hash && ((k = e.key) == key || key.equals(k))) {
                    break;
                }
                p = e;
            }
        }
        
        // 4. key 已存在，覆盖旧值
        if (e != null) {
            V oldValue = e.value;
            if (!onlyIfAbsent || oldValue == null) {
                e.value = value;
            }
            // 回调（LinkedHashMap 用）
            afterNodeAccess(e);
            return oldValue;
        }
    }
    
    // 5. 结构性修改
    ++modCount;
    if (++size > threshold) {
        resize();  // 扩容
    }
    afterNodeInsertion(evict);
    return null;
}
```

### put() 流程图

```
                        ┌─────────────────┐
                        │   开始 put      │
                        └────────┬────────┘
                                 │
                        ┌────────▼────────┐
                        │ table == null ? │──Yes──→ resize() 初始化
                        └────────┬────────┘
                                 │ No
                        ┌────────▼────────────────────────┐
                        │ index = (n-1) & hash            │
                        └────────┬────────────────────────┘
                                 │
                        ┌────────▼────────┐
                        │ tab[index]==null│
                        └────────┬────────┘
                    ┌───────┐    │    ┌────────┐
                   Yes      No  │    │ 是链表？│
                    │      │    │    └───┬────┘
                    │      │    │        │
            ┌───────▼───┐  │    │   ┌─────▼─────────────┐
            │ 创建新节点 │  │    │   │ 遍历链表查找 key  │
            │ 直接放入   │  │    │   │   │              │
            └───────────┘  │    │   │  ┌─key已找到─┐   │
                           │    │   │  │覆盖旧值   │   │
              ┌────────────▼────▼───┘   └───────────┘   │
              │                  │            │          │
              │           ┌──────▼──────┐      │          │
              │           │ 是红黑树？   │──────┘          │
              │           └──────┬─────┘                 │
              │                  │                        │
              │         ┌────────▼────────┐               │
              │         │ 树节点插入       │               │
              │         └────────┬────────┘               │
              │                  │                        │
              │         ┌───────▼──────────┐              │
              │         │ 链表尾部添加新节点│              │
              │         │ 长度>8? 树化      │              │
              │         └───────┬──────────┘              │
              │                 │                          │
              └───────┬─────────┘                          │
                      │                                    │
              ┌───────▼──────────┐                        │
              │ size++ > threshold│                       │
              │       │          │                        │
              │  Yes  │  No      │                        │
              │       │  ┌───────▼───────┐               │
              │       │  │ return null   │               │
              │       │  └───────────────┘               │
              │       │                                   │
              │  ┌────▼────┐                             │
              └──│ resize() │                             │
                 └──────────┘                             │
```

## get() 方法完整流程

```java
public V get(Object key) {
    Node<K,V> e;
    return (e = getNode(hash(key), key)) == null ? null : e.value;
}

final Node<K,V> getNode(int hash, Object key) {
    Node<K,V>[] tab;
    Node<K,V> first, e;
    int n; K k;
    
    // 1. 定位到桶
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (first = tab[(n - 1) & hash]) != null) {
        
        // 2. 检查第一个节点
        if (first.hash == hash &&
            ((k = first.key) == key || (key != null && key.equals(k)))) {
            return first;
        }
        
        // 3. 如果有冲突（链表或红黑树）
        if ((e = first.next) != null) {
            // 4. 红黑树查找
            if (first instanceof TreeNode) {
                return ((TreeNode<K,V>)first).getTreeNode(hash, key);
            }
            // 5. 链表遍历查找
            do {
                if (e.hash == hash &&
                    ((k = e.key) == key || (key != null && key.equals(k)))) {
                    return e;
                }
            } while ((e = e.next) != null);
        }
    }
    return null;
}
```

### get() 流程图

```
                        ┌─────────────────┐
                        │   开始 get       │
                        └────────┬────────┘
                                 │
                        ┌────────▼────────────────────────┐
                        │ table[(n-1)&hash] 定位桶        │
                        └────────┬────────────────────────┘
                                 │
                        ┌────────▼────────┐
                        │ 桶是否为空？     │
                        └────────┬────────┘
                      ┌───────┐    │    ┌────────┐
                     Yes      No  │    │ 检查first│
                      │      │    │    │ key匹配?│
              ┌───────▼───┐ │    │    └───┬────┘
              │ return null│ │    │        │
              └───────────┘ │    │   ┌─────▼─────────┐
                           │    │   │ Yes → return  │
              ┌────────────▼────▼───┘   └─────────────┘
              │                  │            │
              │           ┌──────▼──────┐      │
              │           │ next != null│     │
              │           └──────┬──────┘      │
              │                  │             │
              │         ┌────────▼────────┐    │
              │         │  是红黑树?       │    │
              │         └────────┬────────┘    │
              │                  │             │
              │        ┌─────────▼───────┐    │
              │        │ 红黑树查找       │────┘
              │        └─────────────────┘
              │                  │
              │        ┌─────────▼───────┐
              │        │ 链表遍历查找     │────┘
              │        └─────────────────┘
              │
              └─────── return null
```

## hashCode 和 equals 的关系

### 为什么需要 hashCode？

```
hashCode 用于快速定位桶
equals 用于在链表/红黑树中精确匹配 key

好的 hashCode 设计：让元素均匀分布到各个桶
坏的 hashCode 设计：让所有元素都到同一个桶（退化为链表）
```

### 哈希冲突的例子

```java
// String 的 hashCode 实现
public int hashCode() {
    int h = 0;
    for (int i = 0; i < value.length; i++) {
        h = 31 * h + value[i];
    }
    return h;
}

// 假设容量为 16
"Ea".hashCode() = 31*69 + 65 = 2179
"FB".hashCode() = 31*70 + 66 = 2236

(16-1) & 2179 = 3
(16-1) & 2236 = 4  ← 分布在不同桶

// 但如果 hashCode 设计不好
class BadKey {
    int value;
    public int hashCode() { return 0; }  // 所有实例都返回 0！
}

// 所有 BadKey 都会 hash 到同一个桶
// 退化为链表，HashMap 性能退化到 O(n)
```

### 正确重写 hashCode 和 equals

```java
class Person {
    String name;
    int age;
    
    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + age;
        return result;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Person person = (Person) o;
        return age == person.age &&
               Objects.equals(name, person.name);
    }
}
```

### 约定（来自 Java 规范）

```
1. 同一个对象多次调用 hashCode()，返回值必须相同（除非对象被修改）
2. 如果两个对象 equals() 为 true，它们的 hashCode() 必须相同
3. 如果两个对象 equals() 为 false，它们的 hashCode() 可以相同（但应该尽量不同）
```

## 常见面试题

### Q1：HashMap 的底层数据结构？

**答**：JDK 1.7 是数组+链表；JDK 1.8+ 是数组+链表/红黑树，当链表长度超过 8 时自动转为红黑树。

### Q2：HashMap 的 put 流程？

**答**：
1. 计算 key 的 hash 值
2. 定位到数组桶位置
3. 如果桶为空，直接创建节点
4. 如果桶不为空，遍历链表或红黑树
5. 如果 key 已存在，覆盖旧值
6. 如果 key 不存在，插入新节点
7. 如果链表长度 > 8，转化为红黑树
8. 如果 size > threshold，扩容

### Q3：为什么 hashCode 要用扰动函数？

**答**：将 hashCode 的高位信息混合到低位，让元素分布更均匀，减少 hash 冲突。

### Q4：HashMap 为什么容量必须是 2 的幂次？

**答**：配合扰动函数和 (n-1) & hash 计算，可以将 hash 值的高位信息也参与索引计算，减少冲突。

### Q5：HashMap 是线程安全的吗？

**答**：不是。线程安全的 Map 有 Hashtable、Collections.synchronizedMap、ConcurrentHashMap。

---

## 总结

1. **底层结构**：数组+链表（JDK 1.7）/ 数组+链表/红黑树（JDK 1.8+）
2. **hash() 扰动**：混合高位和低位，减少冲突
3. **容量 2 的幂次**：配合扰动函数，优化分布
4. **put 流程**：定位桶 → 遍历/查找 → 插入/覆盖 → 扩容检查
5. **get 流程**：定位桶 → 检查 first → 遍历链表/红黑树查找
6. **hashCode + equals**：定位+精确匹配，两者缺一不可

---

## 下篇预告

下一篇文章我们将深入讲解 **HashMap 扩容机制**，包括：
- 扩容触发条件
- 扩容计算
- 元素迁移过程
- 多线程下的扩容问题

敬请期待！

---

> 📚 **推荐阅读**
> - [HashMap 扩容机制]({{< relref "post/java-collections-07" >}})
> - [HashMap 红黑树原理]({{< relref "post/java-collections-08" >}})
> - [ArrayList vs LinkedList 对比]({{< relref "post/java-collections-05" >}})
