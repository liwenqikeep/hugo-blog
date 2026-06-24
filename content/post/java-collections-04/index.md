---
title: "Java集合框架(四) fail-fast机制详解"
date: 2018-01-09
draft: false
categories: ["Java"]
tags: ["fail-fast", "modCount", "ConcurrentModificationException", "迭代器"]
series: ["Java集合系列"]
toc: true
---

## 前言

在使用 Java 集合的迭代器遍历时，你一定遇到过这个异常：

```
java.util.ConcurrentModificationException
```

这就是 **fail-fast（快速失败）** 机制在起作用。这篇文章我们来深入理解它的原理、作用和如何避免。

<!--more-->

## 什么是 fail-fast？

fail-fast 是 Java 集合框架的一种**错误检测机制**。

当你在迭代遍历集合的过程中，如果检测到集合被**结构性修改**（添加、删除元素），就会立即抛出 `ConcurrentModificationException`，而不是等到迭代结束才发现问题。

### 结构性修改 vs 非结构性修改

```
结构性修改：
  • add()、remove()、clear() 等改变集合大小的操作
  • 集合的内部结构发生了变化

非结构性修改：
  • set() 替换已有元素（不改变集合大小）
  • 修改集合中某个元素的属性（如果是可变对象）
```

### 触发 fail-fast 的经典场景

```java
List<String> list = new ArrayList<>();
list.add("A");
list.add("B");
list.add("C");

// 用 foreach 遍历（底层就是迭代器）
for (String s : list) {
    if ("B".equals(s)) {
        list.remove(s);  // ❌ ConcurrentModificationException！
    }
}
```

## modCount 计数器

fail-fast 的实现依赖于 **modCount** 这个字段。

```java
// AbstractList 中的定义
protected transient int modCount = 0;
```

### modCount 的语义

```
modCount：记录集合被结构性修改的次数

• 每次 add()、remove()、clear() → modCount++
• 每次迭代器创建时 → 记录初始 modCount（expectedModCount）
• 每次 next()/remove() 时 → 检查 modCount == expectedModCount
```

### modCount 变化的时机

```java
// ArrayList.add() 方法
public boolean add(E e) {
    ensureCapacityInternal(size + 1);  // 可能触发扩容
    elementData[size++] = e;
    return true;
}

// 最终调用的是 AbstractList 的以下方法
protected void modCount++  // 每次结构性修改
```

## 迭代器的实现原理

### ArrayList 的迭代器 - Itr

```java
// ArrayList.iterator() 返回的迭代器
private class Itr implements Iterator<E> {
    int cursor;          // 下一个要返回的元素的索引
    int lastRet = -1;    // 上一次返回的元素的索引，-1 表示没有
    int expectedModCount; // 创建迭代器时记录的 modCount
    
    Itr() {
        // 创建迭代器时，保存当前的 modCount
        expectedModCount = modCount;
    }
    
    public boolean hasNext() {
        return cursor != size;
    }
    
    @SuppressWarnings("unchecked")
    public E next() {
        // 核心检查！
        checkForComodification();
        
        int i = cursor;
        if (i >= size) {
            throw new NoSuchElementException();
        }
        Object[] elementData = ArrayList.this.elementData;
        if (i >= elementData.length) {
            throw new ConcurrentModificationException();
        }
        cursor = i + 1;
        return (E) elementData[lastRet = i];
    }
    
    // fail-fast 检查
    final void checkForComodification() {
        if (modCount != expectedModCount) {
            throw new ConcurrentModificationException();
        }
    }
    
    public void remove() {
        if (lastRet < 0) {
            throw new IllegalStateException();
        }
        checkForComodification();
        
        try {
            ArrayList.this.remove(lastRet);
            cursor = lastRet;
            lastRet = -1;
            // 关键！同步 expectedModCount
            expectedModCount = modCount;
        } catch (IndexOutOfBoundsException ex) {
            throw new ConcurrentModificationException();
        }
    }
}
```

### 关键流程图

```
创建迭代器时：
┌─────────────────────────────────────────────────┐
│  expectedModCount = modCount（当前值）          │
│  例如：expectedModCount = 5                      │
└─────────────────────────────────────────────────┘

迭代过程中：
┌─────────────────────────────────────────────────┐
│  next() 调用 checkForComodification()           │
│  if (modCount != expectedModCount)              │
│      throw ConcurrentModificationException       │
└─────────────────────────────────────────────────┘

正确删除后：
┌─────────────────────────────────────────────────┐
│  remove() 方法内部：                             │
│  expectedModCount = modCount（同步）            │
│  所以不会抛出异常                                │
└─────────────────────────────────────────────────┘
```

## 为什么会抛出异常？

### 场景还原

```java
List<String> list = new ArrayList<>();
list.add("A");  // modCount = 1
list.add("B");  // modCount = 2
list.add("C");  // modCount = 3

Iterator<String> it = list.iterator();
// 此时：expectedModCount = modCount = 3

// foreach 底层调用 it.next()
for (String s : list) {
    if ("B".equals(s)) {
        list.remove(s);  // modCount = 4（结构性修改！）
        // 但 expectedModCount 仍然是 3
    }
}

// 下一次调用 it.next() 时
it.next();
// checkForComodification()
// if (4 != 3) → throw ConcurrentModificationException
```

### 示意图

```
                    modCount vs expectedModCount

  ┌─────────────────────────────────────────────────────────┐
  │                                                          │
  │  list.add("A")    list.add("B")    list.add("C")         │
  │       ↓               ↓               ↓                │
  │  modCount=1       modCount=2       modCount=3           │
  │                                                          │
  │  创建迭代器：expectedModCount = modCount = 3             │
  │                                                          │
  │  list.remove("B")                                       │
  │       ↓                                                  │
  │  modCount=4   ← 改变了！                                 │
  │                                                          │
  │  下次 next() 时：                                        │
  │  if (modCount != expectedModCount)                      │
  │  if (4 != 3) → ConcurrentModificationException           │
  │                                                          │
  └─────────────────────────────────────────────────────────┘
```

## 正确删除元素的方式

### 方式一：使用迭代器的 remove()

```java
List<String> list = new ArrayList<>();
list.add("A");
list.add("B");
list.add("C");

Iterator<String> it = list.iterator();
while (it.hasNext()) {
    String s = it.next();
    if ("B".equals(s)) {
        it.remove();  // ✅ 正确：迭代器自己的 remove 方法
    }
}
// list: ["A", "C"]
```

### 方式二：使用 removeIf()（JDK 8+）

```java
List<String> list = new ArrayList<>();
list.add("A");
list.add("B");
list.add("C");

// ✅ JDK 8+ 推荐方式
list.removeIf(s -> "B".equals(s));
// list: ["A", "C"]
```

### 方式三：倒序遍历避免索引问题

```java
List<String> list = new ArrayList<>();
list.add("A");
list.add("B");
list.add("C");

// ✅ 倒序遍历，删除元素不影响前面的索引
for (int i = list.size() - 1; i >= 0; i--) {
    if ("B".equals(list.get(i))) {
        list.remove(i);
    }
}
```

### 方式四：使用 CopyOnWriteArrayList

```java
// 如果需要在遍历时删除，使用并发集合
List<String> list = new CopyOnWriteArrayList<>();
list.add("A");
list.add("B");
list.add("C");

for (String s : list) {
    if ("B".equals(s)) {
        list.remove(s);  // ✅ CopyOnWriteArrayList 不会抛异常
    }
}
```

## modCount 的使用位置

modCount 定义在 **AbstractCollection** 和 **AbstractList** 中：

```
AbstractCollection
    └── AbstractList (modCount 在这里定义)
            ├── ArrayList
            ├── LinkedList
            ├── Vector
            └── ...
```

### 哪些操作会修改 modCount？

```java
// AbstractList.java
protected transient int modCount = 0;

// 结构性修改会 modCount++
public void add(int index, E element) { modCount++; }
public E remove(int index) { modCount++; }
public void clear() { modCount++; }
```

### 哪些操作不会修改 modCount？

```java
// 非结构性修改，不改变 modCount
public E set(int index, E element) { }  // 替换，不改变大小
public E get(int index) { }             // 获取，不改变结构
```

## fail-safe vs fail-fast

| 特性 | fail-fast | fail-safe |
|------|-----------|-----------|
| 异常 | ConcurrentModificationException | 不抛异常 |
| 原理 | 检测 modCount | 复制原集合遍历 |
| 内存 | 不需要额外内存 | 需要复制集合 |
| 一致性 | 遍历时无法感知修改 | 可能遍历到旧数据 |
| 典型实现 | ArrayList、HashMap | CopyOnWriteArrayList、ConcurrentHashMap |

### fail-safe 示例

```java
// CopyOnWriteArrayList 的迭代器遍历的是一份快照
CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
list.add("A");
list.add("B");

Iterator<String> it = list.iterator();
list.add("C");  // 修改了原集合

while (it.hasNext()) {
    System.out.println(it.next());  // 仍然只输出 A、B
    // 不会输出 C，因为迭代器遍历的是创建时的快照
}
```

## 常见面试题

### Q1：fail-fast 机制是什么？

**答**：fail-fast 是 Java 集合的一种错误检测机制。当迭代器遍历集合时，如果检测到集合被结构性修改，会立即抛出 ConcurrentModificationException。实现依赖于 modCount 计数器，每次结构性修改 modCount++，迭代器创建时记录 expectedModCount，遍历时比较两者是否相等。

### Q2：如何避免 ConcurrentModificationException？

**答**：几种方式：
1. 使用迭代器的 remove() 方法删除元素
2. 使用 JDK 8+ 的 removeIf() 方法
3. 倒序遍历删除
4. 使用 CopyOnWriteArrayList 等 fail-safe 集合

### Q3：modCount 为什么要用 transient 修饰？

**答**：modCount 是为了支持 fail-fast 机制，不参与序列化。transient 表示该字段不参与序列化，反序列化后 modCount 会被重置为 0，这符合设计意图（反序列化后的集合是"新"的）。

### Q4：foreach 和 iterator 的关系？

**答**：
```java
// 编译后等价于：
for (String s : list) {
    // ...
}

// 编译后的代码：
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    String s = it.next();
    // ...
}
```

---

## 总结

1. **fail-fast**：结构性修改集合时，迭代器检测到会抛异常
2. **modCount**：记录结构性修改次数的计数器
3. **expectedModCount**：迭代器创建时记录的 modCount 快照
4. **正确删除**：使用迭代器的 remove() 或 removeIf()
5. **fail-safe**：复制原集合遍历，不抛异常但可能遍历到旧数据

---

## 下篇预告

下一篇文章我们将对比分析 **ArrayList vs LinkedList**，包括：
- 底层数据结构对比
- 增删查改性能对比
- 实战场景选型建议

敬请期待！

---

> 📚 **推荐阅读**
> - [ArrayList vs LinkedList 对比]({{< relref "post/java-collections-05" >}})
> - [HashMap 核心原理]({{< relref "post/java-collections-06" >}})
> - [LinkedList 原理]({{< relref "post/java-collections-03" >}})
