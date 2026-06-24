---
title: "Java集合框架(八) HashMap红黑树原理"
date: 2018-01-17
draft: false
categories: ["Java"]
tags: ["HashMap", "红黑树", "TreeNode", "树化", "退化"]
series: ["Java集合系列"]
[params.toc]
  startLevel = 2
  endLevel = 3
---

## 前言

上一篇文章我们分析了 HashMap 的扩容机制，提到了 JDK 1.8 引入的红黑树优化。这篇文章我们来深入理解 HashMap 中红黑树的实现原理。

<!--more-->

## 为什么要引入红黑树？

### 链表的问题

```
场景：hash 冲突严重，某个桶的链表很长

链表查找：O(n)
        ┌───┐
        │ A │ → B → C → D → E → F → G → H → I → J
        └───┘
             ↑
        查找 J 需要遍历 10 个节点

当链表长度很长时，查找性能退化严重
```

### 红黑树的优势

```
红黑树查找：O(log n)

        ┌───┐
        │ E │  ← 根节点
        └─┬─┘
      ┌───┴───┐
      │       │
    ┌─┴─┐   ┌─┴─┐
    │ B │   │ G │  ← 平衡二叉树
    └─┬─┘   └─┬─┘
      │       │
    ┌─┴─┐   ┌─┴─┐
    │ A │   │ I │
    └───┘   └───┘

查找 J：E → G → I → J，只需要 4 步！
```

## 树化的条件

### 两个阈值

```java
// 链表转红黑树
static final int TREEIFY_THRESHOLD = 8;

// 红黑树退化为链表
static final int UNTREEIFY_THRESHOLD = 6;

// 最小树形化容量（桶数量）
static final int MIN_TREEIFY_CAPACITY = 64;
```

### 树化条件详解

```
treeifyBin() 触发条件：

┌─────────────────────────────────────────────────────────────┐
│                                                              │
│  条件 1：链表长度 > 8                                         │
│                                                              │
│  条件 2：桶数量 >= 64                                        │
│                                                              │
│  两个条件都满足时才树化                                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘

为什么不满足条件 2 时不树化？

因为当桶数量较少时（< 64），优先选择扩容而不是树化
扩容可以将冲突的节点分散到新的桶中
```

### 为什么是 8 和 6？

```
这是一个统计优化的结果

链表长度分布（泊松分布）：
• length=8 时概率约为 0.00000006（极低）
• 意味着超过 8 个节点的冲突极其罕见

为什么退化成 6 而不是 7？

避免在阈值附近反复树化/退化：
• 链表长度 = 7 时，如果再添加一个 → 树化
• 链表长度 = 7 时，如果删除一个 → 退化
• 这样会导致频繁转换
• 所以用 6 和 8 留出缓冲

  链表：长度 <= 6
  红黑树：长度 >= 8
  过渡区：长度 = 7（保持原样）
```

## TreeNode 节点结构

```java
static final class TreeNode<K,V> extends Node<K,V> {
    TreeNode<K,V> parent;   // 父节点
    TreeNode<K,V> left;     // 左子节点
    TreeNode<K,V> right;    // 右子节点
    TreeNode<K,V> prev;     // 前一个节点（用于链表）
    boolean red;            // 节点颜色
    
    // ...
}
```

### 双重身份

```
TreeNode 既是一棵二叉树节点，也是一个链表节点：

链表视角：
┌───┐   ┌───┐   ┌───┐   ┌───┐
│ A │ → │ B │ → │ C │ → │ D │  ← prev/next 指针
└───┘   └───┘   └───┘   └───┘

树视角：
        ┌───┐
        │ B │  ← root
        └─┬─┘
      ┌───┴───┐
      │       │
    ┌─┴─┐   ┌─┴─┐
    │ A │   │ D │
    └───┘   └───┘

这样在退化为链表时，可以快速还原
```

## 红黑树的特性

### 五大规则

```
1. 每个节点要么是红色，要么是黑色

2. 根节点是黑色

3. 所有叶子节点（NIL/空节点）是黑色

4. 红色节点的子节点必须是黑色
   （即：红色节点不能连续）

5. 从任一节点到其所有叶子节点的路径上，
   黑色节点的数量相同
   （即：黑色高度相同）
```

### 特性图解

```
正确示例：                    错误示例：

        黑(B)                        黑(B)
       /      \                     /      \
    红(R)    红(R)               红(R)    红(R)
    /   \      \                 /   \      \
黑(G)  黑(G)  黑(G)            红(R) 黑(G) 黑(G)  ← 红红连续！错误！
   ↓      ↓     ↓
叶子    叶子  叶子

B(黑) 到每个叶子路径上的黑节点数 = 2
```

### 这些特性保证了什么？

```
结论：从根到最远叶子的路径长度 不超过 从根到最近叶子的路径长度的两倍

这保证了红黑树的大致平衡 → 查找复杂度 O(log n)

最坏情况（全是红黑交替）：
路径长度 = 2 * 黑色节点数

最好情况（全黑）：
路径长度 = 黑色节点数
```

## 树化过程

### treeifyBin() 方法

```java
final void treeifyBin(Node<K,V>[] tab, int hash) {
    int n, index;
    Node<K,V> e;
    
    // 如果桶数量 < 64，优先扩容
    if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY) {
        resize();
    }
    // 否则，链表转红黑树
    else if ((e = tab[index = (n - 1) & hash]) != null) {
        TreeNode<K,V> hd = null, tl = null;
        
        // 1. 先构建链表（有序）
        do {
            TreeNode<K,V> p = replacementTreeNode(e, null);
            if (tl == null) {
                hd = p;
            } else {
                p.prev = tl;
                tl.next = p;
            }
            tl = p;
        } while ((e = e.next) != null);
        
        // 2. 将链表转为红黑树
        if ((tab[index] = hd) != null) {
            hd.treeify();
        }
    }
}
```

### 红黑树的插入

```java
// TreeNode.putTreeVal()
final TreeNode<K,V> putTreeVal(HashMap<K,V> map, Node<K,V>[] tab,
                               int h, K k, V v) {
    TreeNode<K,V> p = this;
    TreeNode<K,V> parent = null;
    int dir = 0;  // 方向：-1=左，1=右
    
    // 1. 找到插入位置
    do {
        parent = p;
        int ph = p.hash;
        int cmp;
        K pk = p.key;
        
        // hash 大，往右
        if (h > ph) {
            dir = 1;
        }
        // hash 小，往左
        else if (h < ph) {
            dir = -1;
        }
        // hash 相同，用 equals
        else if ((pk == null && k == null) ||
                 (pk != null && pk.equals(k))) {
            return p;
        }
        // hash 相同但 equals 不同，尝试比较 Comparable
        else if ((pk != null && k != null) &&
                 (k.getClass() == pk.getClass() ||
                  k instanceof Comparable &&
                  (cmp = ((Comparable)k).compareTo(pk)) != 0)) {
            dir = cmp;
        }
        // 无法比较，用 tieBreakOrder
        else {
            dir = tieBreakOrder(k, pk);
        }
        
        p = (dir <= 0) ? p.left : p.right;
        
    } while (p != null);
    
    // 2. 创建新节点（默认为红色）
    TreeNode<K,V> f = (TreeNode<K,V>)tab[hash & (tab.length - 1)];
    TreeNode<K,V> x = new TreeNode<>(parent, k, v, f);
    
    // 3. 插入
    if (dir <= 0) {
        parent.left = x;
    } else {
        parent.right = x;
    }
    
    // 4. 调整平衡（着色 + 旋转）
    x.balanceInsertion(root, x);
    
    return null;
}
```

### 旋转操作

```
左旋：

    P                     P
     \                     \
      x          →          y
     / \                   / \
    a   y                 x   r
       / \               / \
      b   r             a   b

代码：
y.left = x.right
x.right.parent = y
p.right = x (或 left)
x.parent = p
y.parent = x.parent
x.right = y
y.parent = x
```

```
右旋：

    P                     P
     \                     \
      x          →          y
     / \                   / \
    y   a                 l   x
   / \                       / \
  l   r                     r   a

代码：
y.right = x.left
x.left.parent = y
p.right = y (或 left)
y.parent = p
y.right = x
x.parent = y
x.left = y.right
y.right.parent = x
```

## 退化过程

### 退化条件

```java
// 扩容时调用
final void split(HashMap<K,V> map, Node<K,V>[] tab, int index, int bit) {
    TreeNode<K,V> b = this;
    
    // ... 省略迁移逻辑 ...
    
    // 退化逻辑
    if (loHead != null) {
        if (size <= UNTREEIFY_THRESHOLD) {
            // 节点数 <= 6，退化为链表
            tab[index] = loHead.untreeify(map);
        }
        // ...
    }
}
```

### untreeify() 方法

```java
final Node<K,V> untreeify(HashMap<K,V> map) {
    TreeNode<K,V> hd = null, tl = null;
    
    // 遍历红黑树，按中序遍历转链表
    for (Node<K,V> q = this; q != null; q = q.next) {
        Node<K,V> p = map.replacementNode(q, null);
        if (tl == null) {
            hd = p;
        } else {
            tl.next = p;
        }
        tl = p;
    }
    return hd;
}
```

## 红黑树 vs 其他平衡树

### 为什么选红黑树而不是 AVL 树？

| 特性 | 红黑树 | AVL 树 |
|------|--------|--------|
| 平衡标准 | 大致平衡 | 严格平衡（左右子树高度差 ≤ 1） |
| 插入/删除开销 | 较小（最多 2 次旋转） | 较大（可能 O(log n) 次旋转） |
| 查找效率 | O(log n)，略慢于 AVL | O(log n)，最优 |
| 应用场景 | 插入删除多 | 查找多 |

```
HashMap 的选择理由：

1. HashMap 经常需要 put/remove
   - 红黑树的插入删除开销更小
   - 适合频繁修改的场景

2. HashMap 不需要严格平衡
   - 只需要 O(log n) 的查找复杂度
   - 红黑树的"大致平衡"已经足够

3. AVL 的严格平衡在 HashMap 中没有优势
   - HashMap 追求的是综合性能
```

## 实战思考

### 极端场景下的性能

```
场景：所有 key 的 hash 都相同

JDK 1.7（纯链表）：
• 所有元素都在一个桶的链表上
• 查找 O(n)，性能差

JDK 1.8（红黑树）：
• 链表长度 > 8 后树化
• 查找 O(log n)，性能好
```

### 如何避免 hash 冲突

```java
// ❌ 差的 hashCode 实现
class BadKey {
    int value;
    public int hashCode() { return 0; }
}

// ✅ 好的 hashCode 实现
class GoodKey {
    String name;
    int value;
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + value;
        return result;
    }
}

// ✅ 更好的方案：使用不可变对象作为 key
// 如 String、Integer、UUID 等
// 这些类的 hashCode 已经经过优化
```

## 常见面试题

### Q1：HashMap 什么时候树化？

**答**：链表长度 > 8 且桶数量 >= 64 时。

### Q2：HashMap 什么时候退化？

**答**：红黑树节点数 <= 6 时会退化为链表。

### Q3：为什么用红黑树而不是 AVL 树？

**答**：红黑树的插入删除开销更小，适合 HashMap 这种需要频繁 put/remove 的场景。

### Q4：TreeNode 的 prev 指针有什么用？

**答**：在红黑树退化为链表时，可以快速将树结构转换为链表结构。

### Q5：红黑树的五大特性是什么？

**答**：
1. 节点非红即黑
2. 根节点是黑色
3. 叶子节点是黑色
4. 红色节点不能连续
5. 黑色高度相同

---

## 总结

1. **树化条件**：链表长度 > 8 且桶数量 >= 64
2. **退化条件**：红黑树节点数 <= 6
3. **双重身份**：TreeNode 同时是树节点和链表节点
4. **五大特性**：保证大致平衡，查找 O(log n)
5. **选择原因**：插入删除开销小，适合 HashMap 场景

---

## 下篇预告

下一篇文章我们将讲解 **ConcurrentHashMap 并发原理**，包括：
- JDK 1.7 分段锁 vs 1.8 CAS + synchronized
- 并发扩容机制
- 常用操作的线程安全实现

敬请期待！

---

> 📚 **推荐阅读**
> - [ConcurrentHashMap 并发原理]({{< relref "post/java-collections-09" >}})
> - [集合选型与实战总结]({{< relref "post/java-collections-10" >}})
> - [HashMap 扩容机制]({{< relref "post/java-collections-07" >}})
