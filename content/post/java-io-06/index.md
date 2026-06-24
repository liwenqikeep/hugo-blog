---
title: "NIO Channel 通道机制"
date: 2018-02-04
draft: false
categories: ["Java"]
tags: ["NIO", "Channel", "FileChannel"]
---

# NIO Channel 通道机制

## Channel 概述

Channel（通道）是 NIO 提供的用于读写数据的双向连接，类似于流但可以异步读写。

```
传统 IO 流:              NIO Channel:
[程序] → 输出流 → [目标]  [程序] ←→ Buffer ←→ Channel ←→ [目标]
[程序] ← 输入流 ← [源]    [程序] ←→ Buffer ←→ Channel ←→ [源]
```

## Channel 体系

```
                    ┌─────────────┐
                    │  Channel    │ (接口)
                    └──────┬──────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼───────┐  ┌───────▼───────┐  ┌───────▼───────┐
│ WritableByteChannel│ │ ReadableByteChannel│ │ ByteChannel   │
└───────┬───────┘  └───────┬───────┘  └───────┬───────┘
        │                  │                  │
┌───────▼──────────────────▼──────────────────▼───────┐
│              ByteChannel (接口)                      │
└───────┬─────────────────────────────────────┬───────┘
        │                                     │
┌───────▼───────┐                   ┌─────────▼─────────┐
│ FileChannel   │                   │ NetworkChannel    │
└───────────────┘                   └─────────┬─────────┘
                                              │
                                    ┌─────────┼─────────┐
                                    │         │         │
                              ┌─────▼───┐ ┌──▼──────┐ ┌▼────────┐
                              │ServerSocket│ │Socket  │ │Datagram │
                              │Channel    │ │Channel │ │Channel  │
                              └───────────┘ └─────────┘ └─────────┘
```

## 核心接口

```java
// Channel 接口
public interface Channel extends Closeable {
    boolean isOpen();                    // 是否打开
    void close() throws IOException;     // 关闭通道
}

// 可读通道
public interface ReadableByteChannel extends Channel {
    int read(ByteBuffer dst) throws IOException;
}

// 可写通道
public interface WritableByteChannel extends Channel {
    int write(ByteBuffer src) throws IOException;
}

// 可读写通道
public interface ByteChannel extends ReadableByteChannel, WritableByteChannel {
}
```

## FileChannel

FileChannel 是用于文件操作的通道，支持随机访问。

### 获取 FileChannel

```java
// 方式1：通过 FileInputStream/FileOutputStream/RandomAccessFile
FileInputStream fis = new FileInputStream("file.txt");
FileChannel channel1 = fis.getChannel();

FileOutputStream fos = new FileOutputStream("file.txt");
FileChannel channel2 = fos.getChannel();

RandomAccessFile raf = new RandomAccessFile("file.txt", "rw");
FileChannel channel3 = raf.getChannel();

// 方式2：通过 FileChannel.open (JDK 7+)
FileChannel channel = FileChannel.open(Paths.get("file.txt"), 
    StandardOpenOption.READ,
    StandardOpenOption.WRITE);
```

### 核心方法

```java
public abstract class FileChannel extends AbstractInterruptibleChannel
        implements ByteChannel, GatheringByteChannel, ScatteringByteChannel {
    
    // 读取
    public abstract int read(ByteBuffer dst);
    public abstract int read(ByteBuffer dst, long position);
    
    // 写入
    public abstract int write(ByteBuffer src);
    public abstract int write(ByteBuffer src, long position);
    
    // 强制刷新到磁盘
    public abstract void force(boolean metaData);
    
    // 截断
    public abstract FileChannel truncate(long size);
    
    // 锁定
    public abstract FileLock lock(long position, long size, boolean shared);
    
    // 映射到内存
    public abstract MappedByteBuffer map(MapMode mode, long position, long size);
    
    // 传输数据
    public abstract long transferTo(long position, long count, WritableByteChannel target);
    public abstract long transferFrom(ReadableByteChannel src, long position, long count);
    
    // 大小
    public abstract long size();
    
    // 位置
    public abstract long position();
    public abstract FileChannel position(long newPosition);
}
```

## 文件读写示例

### 基本读写

```java
// 写入文件
public void writeFile(String content) throws IOException {
    FileChannel channel = FileChannel.open(
        Paths.get("test.txt"),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
    );
    
    ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
    channel.write(buffer);
    channel.close();
}

// 读取文件
public String readFile() throws IOException {
    FileChannel channel = FileChannel.open(
        Paths.get("test.txt"),
        StandardOpenOption.READ
    );
    
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    channel.read(buffer);
    buffer.flip();
    
    String content = StandardCharsets.UTF_8.decode(buffer).toString();
    channel.close();
    return content;
}
```

### 随机访问

```java
// 写入文件的不同位置
FileChannel channel = FileChannel.open(
    Paths.get("test.txt"),
    StandardOpenOption.READ,
    StandardOpenOption.WRITE
);

// 在位置 0 写入
channel.position(0);
channel.write(ByteBuffer.wrap("Hello".getBytes()));

// 在位置 100 写入
channel.position(100);
channel.write(ByteBuffer.wrap("World".getBytes()));

channel.close();
```

## 内存映射文件

内存映射（Memory Mapping）将文件直接映射到内存，省去读写系统调用。

```java
// 内存映射读取
public void memoryMapRead() throws IOException {
    FileChannel channel = FileChannel.open(Paths.get("largefile.dat"));
    
    // 只读映射
    MappedByteBuffer buffer = channel.map(
        FileChannel.MapMode.READ_ONLY,
        0,
        channel.size()
    );
    
    // 直接在内存中读取
    while (buffer.hasRemaining()) {
        System.out.print((char) buffer.get());
    }
    
    channel.close();
}

// 内存映射写入
public void memoryMapWrite() throws IOException {
    FileChannel channel = FileChannel.open(
        Paths.get("output.dat"),
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE
    );
    
    // 可读写映射
    MappedByteBuffer buffer = channel.map(
        FileChannel.MapMode.READ_WRITE,
        0,
        1024 * 1024  // 1MB
    );
    
    buffer.put("Hello, Memory Mapping!".getBytes());
    
    // 强制同步到磁盘
    buffer.force();
    
    channel.close();
}
```

### 内存映射原理

```
传统读取:                      内存映射:
[程序] → read() → [内核缓冲区] → [磁盘]
   ↑                              ↑
  复制操作                      直接访问

内存映射:
[程序] ←→ [内存页] ←→ [磁盘]
          ↑
     OS 自动同步
```

## 文件锁定

```java
// 锁定文件
FileChannel channel = FileChannel.open(Paths.get("locked.txt"));

// 独占锁（排他锁）
FileLock lock = channel.lock();
try {
    // 执行需要独占的操作
} finally {
    lock.release();
}

// 共享锁（允许多个读）
FileLock lock = channel.lock(0, 100, true);  // shared=true
```

## 零拷贝传输

transferTo() 可以实现零拷贝，减少数据复制：

```java
// 传统文件传输（4次拷贝）
// 磁盘 → 内核缓冲区 → 用户缓冲区 → Socket缓冲区 → 网络

// transferTo 零拷贝（2次拷贝）
// 磁盘 → 内核缓冲区 → 网络

public void transferTo(String src, String dst) throws IOException {
    FileChannel srcChannel = FileChannel.open(Paths.get(src));
    FileChannel dstChannel = FileChannel.open(Paths.get(dst),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    
    long transferred = 0;
    long size = srcChannel.size();
    
    while (transferred < size) {
        transferred += srcChannel.transferTo(
            transferred,
            size - transferred,
            dstChannel
        );
    }
    
    srcChannel.close();
    dstChannel.close();
}
```

### 传输过程对比

```
传统复制:
磁盘 ──copy──→ 内核缓冲区 ──copy──→ 用户缓冲区 ──copy──→ Socket缓冲区 ──→ 网络
                (read)             (write)

transferTo:
磁盘 ──copy──→ 内核缓冲区 ──────────────────────────────────────────→ 网络
                      (sendfile 系统调用)
```

## 实战：文件复制工具

```java
public class FileCopyUtil {
    
    public static void copyFile(String src, String dst) throws IOException {
        try (FileChannel srcChannel = FileChannel.open(Paths.get(src));
             FileChannel dstChannel = FileChannel.open(Paths.get(dst),
                 StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE,
                 StandardOpenOption.TRUNCATE_EXISTING)) {
            
            // 方式1：直接传输（适合大文件）
            long size = srcChannel.size();
            long transferred = 0;
            while (transferred < size) {
                transferred += srcChannel.transferTo(
                    transferred,
                    size - transferred,
                    dstChannel
                );
            }
        }
    }
    
    public static void copyWithBuffer(String src, String dst) throws IOException {
        try (FileChannel srcChannel = FileChannel.open(Paths.get(src));
             FileChannel dstChannel = FileChannel.open(Paths.get(dst),
                 StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE)) {
            
            ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
            while (srcChannel.read(buffer) != -1) {
                buffer.flip();
                dstChannel.write(buffer);
                buffer.clear();
            }
        }
    }
}
```

## 总结

- Channel 是 NIO 的核心，提供与外设的双向连接
- FileChannel 用于文件操作，支持随机访问、内存映射、文件锁定
- 内存映射适合大文件随机读写
- transferTo() 可实现零拷贝，提升传输性能

> 📚 **推荐阅读**
> - [NIO Selector 多路复用]({{< relref "post/java-io-07" >}})
