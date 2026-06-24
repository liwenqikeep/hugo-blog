---
title: "Java集合框架(三) LinkedList原理"
date: 2018-01-07
draft: false
categories: ["Java"]
tags: ["LinkedList", "双向链表", "List", "Deque"]
series: ["Java集合系列"]
---

## 前言

上一篇文章我们深入分析了 ArrayList 的原理，它底层是动态数组，适合随机访问。但对于频繁的增删操作，ArrayList 需要移动大量元素，效率较低。

这时 **LinkedList**（双向链表）就派上用场了。这篇文章我们来深入了解 LinkedList 的实现原理。

<!--more-->

## LinkedList 的双重身份

LinkedList 是一个**特殊的存在**，它同时实现了两个接口：

```
┌─────────────────────────────────────────────────────────────────┐
│                        LinkedList                                │
├─────────────────────────────────────────────────────────────────┤
│  • 实现 List 接口   → 作为 List 使用                             │
│  • 实现 Deque 接口  → 作为队列/栈/双端队列使用                    │
└─────────────────────────────────────────────────────────────────┘
```

```java
public class LinkedList<E>
        extends AbstractSequentialList<E>
        implements List<E>, Deque<E>, Cloneable, java.io.Serializable {
    
    // 链表大小
    transient int size = 0;
    
    // 头节点
    transient Node<E> first;
    
    // 尾节点
    transient Node<E> last;
}
```

> **Deque**（Double Ended Queue）是双端队列，支持从两端进行插入和删除操作。

## 双向链表的节点结构

### Node 节点的定义

```java
private static class Node<E> {
    E item;           // 存储的元素
    Node<E> next;      // 指向下一个节点
    Node<E> prev;      // 指向前一个节点
    
    Node(Node<E> prev, E element, Node<E> next) {
        this.item = element;
        this.next = next;
        this.prev = prev;
    }
}
```

### 双向链表示意图

```
                            双向链表结构
    ┌─────────────────────────────────────────────────────────────────┐
    │                                                                  │
    │  ┌──────┐     ┌──────┐     ┌──────┐     ┌──────┐                 │
    │  │ null │ ←── │  A   │ ←── │  B   │ ←── │  C   │                 │
    │  └──────┘     └──────┘     └──────┘     └──────┘                 │
    │                  ──→           ──→           ──→                 │
    │                                                                  │
    │     first ──────────────────────────────→ last                 │
    │                                                                  │
    └─────────────────────────────────────────────────────────────────┘

    每个节点都知道自己的前一个和后一个节点：
    
    Node<A>: prev=null, item=A, next=Node<B>
    Node<B>: prev=Node<A>, item=B, next=Node<C>
    Node<C>: prev=Node<B>, item=C, next=null
```

## 核心方法源码解析

### 添加元素到链表头部

```java
private void linkFirst(E e) {
    // 1. 保存当前的 head 节点
    final Node<E> f = first;
    
    // 2. 创建新节点，新节点的 next 指向原来的 head
    //    Node(前一个节点, 元素, 后一个节点)
    final Node<E> newNode = new Node<>(null, e, f);
    
    // 3. 将 newNode 设为新的 head
    first = newNode;
    
    // 4. 如果原来没有元素，将 newNode 也设为 tail
    if (f == null) {
        last = newNode;
    } else {
        // 否则，将原 head 的 prev 指向新节点
        f.prev = newNode;
    }
    
    // 5. 大小加一
    size++;
    modCount++;
}
```

### 添加元素到链表尾部

```java
void linkLast(E e) {
    // 1. 保存当前的 tail 节点
    final Node<E> l = last;
    
    // 2. 创建新节点，新节点的 prev 指向原来的 tail
    final Node<E> newNode = new Node<>(l, e, null);
    
    // 3. 将 newNode 设为新的 tail
    last = newNode;
    
    // 4. 如果原来没有元素，将 newNode 也设为 head
    if (l == null) {
        first = newNode;
    } else {
        // 否则，将原 tail 的 next 指向新节点
        l.next = newNode;
    }
    
    size++;
    modCount++;
}
```

### 在指定位置插入元素

```java
public void add(int index, E element) {
    // 检查索引是否有效
    checkPositionIndex(index);
    
    if (index == size) {
        // 如果是末尾，直接添加到尾部
        linkLast(element);
    } else {
        // 否则，找到 index 位置的节点，在其前面插入
        linkBefore(element, node(index));
    }
}

// 找到指定位置的节点
Node<E> node(int index) {
    // size >> 1 = size / 2
    // 优化：从离目标更近的一端开始遍历
    if (index < (size >> 1)) {
        // index 在前半部分，从 head 向后遍历
        Node<E> x = first;
        for (int i = 0; i < index; i++) {
            x = x.next;
        }
        return x;
    } else {
        // index 在后半部分，从 tail 向前遍历
        Node<E> x = last;
        for (int i = size - 1; i > index; i--) {
            x = x.prev;
        }
        return x;
    }
}

// 在指定节点前插入新元素
void linkBefore(E e, Node<E> succ) {
    // 1. 保存目标节点的前一个节点
    final Node<E> pred = succ.prev;
    
    // 2. 创建新节点，前指向 pred，后指向 succ
    final Node<E> newNode = new Node<>(pred, e, succ);
    
    // 3. 将 succ 的 prev 指向新节点
    succ.prev = newNode;
    
    // 4. 如果 pred 为 null，说明插入的是头部
    if (pred == null) {
        first = newNode;
    } else {
        // 否则，将 pred 的 next 指向新节点
        pred.next = newNode;
    }
    
    size++;
    modCount++;
}
```

### 删除头节点

```java
private E unlinkFirst(Node<E> f) {
    // 1. 保存要删除节点的元素值
    final E element = f.item;
    
    // 2. 保存 f 的下一个节点
    final Node<E> next = f.next;
    
    // 3. 清理 f 的引用，帮助 GC
    f.item = null;
    f.next = null;
    
    // 4. 将 next 设为新的 head
    first = next;
    
    // 5. 如果 next 为 null，说明链表空了
    if (next == null) {
        last = null;
    } else {
        // 否则，将新 head 的 prev 设为 null
        next.prev = null;
    }
    
    size--;
    modCount++;
    
    return element;
}
```

### 删除指定节点

```java
E unlink(Node<E> x) {
    // 1. 保存要删除节点的值
    final E element = x.item;
    
    // 2. 保存前后节点
    final Node<E> next = x.next;
    final Node<E> prev = x.prev;
    
    // 3. 修改前节点的 next
    if (prev == null) {
        // 如果 prev 为 null，说明是 head
        first = next;
    } else {
        prev.next = next;
        x.prev = null;  // 清理引用
    }
    
    // 4. 修改后节点的 prev
    if (next == null) {
        // 如果 next 为 null，说明是 tail
        last = prev;
    } else {
        next.prev = prev;
        x.next = null;  // 清理引用
    }
    
    // 5. 清理元素引用
    x.item = null;
    
    size--;
    modCount++;
    
    return element;
}
```

### 删除操作图解

```
删除中间的节点 B：

删除前：
    ┌───────┐     ┌───────┐     ┌───────┐
    │  A    │ ←── │  B    │ ←── │  C    │
    └───────┘     └───────┘     └───────┘
    prev  next   prev  next   prev  next

步骤：
1. A.next = B.next → A.next = C
2. C.prev = B.prev → C.prev = A
3. B.item = null, B.next = null, B.prev = null（帮助GC）

删除后：
    ┌───────┐     ┌───────┐
    │  A    │ ←── │  C    │
    └───────┘     └───────┘
```

## LinkedList 作为 Deque 使用

```java
// LinkedList 实现了 Deque<E> 接口，所以可以做这些操作：

LinkedList<String> deque = new LinkedList<>();

// ===== Queue 队列操作 =====
deque.offer("A");      // 入队（尾部）
deque.offer("B");
String head = deque.poll();  // 出队（头部）→ "A"

// ===== Stack 栈操作 =====
deque.push("X");        // 压栈（头部）
deque.push("Y");
String top = deque.pop();    // 弹栈（头部）→ "Y"

// ===== Deque 双端队列操作 =====
deque.addFirst("F");    // 头部添加
deque.addLast("L");     // 尾部添加
String first = deque.removeFirst();  // 头部移除
String last = deque.removeLast();     // 尾部移除
```

## LinkedList 方法时间复杂度

| 操作 | 方法 | 时间复杂度 | 说明 |
|------|------|-----------|------|
| 头部添加 | addFirst() / push() | O(1) | 直接操作 head |
| 尾部添加 | addLast() / offer() | O(1) | 直接操作 tail |
| 头部删除 | removeFirst() / pop() | O(1) | 直接操作 head |
| 尾部删除 | removeLast() | O(1) | 直接操作 tail |
| 按索引访问 | get(int index) | O(n) | 需要遍历链表 |
| 按索引插入/删除 | add(int, E) / remove(int) | O(n) | 需要遍历到 index |
| 按值查找 | contains() / indexOf() | O(n) | 遍历链表 |

## 为什么不提供 getLast() 返回最后一个元素？

等等，LinkedList 实际上**是提供了** `getLast()` 方法的：

```java
public E getLast() {
    final Node<E> l = last;
    if (l == null) {
        throw new NoSuchElementException();
    }
    return l.item;
}
```

由于 LinkedList 维护了 `first` 和 `last` 两个指针，所以获取头尾元素都是 **O(1)**。

## 实战使用场景

```
LinkedList 适合的场景：

✅ 频繁的头部/尾部操作
   - 队列（FIFO）：offer/poll
   - 栈（LIFO）：push/pop
   - 双端队列：addFirst/addLast

✅ 大数据量的中间插入/删除
   - 只需要修改相邻节点的指针
   - 不需要移动元素

❌ 不适合的场景：

❌ 随机访问（get(1000)）   → 每次都要从头/尾遍历
❌ 遍历操作                → ArrayList 更高效
❌ 查找操作（contains）   → 需要遍历
```

### 典型应用：LRU 缓存

```java
// 使用 LinkedList 实现简单的 LRU 缓存
public class LRUCache<K, V> {
    private final int capacity;
    private final LinkedList<Map.Entry<K, V>> cache;
    
    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedList<>();
    }
    
    public V get(K key) {
        for (Map.Entry<K, V> entry : cache) {
            if (entry.getKey().equals(key)) {
                // 移动到最前面（最近使用）
                cache.remove(entry);
                cache.addFirst(entry);
                return entry.getValue();
            }
        }
        return null;
    }
    
    public void put(K key, V value) {
        // 如果已存在，删除
        cache.removeIf(e -> e.getKey().equals(key));
        
        // 添加到最前面
        cache.addFirst(new AbstractMap.SimpleEntry<>(key, value));
        
        // 如果超过容量，删除最后一个（最久未使用）
        if (cache.size() > capacity) {
            cache.removeLast();
        }
    }
}
```

## 常见面试题

### Q1：ArrayList 和 LinkedList 的区别？

| 维度 | ArrayList | LinkedList |
|------|-----------|------------|
| 底层结构 | 动态数组 | 双向链表 |
| 随机访问 | O(1) | O(n) |
| 头尾操作 | O(1) | O(1) |
| 中间插入/删除 | O(n) | O(1)（找到位置后） |
| 内存占用 | 连续，空间利用率高 | 每节点多 2 个指针 |
| 缓存友好性 | ✅ 数组连续 | ❌ 节点分散 |

### Q2：LinkedList 是线程安全的吗？

**答**：不是。LinkedList 的所有操作都没有 synchronized 修饰，不是线程安全的。

### Q3：LinkedList 的 node() 方法为什么从两端遍历？

**答**：这是 JDK 的优化。由于 LinkedList 同时维护了 `first` 和 `last`，通过 `index < size / 2` 判断目标位置离哪端更近，选择从近的一端开始遍历，将查找复杂度从 O(n) 减半。

---

## 总结

1. **双向链表结构**：每个节点有 `prev`、`item`、`next` 三个属性
2. **头尾操作 O(1)**：直接操作 `first` 和 `last` 指针
3. **按索引操作 O(n)**：需要遍历找到目标位置
4. **Deque 双重身份**：既是 List 也是队列/栈
5. **适用场景**：频繁的头尾操作、作为队列/栈使用

---

## 下篇预告

下一篇文章我们将讲解 **fail-fast 机制**，包括：
- modCount 的作用
- 迭代器的实现原理
- ConcurrentModificationException 产生的原因

敬请期待！

---

> 📚 **推荐阅读**
> - [fail-fast 机制详解]({{< relref "post/java-collections-04" >}})
> - [ArrayList vs LinkedList 对比]({{< relref "post/java-collections-05" >}})
> - [ArrayList 原理与扩容]({{< relref "post/java-collections-02" >}})
