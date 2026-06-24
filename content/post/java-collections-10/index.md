---
title: "Java集合框架(十) 选型与实战总结"
date: 2018-01-21
draft: false
categories: ["Java"]
tags: ["集合选型", "实战", "性能优化", "踩坑汇总"]
series: ["Java集合系列"]
[params.toc]
  startLevel = 2
  endLevel = 3
---

## 前言

这是 Java 集合系列的最后一篇文章。我们来做一个全面的总结，包括：各集合的适用场景、性能优化技巧和常见踩坑汇总。

<!--more-->

## 选型决策树

```
需要存储键值对？
│
├─ Yes → 需要并发安全？
│       │
│       ├─ Yes → ConcurrentHashMap
│       │
│       └─ No → 需要保持插入顺序？
│               │
│               ├─ Yes → LinkedHashMap
│               │
│               └─ No → 需要按键排序？
│                       │
│                       ├─ Yes → TreeMap
│                       │
│                       └─ No → HashMap
│
└─ No → 需要 Queue/Stack？
        │
        ├─ Yes → 需要两端操作？
        │       │
        │       ├─ Yes → LinkedList / ArrayDeque
        │       │
        │       └─ No → 需要优先级？
        │               │
        │               ├─ Yes → PriorityQueue
        │               │
        │               └─ No → LinkedList
        │
        └─ No → 需要元素唯一？
                │
                ├─ Yes → 需要排序？
                │       │
                │       ├─ Yes → TreeSet
                │       │
                │       └─ No → LinkedHashSet / HashSet
                │
                └─ No → 需要快速随机访问？
                        │
                        ├─ Yes → ArrayList
                        │
                        └─ No → LinkedList
```

## List 选型

| 场景 | 推荐 | 原因 |
|------|------|------|
| 随机访问多 | ArrayList | 随机访问 O(1) |
| 尾部追加为主 | ArrayList | 扩容代价可接受 |
| 头尾操作多 | LinkedList | 头尾操作 O(1) |
| 遍历多 | ArrayList | 缓存友好 |
| 线程安全 | CopyOnWriteArrayList | 读多写少场景 |
| 通用场景 | ArrayList | 默认选择 |

### 实际案例

```java
// 场景 1：存储用户列表，分页展示
List<User> users = new ArrayList<>();
// ✅ 分页时 get(index) O(1)

// 场景 2：实现消息队列
Queue<Message> queue = new LinkedList<>();
queue.offer(msg);
Message msg = queue.poll();
// ✅ 头尾操作 O(1)

// 场景 3：配置列表，几乎只读
List<Config> configs = new CopyOnWriteArrayList<>();
// ✅ 读不加锁，写时复制快照
```

## Set 选型

| 场景 | 推荐 | 原因 |
|------|------|------|
| 去重（最常用） | HashSet | O(1) 插入和查找 |
| 保持插入顺序 | LinkedHashSet | 内部维护双向链表 |
| 需要排序 | TreeSet | 红黑树，有序 |
| 线程安全 | ConcurrentSkipListSet | 有序并发 Set |

### 实际案例

```java
// 场景 1：用户 ID 去重
Set<Long> userIds = new HashSet<>();
userIds.add(user.getId());

// 场景 2：最近访问记录（保持顺序）
Set<String> recent = new LinkedHashSet<>(16, 0.75f, true);
recent.add(url);
recent.add(url);
// ✅ accessOrder=true 时，最近访问的移到最后

// 场景 3：排行榜（需要排序）
Set<Score> ranking = new TreeSet<>(
    Comparator.comparingInt(Score::getScore).reversed()
);
```

## Map 选型

| 场景 | 推荐 | 原因 |
|------|------|------|
| 键值存储（通用） | HashMap | O(1) 查找和插入 |
| 保持键的顺序 | LinkedHashMap | 插入顺序或访问顺序 |
| 需要按键排序 | TreeMap | 红黑树，有序 |
| 并发读写 | ConcurrentHashMap | 线程安全高性能 |
| 需要并发有序 | ConcurrentSkipListMap | 有序并发 Map |

### 实际案例

```java
// 场景 1：缓存用户信息
Map<Long, User> cache = new HashMap<>();
cache.put(user.getId(), user);
User u = cache.get(userId);

// 场景 2：实现 LRU 缓存
Map<String, Object> lru = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry eldest) {
        return size() > MAX_SIZE;
    }
};

// 场景 3：配置中心
Map<String, String> config = new ConcurrentHashMap<>();
config.put(key, value);  // 多线程安全
```

## 性能优化技巧

### 1. 预估容量，避免扩容

```java
// ❌ 每次 add 都可能触发扩容
List<String> list = new ArrayList<>();
for (int i = 0; i < 100000; i++) {
    list.add("item" + i);
}

// ✅ 预估容量，一次到位
List<String> list = new ArrayList<>(100000);
for (int i = 0; i < 100000; i++) {
    list.add("item" + i);
}

// ✅ HashMap 同样适用
Map<String, String> map = new HashMap<>(expectedSize * 2);
```

### 2. 使用批量操作

```java
// ❌ 逐个添加
for (String item : items) {
    list.add(item);
}

// ✅ 批量添加（只扩容一次）
list.addAll(items);

// ✅ 批量获取
map.putAll(anotherMap);
```

### 3. 选择合适的负载因子

```java
// 默认负载因子 0.75

// 场景：内存敏感，愿意用空间换时间
// 可以降低负载因子，减少冲突
Map<K, V> map = new HashMap<>(initialCapacity, 0.5f);

// 场景：内存不敏感，希望减少扩容次数
// 可以提高负载因子
Map<K, V> map = new HashMap<>(initialCapacity, 0.9f);
```

### 4. 使用 Entry 遍历

```java
// ❌ 低效：每 entrySet() 都创建新的 Set 视图
for (String key : map.keySet()) {
    doSomething(key, map.get(key));
}

// ✅ 高效：直接遍历 Entry
for (Map.Entry<String, String> entry : map.entrySet()) {
    doSomething(entry.getKey(), entry.getValue());
}

// ✅ JDK 8+ 更高效率
map.forEach((key, value) -> doSomething(key, value));
```

### 5. 合理使用基本类型包装类

```java
// ❌ 每个 Integer 都是对象，内存开销大
List<Integer> list = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    list.add(i);  // 自动装箱：int → Integer
}

// ✅ 如果能用 int 数组
int[] array = new int[10000];
for (int i = 0; i < 10000; i++) {
    array[i] = i;
}

// ✅ 或者使用第三方库如 Trove（现在推荐 HPPC）
TIntArrayList list = new TIntArrayList();
```

### 6. 迭代时删除的正确方式

```java
// ❌ ConcurrentModificationException
for (String item : list) {
    if (shouldRemove(item)) {
        list.remove(item);
    }
}

// ✅ 方式 1：使用迭代器删除
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    if (shouldRemove(it.next())) {
        it.remove();
    }
}

// ✅ 方式 2：JDK 8+ removeIf
list.removeIf(this::shouldRemove);

// ✅ 方式 3：倒序遍历
for (int i = list.size() - 1; i >= 0; i--) {
    if (shouldRemove(list.get(i))) {
        list.remove(i);
    }
}
```

## 常见踩坑汇总

### 踩坑 1：HashMap 的 key 不能是可变对象

```java
// ❌ 错误示例：key 的 hashCode 变了
class User {
    private String name;
    private int id;
    
    @Override
    public int hashCode() {
        return id;  // 如果 id 变了，hashCode 就变了！
    }
}

Map<User, String> map = new HashMap<>();
User user = new User(1, "Tom");
map.put(user, "value");

user.setId(2);  // ⚠️ hashCode 变了！

map.get(user);  // ❌ 可能返回 null！因为找不到正确的桶

// ✅ 正确做法：使用不可变对象作为 key
// String、Integer、Long、UUID 都是好的选择
```

### 踩坑 2：HashMap 不能存 null？

```java
// ❌ HashMap 可以存 null 键和 null 值
HashMap<String, String> map = new HashMap<>();
map.put(null, "value");  // 可以
map.put("key", null);   // 可以

// ❌ ConcurrentHashMap 不允许 null 键和 null 值
ConcurrentHashMap<String, String> chm = new ConcurrentHashMap<>();
chm.put(null, "value");  // NullPointerException！

// 原因：ConcurrentHashMap 的 get() 返回 null 时
// 无法区分是"没有这个键"还是"值是 null"
```

### 踩坑 3：线程不安全的单例模式

```java
// ❌ 线程不安全的懒加载
public class Singleton {
    private static Map<String, Object> cache;
    
    public static Map<String, Object> getInstance() {
        if (cache == null) {
            cache = new HashMap<>();  // ⚠️ 多线程下可能创建多个实例
        }
        return cache;
    }
}

// ✅ 正确方式 1：内部类
public class Singleton {
    private static class Holder {
        static Map<String, Object> cache = new HashMap<>();
    }
    
    public static Map<String, Object> getInstance() {
        return Holder.cache;
    }
}

// ✅ 正确方式 2：ConcurrentHashMap
public class Singleton {
    private static final ConcurrentHashMap<String, Object> cache =
        new ConcurrentHashMap<>();
    
    public static Map<String, Object> getInstance() {
        return cache;
    }
}
```

### 踩坑 4：ArrayList.subList 不是独立列表

```java
// ❌ subList 是原列表的视图，不是副本
List<String> original = new ArrayList<>();
original.add("A");
original.add("B");
original.add("C");

List<String> sub = original.subList(0, 2);

sub.add("D");  // 修改 subList
System.out.println(original);  // [A, B, D, C]
// ⚠️ 原列表也被修改了！

sub.clear();  // 清空 subList
System.out.println(original);  // [C]
// ⚠️ 原列表也被清空了！

// ✅ 如果需要独立副本
List<String> copy = new ArrayList<>(original.subList(0, 2));
```

### 踩坑 5：HashSet 的 contains 复杂度

```java
// HashSet.contains() 依赖 hashCode + equals
// 如果所有对象的 hashCode 都相同
// contains 就退化为 O(n)

class BadClass {
    private int value;
    
    @Override
    public int hashCode() {
        return 0;  // ⚠️ 所有实例 hashCode 都相同！
    }
}

HashSet<BadClass> set = new HashSet<>();
set.contains(badObject);  // O(n)！

// ✅ 正确实现 hashCode
@Override
public int hashCode() {
    return Objects.hash(value);
}
```

### 踩坑 6：ConcurrentHashMap 的复合操作不是原子的

```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
map.put("key", 0);

// ❌ 不是原子操作！
map.put("key", map.get("key") + 1);

// ✅ 使用原子方法
map.incrementAndGet("key");  // 支持时
map.computeIfPresent("key", (k, v) -> v + 1);

// ✅ 使用 merge
map.merge("key", 1, Integer::sum);
```

### 踩坑 7：TreeMap 的 Comparable 要求

```java
// ❌ 如果 key 没有实现 Comparable，会抛异常
class User {
    private String name;
}

TreeMap<User, String> map = new TreeMap<>();
map.put(user, "value");  // ClassCastException！

// ✅ 方式 1：实现 Comparable
class User implements Comparable<User> {
    private String name;
    
    @Override
    public int compareTo(User o) {
        return this.name.compareTo(o.name);
    }
}

// ✅ 方式 2：提供 Comparator
TreeMap<User, String> map = new TreeMap<>(Comparator.comparing(u -> u.name));
```

## 各集合时间复杂度总结

### List

| 操作 | ArrayList | LinkedList |
|------|-----------|------------|
| add(E) | O(1) 均摊 | O(1) |
| add(index, E) | O(n) | O(1) 找到位置 |
| get | O(1) | O(n) |
| remove | O(n) | O(1) 找到位置 |

### Set

| 操作 | HashSet | LinkedHashSet | TreeSet |
|------|---------|---------------|---------|
| add | O(1) | O(1) | O(log n) |
| remove | O(1) | O(1) | O(log n) |
| contains | O(1) | O(1) | O(log n) |

### Map

| 操作 | HashMap | LinkedHashMap | TreeMap |
|------|---------|---------------|---------|
| put | O(1) | O(1) | O(log n) |
| get | O(1) | O(1) | O(log n) |
| remove | O(1) | O(1) | O(log n) |

## 内存占用对比

```
存储 1000 个元素（32位 JVM，指针压缩）

┌────────────────┬─────────────────┬─────────────────┐
│    集合类型      │  元素引用大小    │   额外开销       │
├────────────────┼─────────────────┼─────────────────┤
│ ArrayList      │  ~8KB          │   数组头 ~24B   │
│ LinkedList     │  ~48KB         │   每节点 ~32B   │
│ HashMap        │  ~32KB         │   每桶 ~32B    │
│ TreeMap        │  ~32KB         │   每节点 ~48B  │
└────────────────┴─────────────────┴─────────────────┘
```

## 面试高频问题速记

### 1. ArrayList vs LinkedList
- 随机访问多 → ArrayList
- 头尾操作多 → LinkedList
- 默认选择 → ArrayList

### 2. HashMap 原理
- 数组+链表/红黑树
- hash() 扰动函数
- 容量 2 的幂次
- 负载因子 0.75
- 链表 > 8 且桶 >= 64 树化

### 3. ConcurrentHashMap
- JDK 1.7：分段锁
- JDK 1.8：CAS + synchronized
- get 不加锁（volatile）

### 4. fail-fast vs fail-safe
- fail-fast：modCount 检测
- fail-safe：复制快照遍历

### 5. 如何保证线程安全
- 单线程：HashMap, ArrayList
- 并发安全：ConcurrentHashMap, CopyOnWriteArrayList
- 装饰器：Collections.synchronizedMap/List

---

## 系列总结

经过 10 篇文章，我们深入学习了 Java 集合框架的核心知识：

```
📚 系列文章回顾

1. 集合框架全景图 - 整体架构
2. ArrayList 原理与扩容 - 动态数组
3. LinkedList 原理 - 双向链表
4. fail-fast 机制详解 - modCount
5. ArrayList vs LinkedList - 性能对比
6. HashMap 核心原理 - hash 函数
7. HashMap 扩容机制 - resize
8. HashMap 红黑树原理 - 树化/退化
9. ConcurrentHashMap 并发原理 - CAS + synchronized
10. 集合选型与实战总结 - 选型指南 + 踩坑汇总
```

### 核心要点

1. **ArrayList**：动态数组，随机访问 O(1)，中间操作 O(n)
2. **LinkedList**：双向链表，头尾操作 O(1)，随机访问 O(n)
3. **HashMap**：哈希表，put/get O(1)，依赖好的 hashCode
4. **ConcurrentHashMap**：CAS + synchronized，线程安全
5. **TreeMap/TreeSet**：红黑树，有序，O(log n)
6. **选型**：根据访问模式和数据特点选择合适的集合

### 进阶学习建议

```
还想深入？可以继续学习：

📖 队列专题
• BlockingQueue（ArrayBlockingQueue, LinkedBlockingQueue）
• Deque 详解
• DelayQueue, PriorityBlockingQueue

📖 高级特性
• WeakHashMap（缓存）
• EnumSet/EnumMap（枚举类型）
• IdentityHashMap（对象身份）
• Collections 工具类

📖 并发集合
• CopyOnWriteArrayList
• ConcurrentLinkedQueue
• ConcurrentSkipListMap/Set

📖 源码阅读
• JDK 源码（java.util 包）
• Guava 集合工具
• Hutool 集合工具
```

---

感谢你坚持看完这个系列！希望这些知识对你有帮助。如果有任何问题，欢迎留言讨论！

---

> 📚 **推荐阅读**
> - [Java 集合框架全景图]({{< relref "post/java-collections-01" >}})
> - [ConcurrentHashMap 并发原理]({{< relref "post/java-collections-09" >}})
> - [HashMap 扩容机制]({{< relref "post/java-collections-07" >}})

> 🚀 **继续加油，Java 之路，与你同行！**
