---
title: "Java IO 概述：BIO、NIO、AIO 对比"
date: 2018-01-25
draft: false
categories: ["Java"]
tags: ["IO", "BIO", "NIO", "AIO"]
---

# Java IO 概述：BIO、NIO、AIO 对比

## 什么是 IO

IO（Input/Output）是程序与外部世界交换数据的方式。在 Java 中，IO 操作涉及：
- 读写文件
- 网络通信
- 进程间通信

## 三种 IO 模型

### 1. BIO（Blocking IO）

同步阻塞式 IO，传统的 IO 模型。

```java
// BIO 示例：读取文件
try (InputStream is = new FileInputStream("test.txt")) {
    int data;
    while ((data = is.read()) != -1) {
        // 每次读取都会阻塞，直到数据可用
        System.out.print((char) data);
    }
}
```

**特点**：
- 每个连接一个线程
- 阻塞导致资源浪费
- 适合连接数量少的场景

### 2. NIO（New IO / Non-Blocking IO）

同步非阻塞式 IO，JDK 1.4 引入。

```java
// NIO 示例：非阻塞读取
ByteBuffer buffer = ByteBuffer.allocate(1024);
FileChannel channel = FileChannel.open(Paths.get("test.txt"));

// 设置为非阻塞模式
channel.configureBlocking(false);

int read = channel.read(buffer);  // 立即返回，无数据时返回0
```

**核心组件**：
- **Buffer**：缓冲区
- **Channel**：通道
- **Selector**：选择器

### 3. AIO（Asynchronous IO）

异步非阻塞 IO，JDK 7 引入。

```java
// AIO 示例：异步文件读取
AsynchronousFileChannel channel = 
    AsynchronousFileChannel.open(Paths.get("test.txt"));

ByteBuffer buffer = ByteBuffer.allocate(1024);
channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
    @Override
    public void completed(Integer result, ByteBuffer attachment) {
        // 读取完成后自动调用
        System.out.println("读取了 " + result + " 字节");
    }
    
    @Override
    public void failed(Throwable exc, ByteBuffer attachment) {
        exc.printStackTrace();
    }
});
```

## 模型对比

| 特性 | BIO | NIO | AIO |
|------|-----|-----|-----|
| 阻塞类型 | 同步阻塞 | 同步非阻塞 | 异步非阻塞 |
| 线程模型 | 1:1 | 1:N（Selector） | 回调/Listener |
| 复杂度 | 简单 | 中等 | 复杂 |
| JDK 版本 | 1.0 | 1.4 | 1.7 |
| 适用场景 | 连接少 | 高并发 | 极高并发 |

## 选择建议

```
连接数少、传输速度要求高 → BIO
连接数多、需要高并发    → NIO  
追求极致性能           → AIO / NIO.2
```

## 性能对比示意

```
BIO:     线程1 ───[等待]───[等待]───[等待]───>
         线程2 ──────────[等待]───[等待]───>
         线程3 ──────────────[等待]───[等待]───>

NIO:     线程1 ←───────────────────────────
              ↓
         Selector 检测多个通道
              ↓
         线程1 ←──[处理1]──[处理2]──[处理3]

AIO:     线程1 ────[发起请求]─────────────>
              ↓
         系统内核处理
              ↓
         完成回调 ────→ [处理结果]
```

## 总结

Java IO 模型经历了从 BIO 到 NIO 再到 AIO 的演进。选择合适的 IO 模型需要考虑：
- 连接数量
- 并发要求
- 开发复杂度
- JDK 版本

后续文章将深入解析每个组件的原理与源码实现。
