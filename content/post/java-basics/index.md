---
title: Java 基础语法
date: 2017-11-12
draft: false
categories: ["Java"]
tags: ["Java SE", "基础语法"]
---

## 简介

Java 是一种面向对象的编程语言，由 Sun Microsystems 于 1995 年发布。本文将介绍 Java 的基础语法知识。

## 基本数据类型

Java 共有 8 种基本数据类型：

| 类型 | 占用空间 | 默认值 |
|------|----------|--------|
| byte | 1 字节 | 0 |
| short | 2 字节 | 0 |
| int | 4 字节 | 0 |
| long | 8 字节 | 0L |
| float | 4 字节 | 0.0f |
| double | 8 字节 | 0.0d |
| char | 2 字节 | '\u0000' |
| boolean | 1 位 | false |

## 变量声明

```java
// 变量声明
int age = 25;
String name = "Java";
boolean isActive = true;

// 常量声明
final double PI = 3.14159;
```

## 条件语句

```java
if (condition) {
    // 条件为 true 时执行
} else if (anotherCondition) {
    // 另一个条件为 true 时执行
} else {
    // 所有条件都不满足时执行
}
```

## 循环语句

### for 循环

```java
for (int i = 0; i < 10; i++) {
    System.out.println("i = " + i);
}
```

### while 循环

```java
int count = 0;
while (count < 5) {
    System.out.println("count = " + count);
    count++;
}
```

## 方法定义

```java
public static int add(int a, int b) {
    return a + b;
}
```

## 总结

本文介绍了 Java 的基本数据类型、变量声明、条件语句、循环语句和方法定义等基础知识。掌握这些内容是学习 Java 编程的第一步。