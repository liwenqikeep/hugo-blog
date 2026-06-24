---
title: "IO 流体系：字节流与字符流"
date: 2018-01-29
draft: false
categories: ["Java"]
tags: ["IO", "InputStream", "OutputStream", "Reader", "Writer"]
---

# IO 流体系：字节流与字符流

## IO 流概述

流（Stream）是一组有序的字节序列，用于数据传输。Java 将 IO 抽象为流，主要分为：

```
                    ┌─────────────┐
                    │   数据源    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   输入流    │
                    │ (InputStream│
                    │  /Reader)  │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   程序      │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   输出流    │
                    │(OutputStream│
                    │  /Writer)  │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  数据目的地  │
                    └─────────────┘
```

## 字节流体系

### 抽象基类

```java
// 输入字节流
public abstract class InputStream
    implements Closeable {
    
    // 读取单个字节
    public abstract int read();
    
    // 读取到字节数组
    public int read(byte[] b);
    
    // 读取到字节数组（指定范围）
    public int read(byte[] b, int off, int len);
    
    // 跳过 n 个字节
    public long skip(long n);
    
    // 可用字节数
    public int available();
    
    // 关闭流
    public void close();
}

// 输出字节流
public abstract class OutputStream
    implements Closeable, Flushable {
    
    // 写入单个字节
    public abstract void write(int b);
    
    // 写入字节数组
    public void write(byte[] b);
    
    // 写入字节数组（指定范围）
    public void write(byte[] b, int off, int len);
    
    // 刷新缓冲区
    public void flush();
    
    // 关闭流
    public void close();
}
```

### 常用实现类

| 类 | 作用 | 源码位置 |
|-----|------|----------|
| FileInputStream | 读取文件字节 | JDK 内置 |
| FileOutputStream | 写入文件字节 | JDK 内置 |
| ByteArrayInputStream | 读取字节数组 | JDK 内置 |
| ByteArrayOutputStream | 写入字节数组 | JDK 内置 |
| PipedInputStream | 管道输入 | JDK 内置 |

### FileInputStream 源码解析

```java
public class FileInputStream extends InputStream {
    // 文件描述符
    private final FileDescriptor fd;
    
    // 文件通道
    private final FileChannel channel;
    
    // 构造函数
    public FileInputStream(String name) throws FileNotFoundException {
        this(name != null ? new File(name) : null);
    }
    
    public FileInputStream(File file) throws FileNotFoundException {
        // 打开文件，委托给 FileSystem
        fd = FileRandomAccess.open(file.getPath());
    }
    
    @Override
    public int read() throws IOException {
        // 调用本地方法读取单个字节
        return read0();
    }
    
    private native int read0() throws IOException;
    
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        // 调用本地方法批量读取
        return readBytes(b, off, len);
    }
    
    private native int readBytes(byte[] b, int off, int len) throws IOException;
    
    // 获取 FileChannel
    public FileChannel getChannel() {
        return channel;
    }
}
```

## 字符流体系

### 为什么要字符流

字节流按字节处理数据，字符流按字符处理：

```java
// 字节流：1 个汉字 = 3 个字节
// 字符流：1 个汉字 = 1 个字符

// 使用字节流处理中文可能乱码
FileInputStream fis = new FileInputStream("test.txt");
int b;
while ((b = fis.read()) != -1) {
    // 每次只读取 1 个字节，无法正确处理中文
}

// 使用字符流正确处理中文
FileReader fr = new FileReader("test.txt");
int c;
while ((c = fr.read()) != -1) {
    // 每次读取 1 个字符，中文正确
}
```

### 抽象基类

```java
// 字符输入流
public abstract class Reader implements Readable, Closeable {
    
    protected Object lock;
    
    // 读取单个字符
    public int read() throws IOException;
    
    // 读取到字符数组
    public int read(char[] cbuf) throws IOException;
    
    // 读取到字符数组（指定范围）
    public abstract int read(char[] cbuf, int off, int len);
    
    // 跳过字符
    public long skip(long n);
}

// 字符输出流
public abstract class Writer implements Appendable, Closeable, Flushable {
    
    private char[] writeBuffer;
    
    // 写入单个字符
    public void write(int c) throws IOException;
    
    // 写入字符串
    public void write(String str) throws IOException;
    
    // 写入字符数组
    public abstract void write(char[] cbuf, int off, int len);
    
    // 追加
    public Writer append(CharSequence csq);
}
```

### InputStreamReader 与 OutputStreamWriter

这是字节流和字符流之间的桥梁：

```java
// 源码解析
public class InputStreamReader extends Reader {
    private final StreamDecoder sd;  // 解码器
    
    public InputStreamReader(InputStream in) {
        // 使用系统默认字符集
        this(in, StreamDecoder.forInputStreamReader(in));
    }
    
    public InputStreamReader(InputStream in, String charsetName) 
            throws UnsupportedEncodingException {
        this(in, StreamDecoder.forInputStreamReader(in, in, charsetName));
    }
    
    @Override
    public int read(char[] cbuf, int offset, int length) throws IOException {
        return sd.read(cbuf, offset, length);
    }
}
```

### 常用实现类

| 类 | 作用 |
|-----|------|
| FileReader | 读取文件字符 |
| FileWriter | 写入文件字符 |
| StringReader | 读取字符串 |
| StringWriter | 写入字符串 |
| CharArrayReader | 读取字符数组 |
| CharArrayWriter | 写入字符数组 |
| PipedReader | 管道输入 |
| PipedWriter | 管道输出 |

## 流的使用模式

### 复制文件示例

```java
// 字节流复制
try (InputStream is = new FileInputStream("source.jpg");
     OutputStream os = new FileOutputStream("dest.jpg")) {
    
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
    }
}

// 字符流复制（适合文本文件）
try (Reader reader = new FileReader("source.txt");
     Writer writer = new FileWriter("dest.txt")) {
    
    char[] buffer = new char[8192];
    int charsRead;
    while ((charsRead = reader.read(buffer)) != -1) {
        writer.write(buffer, 0, charsRead);
    }
}
```

### JDK 7 try-with-resources

```java
// 手动关闭（容易遗漏）
FileInputStream fis = null;
try {
    fis = new FileInputStream("test.txt");
    // ...
} finally {
    if (fis != null) fis.close();
}

// try-with-resources（自动关闭）
try (FileInputStream fis = new FileInputStream("test.txt")) {
    // ...
}  // 自动调用 close()
```

## 字符集与编码

```java
// 指定字符集读取
try (BufferedReader br = new BufferedReader(
        new InputStreamReader(
            new FileInputStream("test.txt"), "UTF-8"))) {
    // 按 UTF-8 解码
}

// JDK 10+ 推荐写法
try (BufferedReader br = Files.newBufferedReader(
        Path.of("test.txt"), StandardCharsets.UTF_8)) {
    // ...
}
```

## 总结

- 字节流以字节为单位处理数据（8 位）
- 字符流以字符为单位处理数据（16 位 Unicode）
- InputStreamReader/OutputStreamWriter 是字节流和字符流的桥梁
- 字符流内部依赖字节流 + 编码解码器
- 处理文本建议使用字符流，处理二进制数据使用字节流

> 📚 **推荐阅读**
> - [缓冲流与装饰器模式]({{< relref "post/java-io-04" >}})
