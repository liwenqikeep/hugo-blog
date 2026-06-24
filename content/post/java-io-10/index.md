---
title: "AIO 异步 IO 与 CompletionHandler"
date: 2018-02-10
draft: false
categories: ["Java"]
tags: ["AIO", "AsynchronousChannel", "CompletionHandler"]
---

# AIO 异步 IO 与 CompletionHandler

## AIO 概述

AIO（Asynchronous IO）是 Java 7 引入的异步非阻塞 IO，告别轮询时代。

```
BIO:                          NIO:                        AIO:
[线程] ──阻塞读──→ [等待]     [线程] ←─select── [就绪?]    [线程] ──发起读──→ [完成?]
    ↑                          ↓                            ↓
返回数据                    返回 Channel               注册回调
    ↑                          ↓                            ↓
继续处理                    主动轮询                    OS 通知
```

## 异步通道组

```java
// 创建异步通道组（线程池）
AsynchronousChannelGroup group = AsynchronousChannelGroup
    .withFixedThreadPool(4, Executors.defaultThreadFactory());

// 或使用系统默认
AsynchronousChannelGroup group = 
    AsynchronousChannelGroup.withCachedThreadPool(
        Executors.newCachedThreadPool(), 
        10
    );
```

## AsynchronousFileChannel

### 创建

```java
// 打开文件
AsynchronousFileChannel channel = AsynchronousFileChannel.open(
    Paths.get("test.txt"),
    StandardOpenOption.READ,
    StandardOpenOption.WRITE
);

// 使用通道组
AsynchronousFileChannel channel = AsynchronousFileChannel.open(
    Paths.get("test.txt"),
    Set.of(StandardOpenOption.READ),
    group
);
```

### 读取

```java
// 方式1：使用 Future
Future<Integer> future = channel.read(
    ByteBuffer.allocate(1024),  // 缓冲区
    0                           // 文件位置
);

// 阻塞等待结果
Integer bytesRead = future.get();
System.out.println("读取了 " + bytesRead + " 字节");

// 方式2：使用 CompletionHandler 回调
channel.read(
    ByteBuffer.allocate(1024),
    0,
    null,
    new CompletionHandler<Integer, ByteBuffer>() {
        @Override
        public void completed(Integer result, ByteBuffer buffer) {
            System.out.println("读取了 " + result + " 字节");
            buffer.flip();
            // 处理数据
        }
        
        @Override
        public void failed(Throwable exc, ByteBuffer buffer) {
            exc.printStackTrace();
        }
    }
);
```

### 写入

```java
// Future 方式
ByteBuffer buffer = ByteBuffer.allocate(1024);
buffer.put("Hello, AIO!".getBytes());
buffer.flip();

Future<Integer> future = channel.write(buffer, 0);
Integer bytesWritten = future.get();

// CompletionHandler 方式
channel.write(
    buffer,
    0,
    buffer,
    new CompletionHandler<Integer, ByteBuffer>() {
        @Override
        public void completed(Integer result, ByteBuffer buffer) {
            System.out.println("写入了 " + result + " 字节");
        }
        
        @Override
        public void failed(Throwable exc, ByteBuffer buffer) {
            exc.printStackTrace();
        }
    }
);
```

## AsynchronousServerSocketChannel

### 服务器示例

```java
public class AIOHttpServer {
    private static final int PORT = 8080;
    
    public static void main(String[] args) throws Exception {
        AsynchronousServerSocketChannel server = 
            AsynchronousServerSocketChannel.open();
        
        server.bind(new InetSocketAddress(PORT));
        System.out.println("AIO Server started on port " + PORT);
        
        // 接受连接
        server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel client, Void attachment) {
                // 继续接受下一个连接
                server.accept(null, this);
                
                // 处理客户端请求
                handleClient(client);
            }
            
            @Override
            public void failed(Throwable exc, Void attachment) {
                exc.printStackTrace();
            }
        });
        
        // 防止主线程退出
        Thread.sleep(Long.MAX_VALUE);
    }
    
    private static void handleClient(AsynchronousSocketChannel client) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        
        client.read(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer buffer) {
                if (result == -1) {
                    try {
                        client.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return;
                }
                
                buffer.flip();
                String request = StandardCharsets.UTF_8.decode(buffer).toString();
                System.out.println("Request: " + request);
                
                // 响应
                String response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 2\r\n" +
                    "\r\n" +
                    "OK";
                
                ByteBuffer responseBuffer = 
                    ByteBuffer.wrap(response.getBytes());
                
                client.write(responseBuffer, responseBuffer, 
                    new CompletionHandler<Integer, ByteBuffer>() {
                        @Override
                        public void completed(Integer result, ByteBuffer buffer) {
                            try {
                                client.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                        
                        @Override
                        public void failed(Throwable exc, ByteBuffer buffer) {
                            exc.printStackTrace();
                        }
                    }
                );
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer buffer) {
                exc.printStackTrace();
            }
        });
    }
}
```

## CompletionHandler 接口

```java
public interface CompletionHandler<V, A> {
    // 操作成功完成时调用
    void completed(V result, A attachment);
    
    // 操作失败时调用
    void failed(Throwable exc, A attachment);
}
```

### 参数说明

| 参数 | 说明 |
|------|------|
| V result | 操作结果（读取字节数、写入字节数等） |
| A attachment | 附加对象，用于在回调中传递上下文 |

### 使用场景

```java
// 使用 attachment 传递上下文
public class HttpHandler {
    public void handle(AsynchronousSocketChannel client) {
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        ByteBuffer writeBuffer = ByteBuffer.allocate(1024);
        
        // 创建上下文
        HttpContext context = new HttpContext(client, readBuffer, writeBuffer);
        
        client.read(readBuffer, context, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, HttpContext ctx) {
                // 可以访问上下文中的所有数据
                ctx.getClient();
                ctx.getReadBuffer();
                ctx.getWriteBuffer();
            }
        });
    }
}

class HttpContext {
    private final AsynchronousSocketChannel client;
    private final ByteBuffer readBuffer;
    private final ByteBuffer writeBuffer;
    
    public HttpContext(AsynchronousSocketChannel client, 
                      ByteBuffer readBuffer, ByteBuffer writeBuffer) {
        this.client = client;
        this.readBuffer = readBuffer;
        this.writeBuffer = writeBuffer;
    }
    
    // getters...
}
```

## Future vs CompletionHandler

```java
// Future 方式：同步等待结果
Future<Integer> future = channel.read(buffer, 0);
while (!future.isDone()) {
    // 可以做其他事情
}
Integer result = future.get();  // 阻塞获取结果

// CompletionHandler 方式：回调通知
channel.read(buffer, 0, null, new CompletionHandler<>() {
    @Override
    public void completed(Integer result, ByteBuffer buffer) {
        // 自动回调，结果在这里处理
    }
});
// 继续执行其他代码，不阻塞
```

## 超时处理

```java
// 设置超时
AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();

// 使用 timeout 参数
channel.read(
    buffer,
    5000,  // 5秒超时
    TimeUnit.MILLISECONDS,
    null,
    new CompletionHandler<>() {
        @Override
        public void completed(Integer result, Void attachment) {
            // 读取成功
        }
        
        @Override
        public void failed(Throwable exc, Void attachment) {
            if (exc instanceof InterruptedByTimeoutException) {
                System.out.println("读取超时");
            }
        }
    }
);
```

## 完整示例：异步文件复制

```java
public class AsyncFileCopy {
    
    public Future<Long> copy(String src, String dst) {
        try {
            AsynchronousFileChannel srcChannel = AsynchronousFileChannel.open(
                Paths.get(src), StandardOpenOption.READ);
            AsynchronousFileChannel dstChannel = AsynchronousFileChannel.open(
                Paths.get(dst), 
                StandardOpenOption.CREATE, 
                StandardOpenOption.WRITE);
            
            final long[] totalBytes = {0};
            long size = srcChannel.size();
            
            return copyRecursive(srcChannel, dstChannel, 0, size);
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    private Future<Long> copyRecursive(
            AsynchronousFileChannel src, 
            AsynchronousFileChannel dst,
            long position,
            long size) {
        
        if (position >= size) {
            return CompletableFuture.completedFuture(size);
        }
        
        ByteBuffer buffer = ByteBuffer.allocateDirect(8192);
        
        return src.read(buffer, position).thenCompose(bytesRead -> {
            if (bytesRead == -1) {
                return CompletableFuture.completedFuture(position);
            }
            
            buffer.flip();
            return dst.write(buffer, position).thenCompose(bytesWritten -> {
                return copyRecursive(src, dst, position + bytesWritten, size);
            });
        });
    }
}
```

## 与 NIO 对比

| 特性 | NIO | AIO |
|------|-----|-----|
| 编程模型 | Reactor（主动轮询） | Proactor（被动通知） |
| 线程模型 | 单线程 + Selector | 线程池 + 回调 |
| 适用场景 | 中等并发 | 高并发 |
| API 复杂度 | 较低 | 较高 |
| 性能 | 好 | 更好 |

## 总结

- AIO 是真正的异步 IO，无需轮询
- AsynchronousFileChannel 处理异步文件操作
- AsynchronousServerSocketChannel/AsynchronousSocketChannel 处理异步网络
- CompletionHandler 替代 Future 做回调
- 适合高并发、长连接场景

## 系列总结

本系列文章涵盖了 Java IO 的核心内容：

1. **BIO/NIO/AIO 对比** - 三种 IO 模型的特点与适用场景
2. **File 类** - 文件操作的基础
3. **字节流/字符流** - InputStream/OutputStream/Reader/Writer
4. **缓冲流与装饰器模式** - 性能优化与设计模式
5. **NIO Buffer** - 缓冲区结构与状态转换
6. **NIO Channel** - 通道机制与零拷贝
7. **NIO Selector** - 多路复用与 Reactor 模型
8. **手写 HTTP 服务器** - NIO 实战应用
9. **AIO 异步 IO** - 异步编程范式
