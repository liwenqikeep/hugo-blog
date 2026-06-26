---
title: "Java 字节码详解"
date: 2018-04-16
draft: false
categories: ["Java"]
tags: ["JVM", "字节码", "Class文件", "javap", "指令集"]
toc: true
---

## 前言

Java 字节码是 JVM 执行的指令集。理解字节码不仅能帮你深入理解 JVM 的工作原理，还能在排查疑难问题时多一个底层视角——比如分析语法糖的底层实现、定位编译器优化问题、理解 Lambda 表达式的执行机制等。

<!--more-->

## 一、Class 文件结构

每个 Class 文件对应一个 Java 类或接口的完整定义。它是一组以 **8 位字节**为基础单位的二进制流，按紧凑的格式排列。

### 1.1 总体结构

```
ClassFile {
    u4             magic;                 // 魔数：0xCAFEBABE
    u2             minor_version;         // 次版本号
    u2             major_version;         // 主版本号
    u2             constant_pool_count;   // 常量池大小
    cp_info        constant_pool[constant_pool_count-1]; // 常量池
    u2             access_flags;          // 类/接口的访问标志
    u2             this_class;            // 当前类索引
    u2             super_class;           // 父类索引
    u2             interfaces_count;      // 接口计数器
    u2             interfaces[interfaces_count]; // 接口索引集合
    u2             fields_count;          // 字段计数器
    field_info     fields[fields_count];  // 字段表
    u2             methods_count;         // 方法计数器
    method_info    methods[methods_count];// 方法表
    u2             attributes_count;      // 属性计数器
    attribute_info attributes[attributes_count]; // 属性表
}
```

### 1.2 魔数与版本号

```java
// 每个 Class 文件的前 4 个字节：0xCAFEBABE
// 紧接着的 4 个字节标识版本号（minor + major）

// 常见的版本号对应关系：
// JDK 1.1  → major=45
// JDK 1.5  → major=49
// JDK 1.8  → major=52
// JDK 11   → major=55
// JDK 17   → major=61
// JDK 21   → major=65
```

### 1.3 常量池

常量池是 Class 文件中**最复杂也最重要**的数据结构，它存放两大类常量：

1. **字面量**：文本字符串、被声明为 `final` 的常量值
2. **符号引用**：类和接口的全限定名、字段的名称和描述符、方法的名称和描述符

**常量池中的常见常量类型：**

```java
┌─────────────────┬────────────────────────────────┐
│ 常量类型         │ 标志（tag）                    │
├─────────────────┼────────────────────────────────┤
│ CONSTANT_Utf8   │ 1     // UTF-8 编码的字符串     │
│ CONSTANT_Integer│ 3     // int 常量               │
│ CONSTANT_Float  │ 4     // float 常量             │
│ CONSTANT_Long   │ 5     // long 常量              │
│ CONSTANT_Double │ 6     // double 常量            │
│ CONSTANT_Class  │ 7     // 类或接口的符号引用      │
│ CONSTANT_String │ 8     // 字符串类型字面量        │
│ CONSTANT_Fieldref│ 9    // 字段的符号引用          │
│ CONSTANT_Methodref│10   // 类中方法的符号引用      │
│ CONSTANT_InterfaceMethodref│11 // 接口中方法的符号引用│
│ CONSTANT_NameAndType│12 // 字段/方法的名称和类型    │
│ CONSTANT_MethodHandle│15 // 方法句柄               │
│ CONSTANT_MethodType│16   // 方法类型               │
│ CONSTANT_InvokeDynamic│18 // 动态调用点            │
└─────────────────┴────────────────────────────────┘
```

### 1.4 访问标志（Access Flags）

用于标识类或接口的访问信息：

```java
// 取值（可组合，用位运算）：
ACC_PUBLIC       = 0x0001  // public
ACC_FINAL        = 0x0010  // final
ACC_SUPER        = 0x0020  // 使用 invokespecial 语义（JDK 1.2 后必须为真）
ACC_INTERFACE    = 0x0200  // 接口
ACC_ABSTRACT     = 0x0400  // 抽象类
ACC_SYNTHETIC    = 0x1000  // 编译器自动生成的类
ACC_ANNOTATION   = 0x2000  // 注解
ACC_ENUM         = 0x4000  // 枚举
```

### 1.5 字段表和方法表

字段表描述类或接口中声明的变量：

```
field_info {
    u2             access_flags;      // 访问标志（public/static/volatile 等）
    u2             name_index;        // 字段名在常量池中的索引
    u2             descriptor_index;  // 字段描述符在常量池中的索引
    u2             attributes_count;  // 属性数量
    attribute_info attributes[attributes_count];
}
```

方法表结构与字段表类似，但方法体（字节码指令）存储在 `Code` 属性中。

**描述符（Descriptor）：**

```java
// 字段描述符
int[]         →   [I
Object        →   Ljava/lang/Object;
String[][]    →   [[Ljava/lang/String;

// 方法描述符
void run()              →   ()V
int add(int, int)       →   (II)I
String get(int, String) →   (ILjava/lang/String;)Ljava/lang/String;
```

## 二、字节码指令集

JVM 的指令集基于**栈架构**，大多数指令都不包含操作数，操作数取自操作数栈。

### 2.1 数据类型与指令前缀

字节码指令通常包含数据类型信息：

```java
i = int, l = long, f = float, d = double, a = reference, b = byte, c = char, s = short

// 例子：将常量加载到操作数栈
iconst_1    // 将 int 类型常量 1 压入栈
lconst_1    // 将 long 类型常量 1 压入栈
fconst_1    // 将 float 类型常量 1 压入栈
dconst_1    // 将 double 类型常量 1 压入栈
```

### 2.2 常用指令分类

**加载和存储指令：**

```java
// 将局部变量加载到操作数栈
iload, lload, fload, dload, aload
iload_0    // 将第 0 个局部变量（int）压入栈

// 将操作数栈顶值存储到局部变量表
istore, lstore, fstore, dstore, astore
istore_1   // 将栈顶 int 值存入第 1 个局部变量

// 将常量加载到操作数栈
bipush     // byte 扩展成 int 入栈
sipush     // short 扩展成 int 入栈
ldc        // 从常量池中加载常量到栈（String、Class 等）
iconst_0   // int 常量 0 入栈（另有 iconst_1 ~ iconst_5）
```

**算术指令：**

```java
iadd, ladd, fadd, dadd      // 加法
isub, lsub, fsub, dsub      // 减法
imul, lmul, fmul, dmul      // 乘法
idiv, ldiv, fdiv, ddiv      // 除法
irem, lrem, frem, drem      // 取模
ineg, lneg, fneg, dneg      // 取反
```

**对象操作指令：**

```java
new              // 创建对象（分配内存，未调用构造器）
newarray         // 创建基本类型数组
anewarray        // 创建引用类型数组
getfield         // 获取实例字段
putfield         // 设置实例字段
getstatic        // 获取静态字段
putstatic        // 设置静态字段
instanceof       // 检查对象是否为指定类型
checkcast        // 类型强制转换
```

**方法调用指令：**

```java
invokevirtual      // 调用实例方法（基于虚方法分派，最常见的调用指令）
invokespecial      // 调用构造器、私有方法、父类方法
invokestatic       // 调用静态方法
invokeinterface    // 调用接口方法
invokedynamic      // 动态解析调用点（JDK 7+，Lambda 表达式的核心）
```

**控制转移指令：**

```java
ifeq, ifne, iflt, ifge, ifgt, ifle       // if (x ==/!=/< 等) 0
if_icmpeq, if_icmpne, if_icmplt 等       // 比较两个 int 值
ifnull, ifnonnull                        // 与 null 比较
goto                                     // 无条件跳转
tableswitch, lookupswitch                // switch 语句
```

**返回指令：**

```java
return       // void 方法返回
ireturn      // int 方法返回
lreturn      // long 方法返回
freturn      // float 方法返回
dreturn      // double 方法返回
areturn      // 引用类型方法返回
```

## 三、使用 javap 查看字节码

`javap` 是 JDK 自带的字节码查看工具。

```bash
# 将 .class 文件反汇编为字节码
javap -c HelloWorld.class

# 查看详细信息（常量池、行号等）
javap -verbose HelloWorld.class

# 查看方法字节码
javap -c -p HelloWorld.class  # -p 显示私有方法
```

### 3.1 简单示例

```java
// 源码
public class HelloWorld {
    public static void main(String[] args) {
        int a = 1;
        int b = 2;
        int c = a + b;
        System.out.println(c);
    }
}
```

**字节码：**

```java
public static void main(java.lang.String[]);
    Code:
       0: iconst_1               // 将 int 常量 1 压入操作数栈
       1: istore_1               // 栈顶值弹出，存入局部变量表 slot 1（a）
       2: iconst_2               // 将 int 常量 2 压入操作数栈
       3: istore_2               // 存入局部变量表 slot 2（b）
       4: iload_1                // 将 slot 1（a）加载到栈顶
       5: iload_2                // 将 slot 2（b）加载到栈顶
       6: iadd                   // 弹出栈顶两个 int，相加后结果入栈
       7: istore_3               // 存入局部变量表 slot 3（c）
       8: getstatic #7           // 获取 System.out（#7 常量池引用）
      11: iload_3                // 将 slot 3（c）加载到栈顶
      12: invokevirtual #13      // 调用 println（#13 常量池引用）
      15: return
```

```
执行流程可视化：

iconst_1 → [1]          a = 1
         栈: 1

istore_1 → []           a 存入 slot 1
         栈: (空)

iconst_2 → [2]          
         栈: 2

istore_2 → []           b 存入 slot 2
         栈: (空)

iload_1  → [1]          
         栈: 1

iload_2  → [1, 2]       a 和 b 都在栈上
         栈: 1 → 2

iadd     → [3]          a + b
         栈: 3

istore_3 → []           c 存入 slot 3
         栈: (空)
```

### 3.2 i++ 与 ++i 的字节码差异

```java
public class IncrementDemo {
    // i++：先取 i 的值，再自增
    public int postIncrement(int i) {
        return i++;
    }
    
    // ++i：先自增，再取 i 的值
    public int preIncrement(int i) {
        return ++i;
    }
}
```

**postIncrement 字节码：**

```java
0: iload_1              // 读取 i
1: iinc 1, 1            // 局部变量 slot1 直接 +1（不通过操作数栈）
4: ireturn              // 返回的是栈顶的原始值（即自增前的 i）
```

**preIncrement 字节码：**

```java
0: iinc 1, 1            // 先自增（局部变量 slot1 +1）
3: iload_1              // 再读取 i
4: ireturn              // 返回的是自增后的值
```

**关键点：** `iinc` 是直接操作局部变量表的指令，不经过操作数栈。`i++` 在读取值之后自增，`++i` 在自增之后读取值。这就是两者语义差异的字节码根源。

### 3.3 try-finally 的字节码实现

```java
public class TryFinallyDemo {
    public int method() {
        try {
            return 1;
        } finally {
            System.out.println("finally");
        }
    }
}
```

**字节码：**

```java
0: iconst_1               // 将 1 压入栈（准备返回）
1: istore_1               // 存入局部变量表 slot 1（暂存返回值）
2: getstatic #7           // System.out
5: ldc #13                // "finally"
7: invokevirtual #15      // println
10: iload_1               // 加载暂存的返回值
11: ireturn               // 返回

// 异常路径（任意异常发生时）：
12: astore_2              // 异常对象存入 slot 2
13: getstatic #7          // System.out
16: ldc #13               // "finally"
18: invokevirtual #15     // println
21: aload_2               // 加载异常对象
22: athrow                // 抛出异常
```

**关键点：**
- `return 1` 先把 1 存到局部变量表，然后执行 finally，再从局部变量表加载返回值返回
- finally 块在正常路径和异常路径中都被**复制了一份**
- 这就是为什么 `finally` 无论如何都会执行

### 3.4 自动拆装箱

```java
public class BoxingDemo {
    public void demo() {
        Integer a = 100;
        int b = a;
    }
}
```

**字节码：**

```java
0: bipush 100              // 100 入栈
2: invokestatic #16        // Integer.valueOf(int) → 自动装箱
5: astore_1                // 存入 slot 1 (a)
6: aload_1                 // 加载 a
7: invokevirtual #22       // Integer.intValue() → 自动拆箱
10: istore_2               // 存入 slot 2 (b)
11: return
```

**关键点：** 自动装箱调用了 `Integer.valueOf()`，拆箱调用了 `Integer.intValue()`。这就是 `Integer a = 100` 编译后的真相。

### 3.5 字符串拼接（+ 运算符）

```java
public class StringConcatDemo {
    public String concat(String a, String b) {
        return a + b;
    }
}
```

**JDK 8 字节码：**

```java
0: new #2                  // new StringBuilder
3: dup
4: invokespecial #3        // StringBuilder.<init>()
7: aload_1                 // 加载 a
8: invokevirtual #4        // StringBuilder.append(String)
11: aload_2                // 加载 b
12: invokevirtual #4       // StringBuilder.append(String)
15: invokevirtual #5       // StringBuilder.toString()
18: areturn
```

**关键点：** 在 JDK 8 中，`a + b` 实际是创建 `StringBuilder` 并调用 `append()`。JDK 9 之后改为 `invokedynamic` + `StringConcatFactory`。

## 四、invokedynamic 与 Lambda

### 4.1 为什么需要 invokedynamic

JDK 7 之前只有 4 种方法调用指令，都无法做到"运行时动态确定调用目标"。引入 `invokedynamic` 是为了支持**动态语言**以及后来的 **Lambda 表达式**。

### 4.2 Lambda 的字节码实现

```java
public class LambdaDemo {
    public void demo() {
        Runnable r = () -> System.out.println("hello");
        r.run();
    }
}
```

**字节码：**

```java
0: invokedynamic #2,  0   // InvokeDynamic #0:run:()Ljava/lang/Runnable;
5: astore_1
6: aload_1
7: invokeinterface #3, 1  // Runnable.run()
12: return
```

**关键点：** Lambda 表达式不会在编译时生成匿名内部类，而是通过 `invokedynamic` 指令，在运行时通过 `LambdaMetafactory` 动态生成实现类。这使得：
- Lambda 的创建成本转移到首次调用时
- 后续调用可以复用已生成的实现
- JIT 编译器可以更好地内联优化

### 4.3 匿名内部类 vs Lambda 的字节码差异

```java
// 匿名内部类（编译后会生成单独的 .class 文件）
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("hello");
    }
};

// Lambda（不生成单独的 .class 文件）
Runnable r = () -> System.out.println("hello");
```

匿名内部类会在编译时生成 `LambdaDemo$1.class` 文件，而 Lambda 不会——它是运行时通过 `invokedynamic` 动态生成的。

## 五、字节码操作框架

除了使用 `javap` 查看字节码外，还有几个常用的字节码操作框架：

| 框架 | 用途 | 特点 |
|------|------|------|
| **ASM** | 字节码生成与修改 | 最底层，性能最好，CGLib 和 Spring 底层使用 |
| **Javassist** | 字节码修改 | 提供高级 API，学习成本低 |
| **ByteBuddy** | 字节码生成 | 现代框架，API 优雅，Mockito 使用 |
| **CGLib** | 动态代理 | 基于 ASM，Spring AOP 可选实现 |

```java
// ByteBuddy 示例：动态生成类
new ByteBuddy()
    .subclass(Object.class)
    .method(named("toString"))
    .intercept(FixedValue.value("Hello ByteBuddy"))
    .make()
    .load(getClass().getClassLoader())
    .getLoaded();
```

## 六、总结

### 字节码速查表

| 类别 | 常见指令 |
|------|---------|
| 常量入栈 | `iconst_0~5`, `bipush`, `ldc` |
| 局部变量操作 | `iload/istore`, `aload/astore` |
| 算术 | `iadd`, `isub`, `imul`, `idiv` |
| 对象 | `new`, `getfield/putfield`, `getstatic/putstatic` |
| 方法调用 | `invokevirtual`, `invokespecial`, `invokestatic`, `invokeinterface`, `invokedynamic` |
| 控制流 | `ifeq`, `if_icmpeq`, `goto`, `tableswitch` |
| 返回 | `return`, `ireturn`, `areturn` |

### 实用命令

```bash
# 查看字节码
javap -c -p -verbose ClassName

# 保存字节码到文件
javap -c -p ClassName > bytecode.txt

# 同时查看行号对应关系
javap -c -l ClassName
```

---

**相关阅读：**
- [JVM 类加载机制]({{< relref "post/jvm-classloader" >}})
- [JIT 即时编译（上）：编译过程与分层编译]({{< relref "post/jvm-jit-compilation-01" >}})
