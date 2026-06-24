---
title: "NIO Selector 多路复用"
date: 2018-02-06
draft: false
categories: ["Java"]
tags: ["NIO", "Selector", "多路复用", "Reactor"]
---

# NIO Selector 多路复用

## 什么是 Selector

Selector（选择器）是 NIO 的核心组件，用于监控多个 Channel 的 IO 状态，实现单线程管理多连接。

```
传统 BIO:                    NIO Selector:
[线程1] → [连接1]           [线程] → [Selector] → [连接1]
[线程2] → [连接2]           ↓                   [连接2]
[线程3] → [连接3]           监控 IO 状态        [连接3]
  ...                       ↓
                          处理就绪事件
```

## 为什么需要 Selector

假设有 10000 个连接：
- BIO：需要 10000 个线程，线程栈占用 ~1MB → ~10GB 内存
- NIO Selector：只需 1 个线程 + 1 个 Selector → ~10MB 内存

## Selector 创建

```java
// 创建 Selector
Selector selector = Selector.open();

// 检查是否打开
System.out.println(selector.isOpen());  // true
```

## SelectionKey

SelectionKey 是注册到 Selector 的 Channel 的标记：

```java
public abstract class SelectionKey {
    // 兴趣操作位
    public static final int OP_READ = 1 << 0;      // 1
    public static final int OP_WRITE = 1 << 2;    // 4
    public static final int OP_CONNECT = 1 << 3;  // 8
    public static final int OP_ACCEPT = 1 << 4;   // 16
    
    // 获取关联的 Channel
    public abstract SelectableChannel channel();
    
    // 获取关联的 Selector
    public abstract Selector selector();
    
    // 是否就绪
    public abstract boolean isReadable();
    public abstract boolean isWritable();
    public abstract boolean isConnectable();
    public abstract boolean isAcceptable();
    
    // 附加对象
    public Object attach(Object ob);
    public Object attachment();
}
```

## Channel 注册

```java
ServerSocketChannel serverChannel = ServerSocketChannel.open();
serverChannel.bind(new InetSocketAddress(8080));
serverChannel.configureBlocking(false);  // 必须设置为非阻塞

// 注册到 Selector，监听 Accept 事件
SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);

// 注册多个兴趣
key = serverChannel.register(selector, 
    SelectionKey.OP_READ | SelectionKey.OP_WRITE);
```

## Selector 使用流程

```java
Selector selector = Selector.open();

while (true) {
    // 1. 阻塞等待就绪事件
    int readyChannels = selector.select();
    
    if (readyChannels == 0) continue;
    
    // 2. 获取就绪的 SelectionKey
    Set<SelectionKey> selectedKeys = selector.selectedKeys();
    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
    
    while (keyIterator.hasNext()) {
        SelectionKey key = keyIterator.next();
        
        // 3. 处理事件
        if (key.isAcceptable()) {
            // 接受连接
        }
        if (key.isReadable()) {
            // 读取数据
        }
        if (key.isWritable()) {
            // 写入数据
        }
        
        // 4. 移除已处理的 Key
        keyIterator.remove();
    }
}
```

## 完整示例：Echo Server

```java
public class NIOEchoServer {
    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();
        
        // 1. 打开 ServerSocketChannel
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(new InetSocketAddress(8080));
        serverChannel.configureBlocking(false);
        
        // 2. 注册到 Selector
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        System.out.println("Server started on port 8080");
        
        // 3. 事件循环
        while (true) {
            selector.select();  // 阻塞等待
            
            for (SelectionKey key : selector.selectedKeys()) {
                try {
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    }
                    if (key.isReadable()) {
                        handleRead(key);
                    }
                } catch (IOException e) {
                    key.cancel();
                    key.channel().close();
                }
            }
            selector.selectedKeys().clear();
        }
    }
    
    private static void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        
        // 注册读事件
        client.register(key.selector(), SelectionKey.OP_READ);
        System.out.println("Client connected: " + client);
    }
    
    private static void handleRead(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        int read = client.read(buffer);
        if (read == -1) {
            client.close();
            System.out.println("Client disconnected");
            return;
        }
        
        buffer.flip();
        client.write(buffer);  // Echo back
        buffer.clear();
    }
}
```

## select() 方法族

```java
// 阻塞直到至少有一个 Channel 就绪
int select();

// 阻塞指定时间
int select(long timeout);

// 立即返回，不阻塞
int selectNow();

// wakeup() 使正在阻塞的 select 立即返回
selector.wakeup();
```

## select() 状态管理

```java
// select 过程中 Channel 变化
ServerSocketChannel ch = ServerSocketChannel.open();
ch.configureBlocking(false);
ch.register(selector, SelectionKey.OP_ACCEPT);

// 当 accept() 被调用时：
// 1. Channel 从 unregister → register
// 2. Selector.wakeup() 使 select() 立即返回
```

## Reactor 模型

Selector 是 Reactor 模型的核心实现：

```
Reactor 单线程模型:
┌─────────────────────────────────────────┐
│                 Reactor                  │
│  ┌─────────────────────────────────────┐ │
│  │           Selector                  │ │
│  │   [连接1]  [连接2]  [连接3]  ...   │ │
│  └──────────────┬──────────────────────┘ │
│                 │                         │
│         ┌───────┴───────┐                  │
│         │   事件分发     │                  │
│         └───────┬───────┘                  │
│         ┌───────┴───────┐                  │
│         │   Handler     │                  │
│         │ (读/处理/写)   │                  │
│         └───────────────┘                  │
└─────────────────────────────────────────┘

Reactor 多线程模型:
┌─────────────────────────────────────────┐
│              Main Reactor                │
│  ┌─────────────────────────────────────┐ │
│  │      Acceptor (接受连接)            │ │
│  └──────────────┬──────────────────────┘ │
│                 │                         │
│    ┌────────────┼────────────┐           │
│    ↓            ↓            ↓           │
│ ┌──────┐   ┌──────┐    ┌──────┐         │
│ │SubR 1│   │SubR 2│    │SubR 3│  ...   │
│ └──┬───┘   └──┬───┘    └──┬───┘         │
│    │          │           │             │
│  Handler   Handler    Handler          │
│  (业务线程池)                        │
└─────────────────────────────────────────┘
```

## 常见问题

### 1. 空轮询 Bug

```java
// 问题：Selector.select() 可能什么都没发生就返回
while (true) {
    int count = selector.select();
    if (count == 0) {
        // 可能什么都没就绪
        continue;
    }
    // ...
}

// 解决方案：使用 wakeup() 或处理空轮询
Selector selector = Selector.open();
// ...
selector.wakeup();  // 唤醒
```

### 2. 并发问题

```java
// ❌ 错误：在不同线程中注册
new Thread(() -> {
    channel.register(selector, SelectionKey.OP_READ);  // 可能冲突
});

// ✅ 正确：在同一个线程中注册
synchronized (channel) {
    channel.register(selector, SelectionKey.OP_READ);
}
```

### 3. OP_WRITE 的使用

```java
// ❌ 过度使用 OP_WRITE
key.interestOps(SelectionKey.OP_WRITE);  // 每次可写都触发

// ✅ 按需启用
if (buffer.hasRemaining()) {
    key.interestOps(SelectionKey.OP_WRITE);
} else {
    key.interestOps(SelectionKey.OP_READ);
}
```

## 性能优化

```java
// 1. 使用足够大的 Selector 数组
Selector[] selectors = new Selector[numThreads];
for (int i = 0; i < numThreads; i++) {
    selectors[i] = Selector.open();
}

// 2. 使用 keys() vs selectedKeys()
Set<SelectionKey> allKeys = selector.keys();      // 所有注册的 key
Set<SelectionKey> readyKeys = selector.selectedKeys();  // 已就绪的 key

// 3. 及时取消无效的 key
if (channel.socket().isClosed()) {
    key.cancel();
}
```

## 总结

- Selector 允许单线程管理多个 Channel
- 通过 SelectionKey 表示 Channel 的就绪状态
- select() 阻塞直到有 Channel 就绪
- Reactor 模型是 Selector 的经典应用
- 注意空轮询和并发问题

> 📚 **推荐阅读**
> - [NIO 实战：手写 HTTP 服务器]({{< relref "post/java-io-08" >}})
