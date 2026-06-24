---
title: "Java集合框架(五) ArrayList vs LinkedList 深度对比"
date: 2018-01-11
draft: false
categories: ["Java"]
tags: ["ArrayList", "LinkedList", "性能对比", "选型"]
series: ["Java集合系列"]
[params.toc]
  startLevel = 2
  endLevel = 3
---

## 前言

前几篇文章我们分别深入分析了 ArrayList 和 LinkedList 的原理。这篇文章我们来做一次全面的对比，帮助你在实际开发中做出正确的选择。

<!--more-->

## 底层数据结构对比

```
┌─────────────────────────────────────────────────────────────────┐
│                        ArrayList                                │
│                    动态数组（Object[]）                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐                     │
│  │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │ 9 │                     │
│  ├───┼───┼───┼───┼───┼───┼───┼───┼───┼───┤                     │
│  │ A │ B │ C │ D │ E │ F │ G │ H │ I │ J │                     │
│  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘                     │
│                                                                  │
│  • 内存连续                                                     │
│  • 索引直接寻址：elementData[index]                             │
│  • 随机访问 O(1)                                                 │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        LinkedList                                │
│                    双向链表（Node）                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  first                                                           │
│    ↓                                                             │
│  ┌────┐    ┌────┐    ┌────┐    ┌────┐                          │
│  │ A  │ ←→ │ B  │ ←→ │ C  │ ←→ │ D  │                          │
│  └────┘    └────┘    └────┘    └────┘                          │
│    ↑                                                             │
│  last                                                            │
│                                                                  │
│  • 内存不连续（每个节点独立）                                     │
│  • 通过指针相连                                                   │
│  • 随机访问需要遍历                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## 性能对比一览

| 操作 | ArrayList | LinkedList | 胜者 |
|------|-----------|------------|------|
| 随机访问 get(int) | **O(1)** | O(n) | ArrayList |
| 尾部添加 add(E) | **O(1)** 均摊 | O(1) | 平手 |
| 头部添加 addFirst | O(n) | **O(1)** | LinkedList |
| 尾部添加 addLast | **O(1)** | O(1) | 平手 |
| 中间插入 add(int, E) | O(n) | **O(1)** 找到位置后 | LinkedList |
| 按索引删除 remove(int) | O(n) | **O(1)** 找到位置后 | LinkedList |
| 按值删除 remove(Object) | O(n) | O(n) | 平手 |
| 迭代器遍历 | **O(1)** 每步 | **O(1)** 每步 | 平手 |

## 随机访问性能对比

### ArrayList 的 O(1) 访问

```java
// ArrayList.get(index)
public E get(int index) {
    rangeCheck(index);
    return elementData[index];  // 直接寻址，一步到位
}

// 内存视角：
// elementData[0x1000 + index * 8] = elementData[0x1000 + 5 * 8]
// CPU 一次指令就能拿到数据
```

### LinkedList 的 O(n) 访问

```java
// LinkedList.get(index)
public E get(int index) {
    checkElementIndex(index);
    return node(index).item;  // 需要遍历找到节点
}

Node<E> node(int index) {
    // 优化：从近的一端开始
    if (index < size / 2) {
        Node<E> x = first;
        for (int i = 0; i < index; i++) {
            x = x.next;  // 每次需要指针解引用
        }
        return x;
    } else {
        Node<E> x = last;
        for (int i = size - 1; i > index; i--) {
            x = x.prev;
        }
        return x;
    }
}
```

### 性能差异有多大？

```
访问 ArrayList vs LinkedList 第 50000 个元素：

ArrayList:
  CPU 指令：MOV EAX, [array + index * 8]
  执行时间：~1 纳秒

LinkedList:
  指针遍历：50000 次指针解引用
  执行时间：~50-100 微秒

差距：约 50000-100000 倍！
```

## 插入/删除性能对比

### ArrayList 中间插入

```
在 index=3 插入 "X"：

插入前：          插入后：
┌───┬───┬───┬───┬───┬───┐   ┌───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ E │ F │   │ A │ B │ C │ X │ D │ E │ F │
└───┴───┴───┴───┴───┴───┘   └───┴───┴───┴───┴───┴───┴───┘
                              ↑
                              需要移动 D、E、F 三个元素

时间复杂度：O(n) - 需要移动 n-index 个元素
```

### LinkedList 中间插入

```
在 index=3 插入 "X"：

插入前：                          插入后：
┌────┐   ┌────┐   ┌────┐         ┌────┐   ┌────┐   ┌────┐
│ C  │ ←→│ D  │ ←→│ E  │   →     │ C  │ ←→│ X  │ ←→│ D  │ ←→│ E  │
└────┘   └────┘   └────┘         └────┘   └────┘   └────┘
         ↑                              ↑
      只需要修改 C→D 和 D→E 的指针

时间复杂度：O(n) 找到位置 + O(1) 修改指针
```

### 关键结论

```
LinkedList 的"优势"是建立在能找到位置的前提下：

✅ 头部插入/删除：O(1) + O(1) = O(1)
✅ 尾部插入/删除：O(1) + O(1) = O(1)
❌ 中间插入/删除：O(n) 找到 + O(1) 修改 = O(n)

所以，LinkedList 的优势场景是：
  • 头部操作（栈、队列）
  • 尾部操作
  • 已知位置的批量操作
```

## 内存占用对比

### ArrayList

```
假设存储 10 个 String 引用：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│   │   │   │   │   │   │   │   │   │   │   │   │   │   │   │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
一个数组对象，10 个引用（40/80 字节，取决于 32/64 位 JVM）

内存开销：
• 数组对象头：12-16 字节
• 每个引用：4/8 字节
• 总计：约 56-96 字节（不含实际对象）
```

### LinkedList

```
存储 10 个 String：

┌────┐    ┌────┐    ┌────┐    ┌────┐
│ref1│ ←→ │ref2│ ←→ │ref3│ ←→ │ref4│ ...
└────┘    └────┘    └────┘    └────┘
  ↑          ↑          ↑
 prev      prev       prev
  ↓          ↓          ↓
 null    ┌────┐    ┌────┐
        │ref1│ ←→ │ref2│
        └────┘    └────┘

每个节点 Node：
• item（引用）：4/8 字节
• prev（引用）：4/8 字节
• next（引用）：4/8 字节
• Node 对象头：12-16 字节
• 每个节点：约 32-48 字节

10 个节点：约 320-480 字节
```

### 内存对比结论

```
ArrayList vs LinkedList 内存占用：

ArrayList：连续内存，10 个引用 ≈ 56-96 字节
LinkedList：10 个节点，每个 32-48 字节 ≈ 320-480 字节

LinkedList 的内存开销是 ArrayList 的 5-6 倍！

原因：每个节点都需要额外的 prev 和 next 指针
```

## CPU 缓存友好性

### ArrayList - 缓存友好

```
ArrayList 的数组是连续内存：

┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
│ A │ B │ C │ D │ E │ F │ G │ H │ I │ J │
└───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  ↑
  遍历时，CPU 会预加载相邻的内存（缓存行）
  访问 A 时，B、C、D 可能已经被加载到缓存
  遍历效率极高
```

### LinkedList - 缓存不友好

```
LinkedList 的节点分散在内存各处：

┌────┐     ┌────┐     ┌────┐     ┌────┐
│ A  │ ←→  │ B  │ ←→  │ C  │ ←→  │ D  │
└────┘     └────┘     └────┘     └────┘
  ↑           ↑           ↑           ↑
0x1000     0x5000     0x2000     0x8000   ← 随机地址
  ↑                                   ↑
每次访问下一个节点，都可能触发缓存未命中（Cache Miss）
CPU 需要重新从主存加载数据
```

### 实际影响

```
遍历 100 万个元素的集合：

ArrayList：
  • 耗时：约 10-20 毫秒
  • 原因：充分利用 CPU 缓存，预取效率高

LinkedList：
  • 耗时：约 200-500 毫秒
  • 原因：频繁 Cache Miss，主存访问延迟高

差距：约 10-50 倍！
```

## 实战选型指南

```
┌─────────────────────────────────────────────────────────────────┐
│                     ArrayList vs LinkedList                      │
│                      实战场景选型                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  选择 ArrayList：                                                 │
│  ✅ 随机访问多（get, set）                                        │
│  ✅ 遍历操作多                                                    │
│  ✅ 尾部添加为主                                                  │
│  ✅ 大部分场景的默认选择                                          │
│                                                                  │
│  选择 LinkedList：                                                │
│  ✅ 栈/队列实现（头尾操作多）                                      │
│  ✅ 大数据量的中间增删（需配合 index 访问）                        │
│  ✅ 内存敏感场景除外                                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 具体场景分析

#### 场景 1：分页列表展示

```java
// ❌ 不推荐：LinkedList
List<User> users = new LinkedList<>();
// 每次 getPage(i) 都要遍历

// ✅ 推荐：ArrayList
List<User> users = new ArrayList<>();
// getPage(i) 直接定位，O(1)
```

#### 场景 2：消息队列（FIFO）

```java
// ✅ 推荐：LinkedList（作为 Queue 使用）
Queue<Message> queue = new LinkedList<>();
queue.offer(msg);      // 尾部添加
Message msg = queue.poll();  // 头部取走

// ArrayList 也能做，但头部操作 O(n)
```

#### 场景 3：历史记录（栈结构）

```java
// ✅ 推荐：LinkedList（作为 Deque 使用）
Deque<String> history = new LinkedList<>();
history.push(url);           // 压栈
String last = history.pop(); // 弹栈
```

#### 场景 4：频繁中间插入

```java
// 场景：从文件逐行读取，按条件过滤后存入列表
List<LogEntry> logs = new ArrayList<>();  // ❌ 每次中间插入 O(n)

List<LogEntry> logs = new LinkedList<>();  // ✅ 只需 add() 追加

// 等等！实际情况是：
logs.add(logEntry);  // LinkedList 默认是尾部追加！
// 所以 ArrayList 更快！
```

> **重要**：LinkedList 的中间插入优势只有在**按 index 插入**时才明显。如果是尾部追加，ArrayList 更优。

## 性能测试对比

```java
public class ListPerformanceTest {
    
    private static final int N = 100_000;
    
    public static void main(String[] args) {
        // ============ 随机访问测试 ============
        testRandomAccess(new ArrayList<>(), "ArrayList 随机访问");
        testRandomAccess(new LinkedList<>(), "LinkedList 随机访问");
        
        // ============ 尾部追加测试 ============
        testAddLast(new ArrayList<>(), "ArrayList 尾部追加");
        testAddLast(new LinkedList<>(), "LinkedList 尾部追加");
        
        // ============ 头部插入测试 ============
        testAddFirst(new ArrayList<>(), "ArrayList 头部插入");
        testAddFirst(new LinkedList<>(), "LinkedList 头部插入");
        
        // ============ 遍历测试 ============
        testTraversal(new ArrayList<>(), "ArrayList 遍历");
        testTraversal(new LinkedList<>(), "LinkedList 遍历");
    }
    
    static void testRandomAccess(List<Integer> list, String name) {
        // 预填充
        for (int i = 0; i < N; i++) list.add(i);
        
        long start = System.nanoTime();
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += list.get(i);  // 随机访问
        }
        long time = System.nanoTime() - start;
        System.out.printf("%s: %,d ns%n", name, time);
    }
    
    static void testAddLast(List<Integer> list, String name) {
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            list.add(i);  // 尾部追加
        }
        long time = System.nanoTime() - start;
        System.out.printf("%s: %,d ns%n", name, time);
    }
    
    static void testAddFirst(List<Integer> list, String name) {
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            list.add(0, i);  // 头部插入
        }
        long time = System.nanoTime() - start;
        System.out.printf("%s: %,d ns%n", name, time);
    }
    
    static void testTraversal(List<Integer> list, String name) {
        for (int i = 0; i < N; i++) list.add(i);
        
        long start = System.nanoTime();
        int sum = 0;
        for (Integer i : list) {
            sum += i;  // 迭代器遍历
        }
        long time = System.nanoTime() - start;
        System.out.printf("%s: %,d ns%n", name, time);
    }
}
```

### 预期结果

```
ArrayList 随机访问: ~500,000 ns
LinkedList 随机访问: ~50,000,000 ns  (100倍差距！)

ArrayList 尾部追加: ~15,000,000 ns
LinkedList 尾部追加: ~20,000,000 ns  (ArrayList 更快！)

ArrayList 头部插入: ~800,000,000 ns  (太慢了！)
LinkedList 头部插入: ~15,000,000 ns  (50倍优势)

ArrayList 遍历: ~12,000,000 ns
LinkedList 遍历: ~80,000,000 ns  (7倍差距)
```

## 常见面试题

### Q1：ArrayList 和 LinkedList 的区别？

**答**：

| 维度 | ArrayList | LinkedList |
|------|-----------|------------|
| 底层结构 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 头尾操作 | 尾部 O(1)，头部 O(n) | 都是 O(1) |
| 中间操作 | O(n) 移动元素 | O(n) 找位置 + O(1) 修改 |
| 内存占用 | 低（连续内存） | 高（每节点多 2 个指针） |
| 缓存友好 | ✅ 是 | ❌ 否 |
| 线程安全 | 否 | 否 |

### Q2：ArrayList 一定比 LinkedList 好吗？

**答**：不是。它们各有优势：
- **ArrayList**：随机访问、遍历、尾部追加
- **LinkedList**：头尾操作、已知位置的增删

大部分业务场景用 ArrayList。

### Q3：为什么 LinkedList 的尾部追加反而比 ArrayList 慢？

**答**：JVM 的优化和 CPU 缓存效应。ArrayList 虽然偶尔扩容，但数组的连续内存特性让它整体性能更优。只有在需要频繁头部操作时，LinkedList 才有明显优势。

---

## 总结

1. **随机访问**：ArrayList O(1) vs LinkedList O(n)，差距巨大
2. **尾部追加**：两者都是 O(1)，实际 ArrayList 更快
3. **头部操作**：LinkedList O(1) vs ArrayList O(n)
4. **中间操作**：都需要 O(n) 找位置，LinkedList 稍优
5. **内存占用**：LinkedList 是 ArrayList 的 5-6 倍
6. **缓存友好**：ArrayList 完胜
7. **默认选择**：除非明确需要头尾操作，否则用 ArrayList

---

## 下篇预告

下一篇文章我们将进入 **Map 专题**，讲解 **HashMap 核心原理**，包括：
- put/get 方法的完整流程
- 哈希函数的设计
- equals 和 hashCode 的关系

敬请期待！

---

> 📚 **推荐阅读**
> - [HashMap 核心原理]({{< relref "post/java-collections-06" >}})
> - [HashMap 扩容机制]({{< relref "post/java-collections-07" >}})
> - [fail-fast 机制详解]({{< relref "post/java-collections-04" >}})
