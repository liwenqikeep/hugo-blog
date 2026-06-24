---
title: "NIO 实战：手写 HTTP 服务器"
date: 2018-02-08
draft: false
categories: ["Java"]
tags: ["NIO", "HTTP", "SocketChannel"]
---

# NIO 实战：手写 HTTP 服务器

## 目标

实现一个简单的 HTTP 服务器，支持：
- 处理 GET 请求
- 返回静态文件
- 处理 404、500 错误
- 非阻塞 IO

## 整体架构

```
┌─────────────────────────────────────────────────────┐
│                    HttpServer                        │
│  ┌─────────────────────────────────────────────┐   │
│  │              Boss Thread (Selector)           │   │
│  │              监听 OP_ACCEPT                   │   │
│  └──────────────────────┬────────────────────────┘   │
│                         │                            │
│                   accept 连接                        │
│                         ↓                            │
│  ┌─────────────────────────────────────────────┐   │
│  │              Worker Threads (Selector)        │   │
│  │              处理 OP_READ/OP_WRITE           │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## 完整代码

### HttpServer

```java
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NIOHttpServer {
    
    private static final int PORT = 8080;
    private static final String WEB_ROOT = "./www";
    private static final int BUFFER_SIZE = 8192;
    
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final ExecutorService executor;
    
    public NIOHttpServer() throws IOException {
        // 创建 Selector
        this.selector = Selector.open();
        
        // 创建 ServerSocketChannel
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.socket().bind(new InetSocketAddress(PORT));
        this.serverChannel.configureBlocking(false);
        
        // 注册到 Selector
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        // 业务线程池
        this.executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
        
        System.out.println("HTTP Server started on port " + PORT);
    }
    
    public void start() {
        try {
            while (true) {
                selector.select();
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    }
                    if (key.isReadable()) {
                        handleRead(key);
                    }
                    if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();
        client.configureBlocking(false);
        
        // 注册读事件
        client.register(selector, SelectionKey.OP_READ);
        System.out.println("Client connected: " + client);
    }
    
    private void handleRead(SelectionKey key) {
        SocketChannel client = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        
        try {
            int read = client.read(buffer);
            if (read == -1) {
                client.close();
                return;
            }
            
            if (read > 0) {
                buffer.flip();
                byte[] data = new byte[buffer.limit()];
                buffer.get(data);
                String request = new String(data);
                
                System.out.println("Request:\n" + request);
                
                // 提交到线程池处理
                executor.submit(() -> processRequest(client, request));
            }
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    
    private void processRequest(SocketChannel client, String request) {
        try {
            // 解析请求行
            String[] lines = request.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            
            if (requestLine.length < 3) {
                sendError(client, 400, "Bad Request");
                return;
            }
            
            String method = requestLine[0];
            String path = requestLine[1];
            
            // 只支持 GET
            if (!"GET".equals(method)) {
                sendError(client, 405, "Method Not Allowed");
                return;
            }
            
            // 防止路径遍历
            if (path.contains("..")) {
                sendError(client, 403, "Forbidden");
                return;
            }
            
            // 默认 index.html
            if ("/".equals(path)) {
                path = "/index.html";
            }
            
            // 读取文件
            Path filePath = Paths.get(WEB_ROOT, path);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                byte[] content = Files.readAllBytes(filePath);
                String contentType = getContentType(path);
                sendResponse(client, 200, "OK", contentType, content);
            } else {
                sendError(client, 404, "Not Found");
            }
            
        } catch (IOException e) {
            sendError(client, 500, "Internal Server Error");
        }
    }
    
    private void handleWrite(SelectionKey key) {
        // 响应已发送，关闭连接
        SocketChannel client = (SocketChannel) key.channel();
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendResponse(SocketChannel client, int status, 
                             String statusText, String contentType, 
                             byte[] content) throws IOException {
        StringBuilder response = new StringBuilder();
        
        response.append("HTTP/1.1 ").append(status).append(" ")
                .append(statusText).append("\r\n");
        response.append("Content-Type: ").append(contentType)
                .append("\r\n");
        response.append("Content-Length: ").append(content.length)
                .append("\r\n");
        response.append("Connection: close\r\n");
        response.append("\r\n");
        
        ByteBuffer buffer = ByteBuffer.allocate(
            response.length() + content.length
        );
        buffer.put(response.toString().getBytes());
        buffer.put(content);
        buffer.flip();
        
        client.write(buffer);
        client.close();
    }
    
    private void sendError(SocketChannel client, int status, 
                          String message) {
        String body = "<html><body><h1>" + status + " " 
                    + message + "</h1></body></html>";
        
        try {
            sendResponse(client, status, message, 
                        "text/html", body.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
    
    public static void main(String[] args) {
        try {
            new NIOHttpServer().start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

## HTTP 请求解析

### 请求格式

```
GET /index.html HTTP/1.1\r\n
Host: localhost:8080\r\n
User-Agent: Mozilla/5.0\r\n
Accept: text/html\r\n
\r\n
[可选 body]
```

### 响应格式

```
HTTP/1.1 200 OK\r\n
Content-Type: text/html\r\n
Content-Length: 1234\r\n
\r\n
<html>...</html>
```

## 关键点解析

### 1. 非阻塞 accept

```java
// 非阻塞模式下，如果没有连接立即返回 null
SocketChannel client = server.accept();
if (client != null) {
    client.configureBlocking(false);
    client.register(selector, SelectionKey.OP_READ);
}
```

### 2. 线程池处理

```java
// 业务处理放在线程池，避免阻塞 selector
executor.submit(() -> processRequest(client, request));

// 这样 selector 可以继续处理其他连接
```

### 3. 路径安全检查

```java
// 防止路径遍历攻击
if (path.contains("..")) {
    sendError(client, 403, "Forbidden");
    return;
}
```

### 4. 缓冲区管理

```java
ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
// ...
buffer.flip();  // 切换到读模式
buffer.get(data);
buffer.clear();  // 准备下一轮读取
```

## 测试

### 启动服务器

```bash
javac NIOHttpServer.java
java NIOHttpServer
```

### 测试请求

```bash
# 访问首页
curl http://localhost:8080/

# 访问文件
curl http://localhost:8080/index.html

# 测试 404
curl http://localhost:8080/notexist.html
```

### 性能测试

```bash
# ab 是 Apache Bench
ab -n 10000 -c 100 http://localhost:8080/index.html
```

## 改进方向

1. **连接池**：复用 SocketChannel
2. **缓存**：缓存热门文件
3. **压缩**：Gzip 压缩响应
4. **Keep-Alive**：支持长连接
5. **HTTPS**：添加 SSL/TLS

## 总结

- 使用 NIO 实现非阻塞 HTTP 服务器
- Selector 监控多个连接，事件驱动
- 业务处理交给线程池，避免阻塞
- 正确处理 HTTP 协议格式

> 📚 **推荐阅读**
> - [AIO 异步 IO 与 CompletionHandler]({{< relref "post/java-io-10" >}})
