---
title: "File 类与文件操作原理"
date: 2018-01-27
draft: false
categories: ["Java"]
tags: ["IO", "File", "源码解析"]
---

# File 类与文件操作原理

## File 类概述

`java.io.File` 是文件和目录路径名的抽象表示，用于操作文件和目录。

```java
File file = new File("test.txt");
File dir = new File("/usr/local/java");
```

## 核心属性

File 类包含几个重要的内部组件：

```java
public class File implements Serializable, Comparable<File> {
    // 路径分隔符
    public static final char separatorChar = File.separatorChar;
    public static final String separator = "" + separatorChar;
    
    // 路径
    private final String path;
    // 文件系统前缀（Windows 盘符等）
    private final String prefix;
}
```

## 构造方法解析

```java
// 1. 直接指定路径
new File("test.txt");

// 2. 指定目录和文件名
new File("/home/user", "test.txt");

// 3. 指定父目录和子路径
new File(new File("/home/user"), "test.txt");
```

内部实现：

```java
public File(File parent, String child) {
    if (child == null) {
        throw new NullPointerException();
    }
    if (child.length() > 0) {
        // 拼接父目录和子路径
        this.prefix = parent.getPrefix();
        this.path = fs.resolve(parent.getPath(), child);
    } else {
        this.path = parent.getPath();
    }
}
```

## 常用方法

### 文件属性

```java
File file = new File("test.txt");

file.exists();           // 文件是否存在
file.isFile();           // 是否是文件
file.isDirectory();      // 是否是目录
file.isAbsolute();       // 是否是绝对路径
file.isHidden();         // 是否隐藏

file.length();           // 文件大小（字节）
file.lastModified();     // 最后修改时间
```

### 路径操作

```java
file.getName();          // 获取文件名
file.getPath();          // 获取路径
file.getAbsolutePath();  // 获取绝对路径
file.getParent();        // 获取父目录路径

file.getParentFile();    // 获取父目录 File 对象
file.getCanonicalPath();  // 获取规范路径（解析 . 和 ..
```

### 文件操作

```java
// 创建文件
file.createNewFile();

// 创建目录
file.mkdir();            // 创建单级目录
file.mkdirs();           // 创建多级目录

// 删除
file.delete();           // 删除文件或空目录
file.deleteOnExit();     // JVM 退出时删除

// 重命名
file.renameTo(dest);

// 检查/创建目录
file.mkdir();            // 创建目录（父目录不存在返回 false）
file.mkdirs();           // 创建目录（自动创建父目录）
```

## 文件过滤器

```java
// 列出所有 .txt 文件
File dir = new File("/path/to/dir");
File[] txtFiles = dir.listFiles((d, name) -> name.endsWith(".txt"));

// 列出所有目录
File[] dirs = dir.listFiles(File::isDirectory);
```

## 路径解析原理

File 类依赖 `FileSystem` 来操作文件系统：

```java
// 获取文件系统
FileSystem fs = FileSystem.getFileSystem();

// 解析路径
public String resolve(String parent, String child) {
    if (child.isEmpty()) {
        return parent;
    }
    if (child.charAt(0) == separatorChar) {
        // 绝对路径
        return child;
    }
    // 相对路径：拼接父路径
    return parent + separatorChar + child;
}
```

## 跨平台路径处理

```java
// ❌ 错误：硬编码路径分隔符
String path = "D:\\data\\file.txt";  // Windows 专用

// ✅ 正确：使用 File.separator
String path = "D:" + File.separator + "data" + File.separator + "file.txt";

// ✅ 最佳：直接使用 File
File file = new File("D:", "data", "file.txt");

// ✅ 最佳：使用 Paths（JDK 7+）
Path path = Paths.get("D:", "data", "file.txt");
```

## 常见问题

### 1. File 对象不代表实际文件

```java
File file = new File("nonexistent.txt");
file.exists();  // false

// 创建 File 对象不会创建文件
file.createNewFile();  // 才会真正创建文件
```

### 2. 路径中的空格和中文

```java
// Windows 路径处理
File file = new File("C:\\Users\\用户名\\Documents");

// 建议使用 URI
File file = new File(new URI("file:///C:/Users/%E7%94%A8%E6%88%B7/Documents"));
```

### 3. 文件名大小写

```java
// Windows 不区分大小写
new File("test.txt").equals(new File("Test.txt"));  // true

// Linux 区分大小写
new File("test.txt").equals(new File("Test.txt"));   // false
```

## 总结

File 类是 Java IO 的基础，虽然操作有限，但理解其原理对后续学习流操作很重要：

- File 对象只是路径的抽象表示
- 大部分方法依赖本地文件系统实现
- 跨平台开发时注意路径分隔符
- JDK 7+ 建议使用 `Path` 和 `Files` 工具类

> 📚 **推荐阅读**
> - [Java IO 流体系：字节流与字符流]({{< relref "post/java-io-03" >}})
