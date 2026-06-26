---
title: "JVM 类加载机制"
date: 2018-04-14
draft: false
categories: ["Java"]
tags: ["JVM", "类加载", "类加载器", "双亲委派", "ClassLoader"]
toc: true
---

## 前言

Java 虚拟机将描述类的数据从 Class 文件加载到内存，并对数据进行校验、转换解析和初始化，最终形成可以被 JVM 直接使用的 Java 类型，这个过程称为**类加载机制**。

与那些在编译时进行加载的语言不同，Java 的类加载是**运行时动态加载**的——这是 Java 语言的一项灵活性优势，也是很多高级特性（如热部署、OSGi）的基础。

<!--more-->

## 一、类加载的生命周期

一个类从被加载到 JVM 内存中，到被卸载出内存，完整生命周期包括七个阶段：

```
加载 ──▶ 验证 ──▶ 准备 ──▶ 解析 ──▶ 初始化 ──▶ 使用 ──▶ 卸载
   │                  ↗
   └───── 连接 ──────┘

（解析阶段在某些情况下可以在初始化之后再开始——这是为了支持 Java 的动态绑定）
```

其中加载、验证、准备、初始化和卸载这五个阶段的顺序是确定的。解析阶段在某些情况下可以在初始化阶段之后再开始（**运行时绑定**或**动态绑定**）。

### 1.1 加载（Loading）

加载是类加载的第一步，JVM 需要完成三件事：

1. 通过类的全限定名获取该类的二进制字节流
2. 将字节流所代表的静态存储结构转化为方法区的运行时数据结构
3. 在内存中生成代表这个类的 `java.lang.Class` 对象，作为方法区这个类的各种数据的访问入口

**二进制字节流的来源：**
- 本地文件系统（.class 文件）
- JAR 包（最常见的来源）
- 网络（如 Web Applet）
- 动态代理运行时生成
- JSP 文件编译后生成
- 加密文件（Class 文件加密，加载时解密）

### 1.2 验证（Verification）

验证是连接阶段的第一步，目的是确保 Class 文件中的字节流信息符合 JVM 规范要求，不会危害 JVM 自身安全。

**验证阶段分为四个检验过程：**

```
1. 文件格式验证
   ├── 是否以魔数 0xCAFEBABE 开头
   ├── 主次版本号是否在当前 JVM 可接受范围内
   ├── 常量池中的常量类型是否受支持
   └── 等等...

2. 元数据验证
   ├── 类是否继承自 final 类
   ├── 类是否实现了所有接口方法
   └── 等等...

3. 字节码验证
   ├── 操作数栈的数据类型与指令代码序列是否匹配
   ├── 跳转指令是否指向合理的代码位置
   └── 等等...

4. 符号引用验证
   ├── 符号引用中描述的字符串全限定名是否能找到对应的类
   ├── 符号引用中的类、字段、方法的可访问性是否可被当前类访问
   └── 等等...
```

> 可以通过 `-Xverify:none` 关闭验证，缩短类加载时间（仅在代码绝对安全的情况下）。

### 1.3 准备（Preparation）

为类变量（static 变量）分配内存并设置**零值**。

```java
public static int value = 123;
```

在准备阶段，`value` 的值为 **0**（int 的零值），而不是 123。将 `value` 赋值为 123 的 `putstatic` 指令是在初始化阶段才执行的。

**特殊情况**：如果类变量是 `static final`（常量），则准备阶段直接赋值为指定的值：

```java
public static final int value = 123; // 准备阶段直接赋值为 123
```

### 1.4 解析（Resolution）

JVM 将常量池内的**符号引用**替换为**直接引用**。

| 类型 | 符号引用 | 直接引用 |
|------|---------|---------|
| 定义 | 用一组符号描述目标，与 JVM 内存布局无关 | 直接指向目标的指针、偏移量或句柄 |
| 例子 | `java/lang/Object` | 指向方法区中 Object 类的内存地址 |

**解析针对的类型：**
- 类或接口解析（`CONSTANT_Class_info`）
- 字段解析（`CONSTANT_Fieldref_info`）
- 方法解析（`CONSTANT_Methodref_info`）
- 接口方法解析（`CONSTANT_InterfaceMethodref_info`）
- 方法类型解析（JDK 7）
- 方法句柄解析（JDK 7）
- 调用点限定符解析（JDK 7）

### 1.5 初始化（Initialization）

初始化阶段真正开始执行类中定义的 Java 程序代码（即字节码中的 `<clinit>()` 方法）。

**`<clinit>()` 方法的特点：**
- 由编译器自动收集类中所有类变量的赋值动作和静态语句块合并生成
- 按源文件中出现的顺序依次执行
- 不需要显式调用父类构造器，JVM 保证先执行父类的 `<clinit>()`

```java
public class Parent {
    static { System.out.println("Parent 的 <clinit>"); }
}

public class Child extends Parent {
    public static int value = 123;
    static { System.out.println("Child 的 <clinit>"); }
}

// 执行 Child.value 时输出：
// Parent 的 <clinit>
// Child 的 <clinit>
```

**什么时候会触发初始化？**（主动引用）

1. 遇到 `new`、`getstatic`、`putstatic`、`invokestatic` 字节码指令时
2. 使用 `java.lang.reflect` 对类型进行反射调用时
3. 初始化子类时，发现父类未初始化，先触发父类初始化
4. JVM 启动时指定的主类（包含 `main` 方法的类）
5. `java.lang.invoke.MethodHandle` 实例解析结果为 `REF_getStatic` 等句柄时
6. 接口中定义了 `default` 方法，且有实现类初始化时

**什么时候不会触发初始化？**（被动引用）

```java
// 1. 通过子类引用父类的静态字段
System.out.println(Child.value); // 只触发 Parent 的初始化

// 2. 通过数组定义引用类
Parent[] array = new Parent[10]; // 不会触发 Parent 初始化，触发 [LParent 的初始化

// 3. 引用常量（编译时已放入常量池）
System.out.println(Child.CONSTANT); // 不会触发 Child 初始化
```

## 二、类加载器

### 2.1 类加载器层次结构

对于任意一个类，**加载它的类加载器**和**这个类本身**一起确立其在 JVM 中的唯一性。比较两个类是否"相等"，只有在这两个类是由同一个类加载器加载的前提下才有意义。

JVM 默认提供了三层类加载器：

```
┌─────────────────────────────────────────────┐
│              Bootstrap ClassLoader           │
│          （启动类加载器，C++ 实现）            │
│  加载 <JAVA_HOME>/lib/ 目录下的核心类库       │
│  rt.jar、java.lang.*、java.util.* 等         │
└────────────────┬────────────────────────────┘
                 │ 委托
┌────────────────▼─────────────────────────────┐
│            Extension ClassLoader              │
│          （扩展类加载器，Java 实现）            │
│  加载 <JAVA_HOME>/lib/ext/ 目录下的类库        │
└────────────────┬────────────────────────────┘
                 │ 委托
┌────────────────▼─────────────────────────────┐
│          Application ClassLoader              │
│         （应用程序类加载器，Java 实现）          │
│  加载 classpath（用户类路径）上的类库           │
└──────────────────────────────────────────────┘
```

**如何获取类加载器：**

```java
public class ClassLoaderDemo {
    public static void main(String[] args) {
        // 获取 String 类的类加载器（由 Bootstrap 加载）
        System.out.println(String.class.getClassLoader());
        // 输出: null（因为 Bootstrap 是 C++ 实现，Java 中返回 null）
        
        // 获取当前类的类加载器
        System.out.println(ClassLoaderDemo.class.getClassLoader());
        // 输出: jdk.internal.loader.ClassLoaders$AppClassLoader
        
        // 获取 Application ClassLoader 的父加载器
        System.out.println(ClassLoaderDemo.class.getClassLoader().getParent());
        // 输出: jdk.internal.loader.ClassLoaders$PlatformClassLoader（JDK 9+）
        // 或: sun.misc.Launcher$ExtClassLoader（JDK 8 及以前）
    }
}
```

### 2.2 双亲委派模型（Parents Delegation Model）

**工作流程：**
当一个类加载器收到类加载请求时，它首先不会自己去尝试加载，而是把这个请求委派给父类加载器去完成。

```
子类加载器收到加载请求
    │
    ▼
向上委托给父类加载器
    │
    ▼
一直委托到 Bootstrap ClassLoader
    │
    ▼
Bootstrap 是否能加载？
├── 能 → Bootstrap 加载成功，返回
└── 不能 → 向下返回，由子类加载器尝试加载
        │
        ▼
    加载成功？→ 返回类
    加载失败？→ ClassNotFoundException
```

**代码实现（`java.lang.ClassLoader.loadClass()`）：**

```java
protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
        // 1. 检查类是否已被加载
        Class<?> c = findLoadedClass(name);
        if (c == null) {
            try {
                // 2. 父加载器不为空，委托给父加载器
                if (parent != null) {
                    c = parent.loadClass(name, false);
                } else {
                    // 3. 父加载器为空，使用 Bootstrap 加载
                    c = findBootstrapClassOrNull(name);
                }
            } catch (ClassNotFoundException e) {
                // 父加载器抛出异常，说明父加载器无法完成加载
            }
            
            if (c == null) {
                // 4. 父加载器无法加载，自行加载
                c = findClass(name);
            }
        }
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }
}
```

**双亲委派的优势：**
1. **安全**：Java 核心类库由 Bootstrap 加载，防止核心 API 被篡改（如自定义 `java.lang.String` 不会被加载）
2. **避免重复加载**：父加载器加载过的类，子加载器无需再加载
3. **保证类的唯一性**：同一个类在 JVM 中只会被加载一次

### 2.3 破坏双亲委派——三大场景

双亲委派模型并不是一个强制性的约束，而是 Java 设计者推荐给类加载器的实现方式。在以下三个场景中，双亲委派被"破坏"了：

#### 场景一：JDK 1.2 之前——自定义类加载器

在双亲委派模型被引入之前（JDK 1.2），自定义类加载器已经存在。为了兼容，JDK 1.2 之后添加了 `findClass()` 方法供子类覆盖，如果子类想破坏双亲委派，重写 `loadClass()` 即可。

#### 场景二：Java SPI 机制——线程上下文类加载器

**问题：** SPI 接口（如 `java.sql.Driver`）由 Bootstrap ClassLoader 加载，但 SPI 的实现类（如 MySQL 的驱动）在 classpath 上，由 Application ClassLoader 加载。按照双亲委派，Bootstrap 无法"反向"委托给 Application。

**解决方案：线程上下文类加载器（Thread Context ClassLoader, TCCL）**

```java
// JDBC 驱动的加载（SPI 的经典案例）
// 在 DriverManager（由 Bootstrap 加载）中：
public static <S> ServiceLoader<S> load(Class<S> service) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    return ServiceLoader.load(service, cl);
}
```

TCCL 破坏了双亲委派的"向上委托"规则，让父加载器能够通过子加载器加载类。

```
传统双亲委派：                  SPI 实际：
                            
Bootstrap ──▶ 核心类库         Bootstrap ──▶ SPI 接口
    ▲                              │
    │                              │ 通过 TCCL
    │                              ▼
Application ──▶ 实现类           Application ──▶ SPI 实现类
```

#### 场景三：热部署与模块化——Tomcat 等容器

Tomcat 为每个 Web 应用提供一个独立的 **WebAppClassLoader**，它打破了双亲委派：**优先加载自己目录下的类**，如果找不到才委托给父加载器。

```
          Bootstrap
              │
          Extension
              │
          Application
              │
      ┌───────┴───────┐
      │               │
  WebApp1          WebApp2
  ClassLoader      ClassLoader
  (webapp1/lib)    (webapp2/lib)
```

**Tomcat 破坏双亲委派的策略：**
1. 对于 `.class` 文件，优先在自己的 Web 应用目录中加载
2. 对于 Java 核心类库（`java.*`），仍然委托给 Bootstrap
3. 这样不同 Web 应用可以部署不同版本的类库而不冲突

#### 场景四：OSGi 与模块化

OSGi 实现了更复杂的类加载器网络，每个模块（Bundle）有自己的类加载器，可以定义自己的导入导出规则。类加载不再是树形结构，而是网络结构，完全打破了双亲委派。

## 三、自定义类加载器

### 3.1 实现方式

实现自定义类加载器只需要继承 `ClassLoader` 并重写 `findClass()` 方法：

```java
public class FileClassLoader extends ClassLoader {
    private String classPath;
    
    public FileClassLoader(String classPath) {
        this.classPath = classPath;
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            // 1. 将全限定名转换为文件路径
            String fileName = classPath + "/" + name.replace('.', '/') + ".class";
            
            // 2. 读取 .class 文件的字节
            FileInputStream fis = new FileInputStream(fileName);
            byte[] classBytes = new byte[fis.available()];
            fis.read(classBytes);
            fis.close();
            
            // 3. 调用 defineClass 将字节数组转换为 Class 对象
            return defineClass(name, classBytes, 0, classBytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}

// 使用
FileClassLoader loader = new FileClassLoader("/tmp/classes");
Class<?> clazz = loader.loadClass("com.example.MyService");
```

### 3.2 破坏双亲委派的自定义加载器

重写 `loadClass()` 方法，改变类加载顺序：

```java
public class BreakParentDelegationLoader extends ClassLoader {
    
    @Override
    public Class<?> loadClass(String name, boolean resolve) 
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                // 核心类库仍委托给父加载器
                if (name.startsWith("java.") || name.startsWith("javax.")) {
                    c = getParent().loadClass(name);
                } else {
                    // 非核心类优先自己加载
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        // 自己加载失败，再委托给父加载器
                        c = getParent().loadClass(name);
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
```

## 四、JDK 9+ 的模块化变化

JDK 9 引入模块化系统（Jigsaw Project），类加载器架构也发生了变化。

### 4.1 类加载器层次变化

| JDK 8 | JDK 9+ |
|-------|--------|
| Bootstrap ClassLoader | Bootstrap ClassLoader（加载模块化运行时镜像）|
| Extension ClassLoader | 改为 **Platform ClassLoader**（平台类加载器）|
| Application ClassLoader | Application ClassLoader（不变）|

**主要变化：**
- Extension ClassLoader 被移除，改为 Platform ClassLoader
- 不再从 `rt.jar` 加载，而是从模块化运行时镜像加载
- 类加载器不再是 `URLClassLoader` 的子类

### 4.2 模块化对类加载的影响

```java
// JDK 9 中查看类加载器
ModuleLayer layer = ModuleLayer.boot();
layer.modules().forEach(module -> {
    System.out.println(module.getName() + " -> " + module.getClassLoader());
});
```

**兼容性影响：**
- 原来通过 `-Xbootclasspath/p` 扩展 Bootstrap 类的方式被移除，改为 `--patch-module`
- `Extension ClassLoader` 相关的代码需要迁移到 `Platform ClassLoader`
- 双亲委派模型在模块化系统中加入了"模块可见性"约束

## 五、总结

### 类加载流程速记

```
加载 → 验证 → 准备 → 解析 → 初始化
  │                    │
  │        （可选：初始化后解析）
  ▼
使用 → 卸载
```

### 类加载器层次

```
Bootstrap ClassLoader（C++，null）
    └── Platform/Extension ClassLoader（Java）
        └── Application ClassLoader（Java）
            └── 自定义类加载器
```

### 关键概念

| 概念 | 要点 |
|------|------|
| 类加载阶段 | 加载、验证、准备、解析、初始化 |
| 主动引用（6 种）| new、反射、访问静态字段/方法、子类初始化等 |
| 被动引用 | 通过子类引用父类静态字段、数组定义、常量 |
| 双亲委派 | 先父后子，保证核心类安全、避免重复加载 |
| 破坏双亲委派 | SPI（TCCL）、Tomcat、OSGi、热部署 |

---

**相关阅读：**
- [JVM 内存区域与内存溢出]({{< relref "post/jvm-memory-area" >}})
- [JVM 垃圾回收机制]({{< relref "post/jvm-gc" >}})
- [Java 字节码详解]({{< relref "post/java-bytecode" >}})
