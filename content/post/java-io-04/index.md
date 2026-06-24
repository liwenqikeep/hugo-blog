---
title: "缓冲流与装饰器模式"
date: 2018-01-31
draft: false
categories: ["Java"]
tags: ["IO", "BufferedInputStream", "装饰器模式"]
---

# 缓冲流与装饰器模式

## 什么是缓冲流

缓冲流为 IO 操作提供缓冲区，减少实际 IO 次数，提高性能。

```
无缓冲：                   有缓冲：
[程序] -> [磁盘]           [程序] -> [缓冲区] -> [磁盘]
  ↓                         ↓
一次一字节                  批量读写
多次系统调用                一次系统调用
```

## 缓冲流家族

| 类 | 作用 |
|-----|------|
| BufferedInputStream | 为字节输入流提供缓冲 |
| BufferedOutputStream | 为字节输出流提供缓冲 |
| BufferedReader | 为字符输入流提供缓冲 + 行读取 |
| BufferedWriter | 为字符输出流提供缓冲 |

## BufferedInputStream 源码解析

### 类结构

```java
public class BufferedInputStream extends FilterInputStream {
    // 缓冲数组
    protected volatile byte buf[];
    
    // 缓冲区中有效字节数
    protected int count;
    
    // 当前读取位置
    protected int pos;
    
    // 标记位置（mark 操作时记录）
    protected int markpos;
    
    // 标记后最多可读取的字节数
    protected int marklimit;
}
```

### 构造函数

```java
public BufferedInputStream(InputStream in) {
    this(in, DEFAULT_BUFFER_SIZE);  // 默认 8192 字节
}

public BufferedInputStream(InputStream in, int size) {
    super(in);
    if (size <= 0) {
        throw new IllegalArgumentException("Buffer size <= 0");
    }
    // 创建指定大小的缓冲区
    buf = byte[size];
}
```

### 读取逻辑

```java
@Override
public synchronized int read() throws IOException {
    // 缓冲区已读完，需要重新填充
    if (pos >= count) {
        fill();
        if (pos >= count) {
            return -1;
        }
    }
    // 从缓冲区返回字节
    return buf[pos++] & 0xff;
}

private void fill() throws IOException {
    byte[] buffer = buf;
    int mark = markpos;
    
    // 如果有 mark，先尝试从 mark 位置开始读取
    if (mark > 0) {
        int sz = pos - mark;
        System.arraycopy(buffer, mark, buffer, 0, sz);
        pos = sz;
        markpos = 0;
    }
    
    // 读取新数据填充缓冲区
    int len = in.read(buffer, pos, buffer.length - pos);
    if (len > 0) {
        count = pos + len;
    }
}
```

### fill() 流程图

```
fill() 调用前:
┌─────────────────────────────┐
│ buf: [■ ■ ■ ■ □ □ □ □ □ □] │
│       ↑mark  ↑pos  ↑count   │
└─────────────────────────────┘

fill() 调用后:
┌─────────────────────────────┐
│ buf: [■ ■ □ □ □ □ □ □ □ □] │
│            ↑pos  ↑count    │
└─────────────────────────────┘
   ■=已有数据  □=新读取数据
```

## BufferedReader 源码解析

### 特有方法

```java
public class BufferedReader extends Reader {
    // 行读取（BufferedReader 特有）
    public String readLine() throws IOException {
        StringBuilder s = null;
        int c;
        
        while ((c = read()) != -1) {
            if (c == '\n') {
                return s.toString();
            }
            if (s == null) {
                s = new StringBuilder();
            }
            s.append((char) c);
        }
        
        // 文件以 \r 结尾
        if (s != null && s.length() > 0) {
            return s.toString();
        }
        return null;
    }
}
```

## 装饰器模式

### 什么是装饰器模式

装饰器模式（Decorator Pattern）动态地给对象添加额外职责，比继承更灵活。

```
传统继承:                    装饰器模式:
InputStream                InputStream
    │                          │
    ├──FileInputStream        ├──InputStream (抽象组件)
    ├──ByteArrayInputStream   │     │
    └──...                    │     ├──FileInputStream (具体组件)
                              │     └──...
                         ┌────┴────┐
                         │FilterInputStream
                         │ (装饰器基类)
                         │
                    ┌─────┴─────┐
                    │           │
              BufferedInput   DataInput
```

### FilterInputStream 源码

```java
public class FilterInputStream extends InputStream {
    // 被装饰的输入流
    protected volatile InputStream in;
    
    protected FilterInputStream(InputStream in) {
        this.in = in;
    }
    
    @Override
    public int read() throws IOException {
        return in.read();  // 默认委托给被装饰对象
    }
    
    @Override
    public int read(byte[] b) throws IOException {
        return in.read(b);
    }
}
```

### 装饰器组合

```java
// 多层装饰器组合
DataInputStream dis = new DataInputStream(
    new BufferedInputStream(
        new FileInputStream("data.txt")
    )
);

// 等价于:
// FileInputStream 读取字节
// BufferedInputStream 提供缓冲
// DataInputStream 提供数据类型解析
```

## 性能对比

### 测试代码

```java
public class BufferPerformanceTest {
    public static void main(String[] args) throws Exception {
        File file = new File("test.dat");
        byte[] data = new byte[1024 * 1024];  // 1MB
        
        // 写入测试数据
        new Random().nextBytes(data);
        
        // 无缓冲写入
        long time1 = writeWithoutBuffer(data);
        
        // 有缓冲写入
        long time2 = writeWithBuffer(data);
        
        System.out.println("无缓冲: " + time1 + "ms");
        System.out.println("有缓冲: " + time2 + "ms");
    }
    
    private static long writeWithoutBuffer(byte[] data) {
        long start = System.currentTimeMillis();
        try (OutputStream os = new FileOutputStream("test.dat")) {
            for (byte b : data) {
                os.write(b);  // 每次都调用系统 IO
            }
        }
        return System.currentTimeMillis() - start;
    }
    
    private static long writeWithBuffer(byte[] data) {
        long start = System.currentTimeMillis();
        try (OutputStream os = new BufferedOutputStream(
                new FileOutputStream("test.dat"))) {
            os.write(data);  // 先写入缓冲区
            os.flush();      // 一次性刷出
        }
        return System.currentTimeMillis() - start;
    }
}
```

### 结果示意

```
无缓冲写入 1MB: ~150ms (1,000,000 次系统调用)
有缓冲写入 1MB: ~15ms  (1 次系统调用)

性能提升: ~10x
```

## 最佳实践

```java
// ✅ 推荐：使用缓冲流包装底层流
try (BufferedReader reader = new BufferedReader(
        new FileReader("file.txt"))) {
    String line;
    while ((line = reader.readLine()) != null) {
        // 处理行
    }
}

// ✅ 推荐：使用缓冲流 + 指定缓冲区大小
try (BufferedInputStream bis = new BufferedInputStream(
        new FileInputStream("file.dat"), 64 * 1024)) {  // 64KB
    // ...
}

// ❌ 不推荐：嵌套多个相同类型的缓冲流
new BufferedBufferedInputStream(  // 多余
    new BufferedInputStream(
        new FileInputStream("file.dat")
    )
);
```

## 总结

- 缓冲流通过减少系统 IO 调用提升性能
- BufferedInputStream/BufferedOutputStream 处理字节
- BufferedReader/BufferedWriter 处理字符，支持行读取
- 装饰器模式让 IO 流可以灵活组合
- 默认缓冲区大小为 8KB，可根据场景调整

> 📚 **推荐阅读**
> - [NIO Buffer 深度解析]({{< relref "post/java-io-05" >}})
