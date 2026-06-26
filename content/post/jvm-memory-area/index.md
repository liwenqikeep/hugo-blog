---
title: "JVM 内存区域与内存溢出"
date: 2018-04-10
draft: false
categories: ["Java"]
tags: ["JVM", "内存区域", "OOM", "运行时数据区", "堆", "栈"]
toc: true
---

## 前言

Java 开发者相比 C/C++ 开发者的一大优势是 JVM 提供了自动内存管理机制，无需手动分配和释放内存。但也正因如此，一旦出现内存泄漏或溢出，排查起来往往更加棘手。

理解 JVM 内存区域的划分，是深入 JVM 的第一步，也是定位内存问题的基本功。

<!--more-->

## 一、运行时数据区

JVM 在执行 Java 程序时会把它管理的内存划分为若干个不同的数据区域。根据《Java 虚拟机规范》，这些区域可以分为两大类：

```
┌─────────────────────────────────────────────────┐
│                 线程私有                          │
│  ┌──────────┐  ┌────────────────┐  ┌──────────┐ │
│  │ 程序计数器 │  │  虚拟机栈      │  │ 本地方法栈│ │
│  └──────────┘  └────────────────┘  └──────────┘ │
├─────────────────────────────────────────────────┤
│                 线程共享                          │
│  ┌──────────────────────┐  ┌──────────────────┐  │
│  │        堆            │  │     方法区        │  │
│  │                      │  │  (运行时常量池)   │  │
│  └──────────────────────┘  └──────────────────┘  │
├─────────────────────────────────────────────────┤
│                 其他                              │
│  ┌──────────────────────┐                        │
│  │      直接内存         │                        │
│  └──────────────────────┘                        │
└─────────────────────────────────────────────────┘
```

### 1.1 程序计数器（Program Counter Register）

程序计数器是一块较小的内存空间，可以看作是当前线程所执行的字节码的行号指示器。

**核心特性：**
- **线程私有**：每个线程都有独立的程序计数器
- **无 OOM**：此区域是唯一一个在《Java 虚拟机规范》中没有规定 `OutOfMemoryError` 情况的区域
- **存储内容**：
  - 执行 Java 方法时：存储正在执行的字节码指令地址
  - 执行 Native 方法时：为空（undefined）

### 1.2 虚拟机栈（Java Virtual Machine Stack）

虚拟机栈描述的是 Java 方法执行的线程内存模型：每个方法被执行时，JVM 都会同步创建一个**栈帧（Stack Frame）**用于存储局部变量表、操作数栈、动态连接、方法出口等信息。

```
┌─────────────────────────────────┐
│        线程                       │
│  ┌───────────────────────────┐   │
│  │ 栈帧（当前方法）            │   │
│  │ ├── 局部变量表             │   │
│  │ ├── 操作数栈               │   │
│  │ ├── 动态连接               │   │
│  │ └── 方法出口               │   │
│  ├───────────────────────────┤   │
│  │ 栈帧（调用者方法）          │   │
│  ├───────────────────────────┤   │
│  │ ……                        │   │
│  └───────────────────────────┘   │
└─────────────────────────────────┘
```

**局部变量表：**
- 存放方法参数和方法内部定义的局部变量
- 以**变量槽（Slot）**为单位，每个 Slot 占用 32 位空间
- 64 位数据类型（long、double）占用两个 Slot
- 实例方法（非 static）的第 0 个 Slot 是 `this`

**异常情况：**
- `StackOverflowError`：线程请求的栈深度大于 JVM 允许的深度（如无限递归）
- `OutOfMemoryError`：栈可动态扩展，但无法申请到足够内存（较少见）

```java
// 栈溢出示例
public class StackOverflowDemo {
    private int stackDepth = 0;
    
    public void recursiveCall() {
        stackDepth++;
        recursiveCall();
    }
    
    public static void main(String[] args) {
        StackOverflowDemo demo = new StackOverflowDemo();
        try {
            demo.recursiveCall();
        } catch (StackOverflowError e) {
            System.out.println("栈深度: " + demo.stackDepth);
            throw e;
        }
    }
}
```

### 1.3 本地方法栈（Native Method Stacks）

本地方法栈与虚拟机栈类似，区别在于虚拟机栈为 JVM 执行 Java 方法（字节码）服务，而本地方法栈为 JVM 使用到的 Native 方法服务。

- 在 HotSpot JVM 中，**虚拟机栈和本地方法栈合二为一**
- 异常情况与虚拟机栈相同：`StackOverflowError` 和 `OutOfMemoryError`

### 1.4 堆（Java Heap）

堆是 JVM 管理的最大一块内存区域，也是**垃圾回收器管理的主要区域**（因此也被称为 GC 堆）。

**核心特性：**
- **线程共享**，在 JVM 启动时创建
- 存放对象实例：几乎所有对象都在这里分配
- 是 GC 的主要战场

**堆的分代结构（以 G1 前的经典布局为例）：**

```
┌──────────────────────────────────────────┐
│                   堆                      │
│  ┌──────┐  ┌──────────┐  ┌────────────┐  │
│  │ 新生代│  │           │  │            │  │
│  │──────│  │  老年代    │  │   元空间   │  │
│  │ Eden │  │           │  │  (方法区)   │  │
│  │ S0 S1│  │           │  │            │  │
│  └──────┘  └──────────┘  └────────────┘  │
└──────────────────────────────────────────┘
```

**参数控制：**
- `-Xms`：堆初始大小
- `-Xmx`：堆最大大小
- `-Xmn`：新生代大小
- `-XX:NewRatio`：老年代与新生代比例（默认 2:1）

```java
// 堆 OOM 示例
public class HeapOOMDemo {
    static class OOMObject {}
    
    public static void main(String[] args) {
        List<OOMObject> list = new ArrayList<>();
        while (true) {
            list.add(new OOMObject());
        }
    }
}
// 运行参数：-Xms20m -Xmx20m -XX:+HeapDumpOnOutOfMemoryError
```

### 1.5 方法区（Method Area）

方法区用于存储已被 JVM 加载的**类型信息、常量、静态变量、即时编译器编译后的代码缓存**等数据。

**核心特性：**
- **线程共享**
- 在 JDK 8 以前，方法区被称为**永久代（PermGen）**
- 从 JDK 8 开始，永久代被移除，取而代之的是**元空间（Metaspace）**

**JDK 8 前后的变化：**

| 特性 | JDK 7 (PermGen) | JDK 8+ (Metaspace) |
|------|-----------------|-------------------|
| 位置 | JVM 堆内 | 本地内存（Native Memory）|
| 默认大小 | 有限（受 -XX:MaxPermSize 控制）| 几乎无限（仅受本地内存限制）|
| OOM 风险 | 频繁（类加载过多时） | 很低 |
| 参数 | `-XX:PermSize` / `-XX:MaxPermSize` | `-XX:MetaspaceSize` / `-XX:MaxMetaspaceSize` |

```java
// 方法区 OOM 示例（JDK 7 及以前）
// 运行时需要设置：-XX:PermSize=10m -XX:MaxPermSize=10m
public class MethodAreaOOMDemo {
    public static void main(String[] args) {
        while (true) {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(OOMObject.class);
            enhancer.setUseCache(false);
            enhancer.setCallback(new MethodInterceptor() {
                public Object intercept(Object obj, Method method, 
                        Object[] args, MethodProxy proxy) throws Throwable {
                    return proxy.invokeSuper(obj, args);
                }
            });
            enhancer.create(); // 动态生成大量类，撑爆方法区
        }
    }
}
```

### 1.6 运行时常量池（Runtime Constant Pool）

运行时常量池是方法区的一部分。Class 文件中除了有类的版本、字段、方法、接口等描述信息外，还有一项信息是**常量池表（Constant Pool Table）**，用于存放编译期生成的各种字面量和符号引用，这部分内容在类加载后存放到运行时常量池中。

**主要特点：**
- 具备动态性：运行期间也可以将新的常量放入池中（如 `String.intern()`）
- 受方法区内存限制

```java
// String.intern() 与常量池
public class StringInternDemo {
    public static void main(String[] args) {
        String s1 = new StringBuilder("计算机").append("软件").toString();
        System.out.println(s1.intern() == s1); // true (JDK 7+)
        
        String s2 = new StringBuilder("ja").append("va").toString();
        System.out.println(s2.intern() == s2); // false ("java" 已在常量池)
    }
}
```

### 1.7 直接内存（Direct Memory）

直接内存并不是 JVM 运行时数据区的一部分，但它也被频繁使用，可能导致 OOM。

**核心要点：**
- 在 JDK 1.4 引入的 NIO 中，基于 **Channel 与 Buffer** 的 I/O 方式可以使用 Native 函数库直接分配堆外内存
- 通过一个存储在堆中的 **DirectByteBuffer** 对象作为内存的引用进行操作
- **优势**：避免了 Java 堆和 Native 堆之间的数据复制，性能更高

```java
// 直接内存 OOM 示例
public class DirectMemoryOOMDemo {
    private static final int _1MB = 1024 * 1024;
    
    public static void main(String[] args) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredFields()[0];
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        while (true) {
            unsafe.allocateMemory(_1MB); // 分配直接内存
        }
    }
}
// 运行参数：-Xmx20m -XX:MaxDirectMemorySize=10m
```

## 二、对象创建与访问

了解 JVM 如何创建一个对象，有助于理解内存区域的协作方式。

### 2.1 对象创建过程

当 JVM 遇到一条 `new` 指令时，会执行以下步骤：

```
new 指令
    │
    ▼
1. 类加载检查：检查常量池中是否有类的符号引用，该类是否已被加载、解析、初始化
    │
    ▼
2. 分配内存：从堆中分配一块内存（大小在类加载完成后即可确定）
    │
    ├── 指针碰撞（Bump the Pointer）：堆内存规整时使用
    └── 空闲列表（Free List）：堆内存不规整时使用
    │
    ▼
3. 内存空间初始化：将分配到的内存空间初始化为零值（不包括对象头）
    │
    ▼
4. 设置对象头：设置对象的哈希码、GC 分代年龄、类型指针、锁信息等
    │
    ▼
5. 执行 <init> 方法：按程序员的意图进行初始化
```

**内存分配的并发问题：**
- **CAS + 失败重试**：乐观方式处理并发
- **TLAB（Thread Local Allocation Buffer）**：每个线程在堆中预先分配一块缓冲区，优先在 TLAB 中分配

```java
// 通过 -XX:+UseTLAB 开启，-XX:TLABSize 指定大小
```

### 2.2 对象的内存布局

在 HotSpot JVM 中，对象在堆中的存储布局分为三部分：

```
┌──────────────────────────────────────────────┐
│              对象头（Header）                  │
│  ├── Mark Word：哈希码、GC 年龄、锁信息等      │
│  │     32位：32bit    64位：64bit              │
│  └── 类型指针（Klass Pointer）：指向类元数据    │
│      （如果开启了指针压缩则为 32bit）           │
├──────────────────────────────────────────────┤
│             实例数据（Instance Data）           │
│  ├── 父类定义的字段                            │
│  └── 子类定义的字段                            │
│  分配顺序：long/double → int → short/char      │
│          → byte/boolean → 普通对象指针          │
├──────────────────────────────────────────────┤
│             对齐填充（Padding）                 │
│  对象起始地址必须是 8 字节的整数倍              │
└──────────────────────────────────────────────┘
```

**指针压缩（-XX:+UseCompressedOops）：**
- 默认开启，将 64 位引用压缩为 32 位
- 节省内存空间，同时减少 GC 压力
- 仅在堆大小 < 32GB 时有效

### 2.3 对象的访问定位

Java 程序通过栈上的 **reference** 数据来操作堆上的对象。主流访问方式有两种：

```
句柄访问：                           直接指针访问（HotSpot 使用）：
┌───────────┐                        ┌───────────┐
│  reference │─────▶┌────────┐       │  reference │─────▶┌───────────┐
└───────────┘      │ 句柄池  │       └───────────┘      │  对象实例   │
                    │────────│                          │───────────│
                    │指针1───│────▶ 对象实例              │ 类型指针───│────▶ 方法区
                    │指针2───│────▶ 对象类型数据          └───────────┘
                    └────────┘          （方法区）
```

**两种方式对比：**

| 对比维度 | 句柄访问 | 直接指针访问 |
|---------|---------|------------|
| 速度 | 慢（两次指针访问） | 快（一次指针访问）|
| 对象移动 | reference 不变（只需改句柄） | reference 需更新 |
| 使用方 | Sun 某些 JVM | **HotSpot** |

## 三、内存溢出（OOM）案例分析

### 3.1 Java 堆溢出

最常见的 OOM 场景，错误信息为 `java.lang.OutOfMemoryError: Java heap space`。

**常见原因：**
- 内存泄漏：对象无法被 GC 回收，不断堆积
- 内存溢出：对象确实都需要，但堆容量不够

**排查步骤：**

```bash
# 1. 启动时开启堆转储
-Xms20m -Xmx20m -XX:+HeapDumpOnOutOfMemoryError

# 2. 使用 MAT 或 VisualVM 分析 dump 文件
#    - 查看 GC Roots 引用链
#    - 找出占用内存最大的对象
#    - 确认是否内存泄漏还是溢出
```

**示例：内存泄漏——ThreadLocal 误用**

```java
public class ThreadLocalLeakDemo {
    private static final ThreadLocal<byte[]> threadLocal = new ThreadLocal<>();
    
    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                threadLocal.set(new byte[10 * 1024 * 1024]); // 10MB
                // 忘记调用 threadLocal.remove()
                // 线程池中的线程不会被销毁，导致 ThreadLocalMap 中的值永远无法回收
            });
        }
        executor.shutdown();
    }
}
```

### 3.2 虚拟机栈溢出

错误信息：`java.lang.StackOverflowError`（深递归）或 `java.lang.OutOfMemoryError: unable to create new native thread`（频繁创建线程）。

**StackOverflowError 示例：**

```java
// 减少栈容量更容易观察
// -Xss128k
public class StackSOFDemo {
    private int length = 1;
    
    public void stackLeak() {
        length++;
        stackLeak();
    }
    
    public static void main(String[] args) {
        StackSOFDemo demo = new StackSOFDemo();
        try {
            demo.stackLeak();
        } catch (StackOverflowError e) {
            System.out.println("递归深度: " + demo.length);
            throw e;
        }
    }
}
```

**无法创建线程的 OOM：**

```java
// 操作系统限制了线程数量
// -Xss2m（增大栈容量会加速出现此异常）
public class StackOOMDemo {
    public static void main(String[] args) {
        while (true) {
            new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }).start();
        }
    }
}
```
> 注意：此操作可能导致系统假死，谨慎执行。

### 3.3 方法区/元空间溢出

JDK 8 以后，元空间使用本地内存，默认无上限，但仍然可以被 `-XX:MaxMetaspaceSize` 限制。

**常见场景：**
- CGLib、Javassist 等动态生成大量类的框架
- JSP 文件编译产生的大量 Class
- 大量使用 Lambda 表达式（每个 Lambda 都会生成一个匿名类）

```java
// -XX:MetaspaceSize=10m -XX:MaxMetaspaceSize=10m
public class MetaspaceOOMDemo {
    public static void main(String[] args) {
        while (true) {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(OOMObject.class);
            enhancer.setUseCache(false);
            enhancer.setCallback((MethodInterceptor) (obj, method, args1, proxy) 
                    -> proxy.invokeSuper(obj, args1));
            enhancer.create();
        }
    }
    
    static class OOMObject {}
}
```

### 3.4 直接内存溢出

错误信息不明显，往往会伴随 `OutOfMemoryError`，但堆转储文件很小，说明问题不在堆内。

**常见场景：**
- NIO 程序中频繁分配 DirectBuffer 忘记释放
- Netty 中直接内存池泄漏

```java
// -XX:MaxDirectMemorySize=10m
public class DirectBufferOOMDemo {
    private static final int _1MB = 1024 * 1024;
    
    public static void main(String[] args) throws Exception {
        List<ByteBuffer> list = new ArrayList<>();
        while (true) {
            list.add(ByteBuffer.allocateDirect(_1MB));
        }
    }
}
```

## 四、总结

| 区域 | 线程私有/共享 | 存储内容 | 异常 |
|------|-------------|---------|------|
| 程序计数器 | 私有 | 字节码行号 | 无 |
| 虚拟机栈 | 私有 | 局部变量表、操作数栈等 | SOF / OOM |
| 本地方法栈 | 私有 | Native 方法信息 | SOF / OOM |
| 堆 | 共享 | 对象实例 | OOM |
| 方法区 | 共享 | 类型信息、常量、静态变量 | OOM |
| 直接内存 | 共享（全局） | Native 内存 | OOM |

### 内存排查速查表

| 错误信息 | 可能原因 | 排查工具 | 常见参数 |
|---------|---------|---------|---------|
| `Java heap space` | 堆溢出/泄漏 | MAT、VisualVM | `-Xmx` |
| `PermGen space` | 方法区溢出（JDK 7-） | jstat | `-XX:MaxPermSize` |
| `Metaspace` | 方法区溢出（JDK 8+） | jstat | `-XX:MaxMetaspaceSize` |
| `unable to create new native thread` | 栈溢出（线程过多） | jstack、OS 层面 | `-Xss`、ulimit |
| 堆转储很小但 OOM | 直接内存溢出 | Native Memory Tracking | `-XX:MaxDirectMemorySize` |

---

**相关阅读：**
- [JVM 垃圾回收机制]({{< relref "post/jvm-gc" >}})
- [JVM 类加载机制]({{< relref "post/jvm-classloader" >}})
