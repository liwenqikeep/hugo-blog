---
title: Java 集合类框架详解
date: 2017-12-14
draft: false
categories: ["Java"]
tags: ["Java SE", "集合框架", "数据结构"]
---

## 概述

Java 集合类框架（Java Collections Framework）是 Java 标准库中用于存储和操作对象组的核心组件。它提供了丰富的数据结构，可以满足不同的业务场景需求。

## 集合框架层次结构

```
Collection
├── List（有序、可重复）
│   ├── ArrayList
│   ├── LinkedList
│   └── Vector
├── Set（无序、不可重复）
│   ├── HashSet
│   ├── LinkedHashSet
│   └── TreeSet
└── Queue（队列）
    ├── LinkedList
    └── PriorityQueue

Map（键值对）
├── HashMap
├── LinkedHashMap
├── TreeMap
└── Hashtable
```

## List 接口

### ArrayList

```java
List<String> list = new ArrayList<>();
list.add("Java");
list.add("Python");
list.add("Go");

// 访问元素
String first = list.get(0);

// 遍历
for (String item : list) {
    System.out.println(item);
}
```

**特点**：
- 基于动态数组实现
- 随机访问效率高 O(1)
- 插入、删除效率低 O(n)
- 线程不安全

### LinkedList

```java
LinkedList<String> linkedList = new LinkedList<>();
linkedList.add("A");
linkedList.addFirst("B");
linkedList.addLast("C");

// 在头部和尾部操作效率高
linkedList.removeFirst();
linkedList.removeLast();
```

**特点**：
- 基于双向链表实现
- 插入、删除效率高 O(1)
- 随机访问效率低 O(n)
- 可用作栈和队列

## Set 接口

### HashSet

```java
Set<String> set = new HashSet<>();
set.add("Apple");
set.add("Banana");
set.add("Apple");  // 重复元素不会被添加

// 判断是否包含
boolean hasApple = set.contains("Apple");
```

**特点**：
- 基于 HashMap 实现
- 元素无序
- 查找、添加效率高 O(1)
- 线程不安全

### TreeSet

```java
Set<Integer> treeSet = new TreeSet<>();
treeSet.add(3);
treeSet.add(1);
treeSet.add(2);

// 自然排序
System.out.println(treeSet.first());  // 1
System.out.println(treeSet.last());   // 3
```

**特点**：
- 基于红黑树实现
- 元素有序（自然排序或自定义排序）
- 查找、添加效率 O(log n)

## Map 接口

### HashMap

```java
Map<String, Integer> map = new HashMap<>();
map.put("Java", 98);
map.put("Python", 95);
map.put("Go", 90);

// 访问
Integer score = map.get("Java");

// 遍历
for (Map.Entry<String, Integer> entry : map.entrySet()) {
    System.out.println(entry.getKey() + ": " + entry.getValue());
}
```

**特点**：
- 基于哈希表实现
- 键值对存储
- 键不能重复
- 线程不安全

### LinkedHashMap

```java
Map<String, Integer> linkedMap = new LinkedHashMap<>();
linkedMap.put("A", 1);
linkedMap.put("B", 2);
linkedMap.put("C", 3);

// 保持插入顺序
for (String key : linkedMap.keySet()) {
    System.out.println(key);
}
```

**特点**：
- 继承自 HashMap
- 保持插入顺序

## 选择合适的数据结构

| 场景 | 推荐选择 |
|------|----------|
| 需要快速随机访问 | ArrayList |
| 需要频繁插入删除 | LinkedList |
| 需要去重 | HashSet |
| 需要排序 | TreeSet |
| 键值对存储 | HashMap |
| 需要保持顺序 | LinkedHashMap / LinkedHashSet |
| 高并发场景 | ConcurrentHashMap |

## 总结

Java 集合框架提供了丰富的数据结构，选择合适的数据结构可以显著提升程序性能。理解各数据结构的底层实现和特点，是写出高效 Java 代码的基础。