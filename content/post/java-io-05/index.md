---
title: "NIO Buffer 深度解析"
date: 2018-02-02
draft: false
categories: ["Java"]
tags: ["NIO", "Buffer", "ByteBuffer"]
---

# NIO Buffer 深度解析

## Buffer 概述

Buffer 是 NIO 的核心组件，用于在通道（Channel）和程序之间传输数据。

```
                    ┌─────────────┐
                    │   Channel   │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Buffer   │
                    │  [读写数据] │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │    程序     │
                    └─────────────┘
```

## Buffer 类型

| Buffer 类型 | 对应数据类型 |
|------------|-------------|
| ByteBuffer | byte |
| CharBuffer | char |
| ShortBuffer | short |
| IntBuffer | int |
| LongBuffer | long |
| FloatBuffer | float |
| DoubleBuffer | double |
| MappedByteBuffer | 内存映射文件 |

## Buffer 数据结构

Buffer 内部结构包含四个关键索引：

```
┌────────────────────────────────────────────┐
│                ByteBuffer                   │
├────────────────────────────────────────────┤
│                                            │
│   ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐       │
│   │  │  │  │  │  │  │  │  │  │  │       │
│   └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘       │
│    ↑                    ↑                  │
│  position             capacity             │
│                                            │
│   ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐       │
│   │▓▓│▓▓│  │  │  │  │  │  │  │  │       │
│   └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘       │
│    ↑    ↑                                │
│  mark  limit                             │
│                                            │
│   ■ = 已写入数据                          │
│   □ = 可用空间                            │
│                                            │
└────────────────────────────────────────────┘
```

### 四个关键索引

```java
public abstract class Buffer {
    // 当前位置，下一个读写操作的起始位置
    int position = 0;
    
    // 界限，表示可以读写的最大位置
    int limit;
    
    // 容量，缓冲区的总大小
    int capacity;
    
    // 标记位置，用于恢复 position
    int mark = -1;
}
```

## ByteBuffer 创建

```java
// 1. 使用 allocate（堆内存）
ByteBuffer heapBuffer = ByteBuffer.allocate(1024);

// 2. 使用 allocateDirect（直接内存，OS 级缓冲区）
ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024);

// 3. 包装已有数组
byte[] array = new byte[1024];
ByteBuffer wrappedBuffer = ByteBuffer.wrap(array);
```

### 堆内存 vs 直接内存

```java
// allocate 内部实现
public static ByteBuffer allocate(int capacity) {
    if (capacity < 0) {
        throw new IllegalArgumentException();
    }
    // 创建基于堆内存的 ByteBuffer
    return new HeapByteBuffer(capacity, capacity);
}

// allocateDirect 内部实现
public static ByteBuffer allocateDirect(int capacity) {
    // 创建直接内存缓冲区，由 unsafe 操作
    return new DirectByteBuffer(capacity);
}
```

| 特性 | HeapByteBuffer | DirectByteBuffer |
|------|---------------|------------------|
| 存储位置 | JVM 堆内存 | OS 直接内存 |
| 创建速度 | 快 | 慢 |
| 读写速度 | 较慢（需要复制） | 快（零拷贝） |
| GC | 会 | 不会（手动回收） |
| 内存占用 | 较大 | 较小 |

## 写操作

### put() 方法

```java
ByteBuffer buffer = ByteBuffer.allocate(10);

// 写入单个字节
buffer.put((byte) 1);
buffer.put((byte) 2);

// 写入字节数组
buffer.put(new byte[]{3, 4, 5});

// 写入指定位置
buffer.put(7, (byte) 10);

System.out.println(buffer.position());  // 5
System.out.println(buffer.limit());     // 10
```

### 写入过程

```
初始状态:
position=0, limit=10, capacity=10
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│  │  │  │  │  │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘

put(1), put(2), put(new byte[]{3,4,5}) 后:
position=5, limit=10, capacity=10
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
```

## 读操作

### get() 方法

```java
ByteBuffer buffer = ByteBuffer.allocate(10);
buffer.put(new byte[]{1, 2, 3, 4, 5});
buffer.flip();  // 切换到读模式

// 读取单个字节
byte b = buffer.get();  // 1

// 读取到字节数组
byte[] arr = new byte[2];
buffer.get(arr);  // arr = [2, 3]

// 读取指定位置（不影响 position）
byte b2 = buffer.get(4);  // 5
```

## flip() 方法（关键）

flip() 用于切换读写模式：

```java
// flip() 源码
public final Buffer flip() {
    limit = position;    // 设置界限为当前位置
    position = 0;        // 重置位置为 0
    mark = -1;          // 清除标记
    return this;
}
```

### flip() 转换过程

```
写入后（准备 flip）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
 ↑                    ↑
position=5          limit=10

flip() 后（准备读取）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
 ↑        ↑
position=0   limit=5
```

## clear() 方法

clear() 用于清空缓冲区，准备重新写入：

```java
// clear() 源码
public final Buffer clear() {
    position = 0;
    limit = capacity;
    mark = -1;
    return this;
}
```

### clear() 转换过程

```
读取后（准备 clear）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
              ↑    ↑
        position=5  limit=5

clear() 后（准备写入）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
 ↑                    ↑
position=0          limit=10
```

## compact() 方法

compact() 保留未读数据，准备继续写入：

```java
// compact() 源码
public ByteBuffer compact() {
    System.arraycopy(
        src, src.position(),    // 源：未读数据起始位置
        src, 0,                 // 目标：从 0 开始
        src.remaining()          // 长度：剩余未读数据
    );
    position(remaining());
    limit(capacity);
    return this;
}
```

### compact() 转换过程

```
compact() 前（读取了前3个字节）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│① │② │③ │④ │⑤ │⑥ │⑦ │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
              ↑    ↑
        position=3  limit=7

compact() 后（未读数据前移）:
┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
│④ │⑤ │⑥ │⑦ │  │  │  │  │  │  │
└──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
 ↑                    ↑
position=4          limit=10
```

## 常用方法总结

| 方法 | 作用 |
|------|------|
| flip() | 切换为读模式 |
| clear() | 清空，准备写入 |
| compact() | 压缩未读数据，准备继续写入 |
| rewind() | 重置 position，准备重新读取 |
| mark() | 标记当前位置 |
| reset() | 恢复到 mark 位置 |
| remaining() | 剩余可读写元素数 |
| hasRemaining() | 是否还有可读写元素 |

## 实战示例：文件复制

```java
public void copyFile(String src, String dst) throws IOException {
    FileChannel srcChannel = FileChannel.open(Paths.get(src), StandardOpenOption.READ);
    FileChannel dstChannel = FileChannel.open(Paths.get(dst), 
        StandardOpenOption.CREATE, 
        StandardOpenOption.WRITE);
    
    ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
    
    while (srcChannel.read(buffer) != -1) {
        buffer.flip();
        dstChannel.write(buffer);
        buffer.clear();  // 准备下一轮读取
    }
    
    srcChannel.close();
    dstChannel.close();
}
```

## 总结

- Buffer 是 NIO 的基础，包含 position、limit、capacity、mark 四个索引
- flip() 切换读写模式，clear() 清空缓冲区
- allocate() 使用堆内存，allocateDirect() 使用直接内存
- 理解 Buffer 状态转换是掌握 NIO 的关键

> 📚 **推荐阅读**
> - [NIO Channel 通道机制]({{< relref "post/java-io-06" >}})
