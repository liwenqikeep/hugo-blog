---
title: "Java集合框架(二) ArrayList原理与扩容机制"
date: 2018-01-05
draft: false
categories: ["Java"]
tags: ["ArrayList", "集合框架", "扩容机制", "源码分析"]
series: ["Java集合系列"]
toc: true
---

## 前言

上一篇文章我们 overview 了 Java 集合框架的整体架构。从这篇开始，我们深入各个集合的具体实现。

第一个要讲的是 **ArrayList**，这是 Java 中最常用的集合之一。理解 ArrayList 的底层原理，对于写出高效的代码至关重要。

<!--more-->

## ArrayList 底层结构

ArrayList 的底层是一个 **动态数组**。

```
┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │ 9 │  ← 索引（从 0 开始）
├───┼───┼───┼───┼───┼───┼───┼───┼───┼───┤
│ A │ B │ C │ D │ E │ F │ G │ H │ I │ J │  ← 元素
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  ↑
 elementData[0] 存储 A
```

### ArrayList 的核心字段

```java
public class ArrayList<E> extends AbstractList<E>
        implements List<E>, RandomAccess, Cloneable, java.io.Serializable {
    
    // 默认初始容量
    private static final int DEFAULT_CAPACITY = 10;
    
    // 空数组（用于空实例）
    private static final Object[] EMPTY_ELEMENTDATA = {};
    
    // 默认空数组（用于区分是否需要扩容）
    private static final Object[] DEFAULTCAPACITY_EMPTY_ELEMENTDATA = {};
    
    // 存储元素的数组（核心！）
    transient Object[] elementData;
    
    // 实际元素数量
    private int size;
}
```

### 关键设计点

```
1. transient Object[] elementData
   - 使用 transient 修饰，序列化时会过滤空位
   - 只序列化 [0, size) 范围的元素

2. DEFAULT_CAPACITY = 10
   - 无参构造时不会立即分配 10 个空间
   - 第一次 add 时才扩容到 10

3. EMPTY_ELEMENTDATA vs DEFAULTCAPACITY_EMPTY_ELEMENTDATA
   - 区分空 ArrayList 和 需要默认容量的空 ArrayList
   - 用于优化扩容判断
```

## 构造方法解析

```java
// 1. 指定初始容量
public ArrayList(int initialCapacity) {
    if (initialCapacity > 0) {
        // 创建指定大小的数组
        this.elementData = new Object[initialCapacity];
    } else if (initialCapacity == 0) {
        // 空数组
        this.elementData = EMPTY_ELEMENTDATA;
    } else {
        throw new IllegalArgumentException("Illegal Capacity: " + initialCapacity);
    }
}

// 2. 无参构造（重点！）
public ArrayList() {
    // 注意：这里不是 new Object[10]！
    this.elementData = DEFAULTCAPACITY_EMPTY_ELEMENTDATA;
    // 真正分配 10 个空间是在第一次 add 时
}

// 3. 从 Collection 构建
public ArrayList(Collection<? extends E> c) {
    elementData = c.toArray();
    size = elementData.length;
    // 防止 c.toArray() 返回的不是 Object[]
    if (elementData.getClass() != Object[].class) {
        elementData = Arrays.copyOf(elementData, size, Object[].class);
    }
}
```

## add() 方法与扩容机制

### 核心 add() 方法

```java
public boolean add(E e) {
    // 1. 确保容量足够（核心！是否需要扩容在这里判断）
    ensureCapacityInternal(size + 1);
    
    // 2. 将元素添加到数组末尾
    elementData[size++] = e;
    
    return true;
}
```

### 扩容判断详解

```java
// 确保内部容量足够
private void ensureCapacityInternal(int minCapacity) {
    // 计算需要的最小容量
    // 如果是空数组，取 DEFAULT_CAPACITY(10) 和 minCapacity 的较大值
    if (elementData == DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
        minCapacity = Math.max(DEFAULT_CAPACITY, minCapacity);
    }
    
    // 确认是否需要扩容
    ensureExplicitCapacity(minCapacity);
}

private void ensureExplicitCapacity(int minCapacity) {
    // modCount++，用于 fail-fast 机制
    modCount++;
    
    // 如果需要的容量 > 当前数组长度，则扩容
    if (minCapacity - elementData.length > 0) {
        grow(minCapacity);
    }
}
```

### 扩容核心 - grow() 方法

```java
private void grow(int minCapacity) {
    // 当前容量
    int oldCapacity = elementData.length;
    
    // 新容量 = 旧容量 + 旧容量/2 = 旧容量 * 1.5（位运算，等价于 oldCapacity * 1.5）
    int newCapacity = oldCapacity + (oldCapacity >> 1);
    
    // 如果计算出的新容量小于所需最小容量，则使用最小容量
    if (newCapacity - minCapacity < 0) {
        newCapacity = minCapacity;
    }
    
    // 如果新容量超出最大数组限制，处理溢出
    if (newCapacity - MAX_ARRAY_SIZE > 0) {
        newCapacity = hugeCapacity(minCapacity);
    }
    
    // 核心操作：创建新数组并复制元素！
    elementData = Arrays.copyOf(elementData, newCapacity);
}
```

### 扩容图解

```
假设初始容量为 10，添加第 11 个元素时触发扩容：

扩容前（oldCapacity = 10）：
┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ E │ F │ G │ H │ I │ J │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘

扩容计算：newCapacity = 10 + 10/2 = 15

扩容后（newCapacity = 15）：
┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ E │ F │ G │ H │ I │ J │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
                                                    ↑
                                          新增的 5 个空位
```

### 扩容的代价

```
扩容操作的成本很高：
1. 创建新数组（内存分配）
2. 复制所有元素（数组拷贝）

最坏情况：如果不断往 ArrayList 添加元素，
每次扩容都要复制整个数组，时间复杂度接近 O(n)

所以！建议在创建 ArrayList 时预估容量：
```

```java
// ❌ 低效：不断扩容
List<String> list = new ArrayList<>();
for (int i = 0; i < 100000; i++) {
    list.add("item" + i);
}

// ✅ 高效：一步到位
List<String> list = new ArrayList<>(100000);
for (int i = 0; i < 100000; i++) {
    list.add("item" + i);
}
```

## 指定位置添加 add(int index, E element)

```java
public void add(int index, E element) {
    // 1. 检查索引合法性
    rangeCheckForAdd(index);
    
    // 2. 确保容量
    ensureCapacityInternal(size + 1);
    
    // 3. 将 index 及之后的元素向后移动一位
    //    System.arraycopy(源数组, 源起始位置, 目标数组, 目标起始位置, 长度)
    System.arraycopy(elementData, index, elementData, index + 1, size - index);
    
    // 4. 插入新元素
    elementData[index] = element;
    size++;
}
```

### 图解指定位置插入

```
在 index=2 的位置插入 "X"：

插入前：
┌───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ E │ F │
└───┴───┴───┴───┴───┴───┘

步骤 1：移动元素 index=2 及之后的元素
┌───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ D │ E │ F │  ← C 被 D 覆盖了？
└───┴───┴───┴───┴───┴───┴───┘

步骤 2：插入新元素
┌───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ X │ C │ D │ E │ F │
└───┴───┴───┴───┴───┴───┴───┘
```

> **注意**：这里的 `System.arraycopy` 是 native 方法，直接操作内存，是最高效的数组复制方式。

## 常用方法时间复杂度

| 操作 | 方法 | 时间复杂度 | 说明 |
|------|------|-----------|------|
| 在末尾添加 | add(E) | O(1) 均摊 | 偶尔扩容，扩容时 O(n) |
| 在末尾删除 | removeLast() | O(1) | 无参 remove 需要 O(n) |
| 指定位置添加 | add(int, E) | O(n) | 需要移动元素 |
| 指定位置删除 | remove(int) | O(n) | 需要移动元素 |
| 按值查找 | contains() | O(n) | 遍历数组 |
| 按索引访问 | get(int) | O(1) | 直接数组寻址 |
| 按值删除 | remove(E) | O(n) | 先找 index，再移动 |

## 实战优化技巧

### 1. 预估容量，避免扩容

```java
// 从数据库查出 10000 条数据
List<User> users = queryUsers();

// ❌ 每次 add 都可能触发扩容
ArrayList<User> list = new ArrayList<>();

// ✅ 创建时指定容量（capacity 稍微留点余量）
ArrayList<User> list = new ArrayList<>(users.size() * 11 / 10);
list.addAll(users);
```

### 2. 批量操作比单次操作高效

```java
// ❌ 逐个添加
for (User user : users) {
    list.add(user);
}

// ✅ 批量添加（只触发一次扩容）
list.addAll(users);
```

### 3. 不同操作选择不同集合

```java
// 场景 1：主要做遍历和随机访问
List<String> list = new ArrayList<>();
// ✅ ArrayList 随机访问 O(1)

// 场景 2：主要做频繁的中间插入删除
List<String> list = new LinkedList<>();
// ❌ ArrayList 中间插入 O(n)

// 场景 3：需要保证线程安全
List<String> list = Collections.synchronizedList(new ArrayList<>());
// 或使用 CopyOnWriteArrayList
```

### 4. trimToSize() 回收多余空间

```java
// 当 ArrayList 添加大量元素后又删除了很多时
// 可以调用 trimToSize() 回收多余的数组空间

ArrayList<String> list = new ArrayList<>(10000);
list.addAll(millionData);
list.clear();  // 清空数据

// ❌ 此时 elementData.length 仍然是 10000

list.trimToSize();  // 回收多余空间
// ✅ 现在 elementData.length == 0
```

## 常见面试题

### Q1：ArrayList 的初始容量是多少？

**答**：准确说有两个概念：
- 无参构造：`elementData` 初始化为空数组，第一次 add 时才扩容到 10
- 有参构造：如果传 `new ArrayList<>(10)`，直接创建容量为 10 的数组

### Q2：ArrayList 和 LinkedList 的区别？

**答**：

| 维度 | ArrayList | LinkedList |
|------|-----------|------------|
| 底层结构 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 中间插入/删除 | O(n) | O(1) |
| 内存占用 | 连续内存，空间利用率高 | 每个节点需要额外存储前后指针 |
| 线程安全 | 否 | 否 |

### Q3：ArrayList 是线程安全的吗？

**答**：不是。ArrayList 的所有操作都没有 synchronized 修饰，不是线程安全的。如果需要线程安全：
- `Collections.synchronizedList(new ArrayList<>())`
- `CopyOnWriteArrayList`（适合读多写少场景）

---

## 总结

1. **底层是动态数组**：通过 `Object[] elementData` 存储元素
2. **扩容公式**：`newCapacity = oldCapacity + oldCapacity / 2`（1.5 倍）
3. **扩容代价高**：需要创建新数组并复制所有元素
4. **实战建议**：预估容量，避免频繁扩容

---

## 下篇预告

下一篇文章我们将讲解 **LinkedList 原理**，包括：
- 双向链表的节点结构
- add/remove/get 方法的源码解读
- LinkedList 如何实现 List 和 Deque 接口

敬请期待！

---

> 📚 **推荐阅读**
> - [LinkedList 原理]({{< relref "post/java-collections-03" >}})
> - [fail-fast 机制详解]({{< relref "post/java-collections-04" >}})
> - [Java 集合框架全景图]({{< relref "post/java-collections-01" >}})
