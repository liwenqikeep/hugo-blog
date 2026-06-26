---
title: "JIT 即时编译（上）：编译过程与分层编译"
date: 2018-04-18
draft: false
categories: ["Java"]
tags: ["JVM", "JIT", "即时编译", "C1", "C2", "方法内联", "分层编译"]
toc: true
---

## 前言

Java 程序最初是通过**解释器**逐行解释字节码来执行的，速度较慢。为了提高执行效率，JVM 引入了 **JIT（Just-In-Time）即时编译器**，在运行时将热点代码（Hot Spot Code）编译为本地机器码，从而大幅提升执行速度。

理解 JIT 编译原理，有助于你写出更易于 JIT 优化的代码，也能帮助理解为什么某些代码"预热"后性能有明显提升。

<!--more-->

## 一、解释执行 vs 编译执行

### 1.1 执行模式对比

```java
// 解释执行：
// 字节码 → 逐条解释 → 执行
// 优点：启动快
// 缺点：执行慢

// 编译执行（JIT）：
// 字节码 → 编译为本地机器码 → 执行
// 优点：执行快
// 缺点：编译需要时间和 CPU
```

```
解释执行：
  [iload_1] ──▶ 解释器读取指令 ──▶ 查找指令含义 ──▶ 执行 ──▶ 下一个
  [iload_2] ──▶ 解释器读取指令 ──▶ 查找指令含义 ──▶ 执行 ──▶ 下一个
  [iadd   ] ──▶ 解释器读取指令 ──▶ 查找指令含义 ──▶ 执行 ──▶ 下一个

编译执行：
  [iload_1, iload_2, iadd] ──▶ 编译器一次性编译 ──▶ 本地机器码 ──▶ 直接执行
```

### 1.2 HotSpot VM 的执行策略

HotSpot VM 采用**混合模式**：解释器与编译器配合工作。

```
启动时：解释执行（快速启动）
   │
   ▼
监控热点代码（方法调用计数/循环回边计数）
   │
   ▼
热点达到阈值 → 触发 JIT 编译
   │
   ▼
编译为本地机器码并缓存
   │
   ▼
后续调用直接执行机器码（极快）
```

这种设计的优势：
- **启动速度快**：无需等待所有代码编译完成即可运行
- **执行效率高**：热点代码被编译为机器码，获得接近 C++ 的性能
- **自适应优化**：基于运行时的 profiling 信息进行针对性优化

```bash
# 查看 JVM 运行模式
java -version
# 输出示例：Java HotSpot(TM) 64-Bit Server VM (mixed mode)
```

## 二、C1 编译器与 C2 编译器

HotSpot VM 内置了两个主要的即时编译器：

| 特性 | C1（Client Compiler） | C2（Server Compiler） |
|------|----------------------|----------------------|
| 别名 | 客户端编译器 | 服务端编译器 |
| 编译速度 | 快 | 慢 |
| 优化程度 | 较低 | 极高 |
| 适用场景 | 桌面应用、需要快速启动 | 服务端、长时间运行 |
| 启用参数 | `-client` | `-server`（默认）|
| 编译线程 | 少 | 多 |

### 2.1 C1 编译器（Client Compiler）

C1 编译器设计目标是在短时间内完成编译，适合对启动性能有要求的应用。

**C1 的编译流程：**

```
字节码
   │
   ▼
字节码的前端解析（HIR 构建）
   │
   ▼
HIR → 平台无关优化（内联、常量折叠等）
   │
   ▼
HIR → LIR（低级中间表示）
   │
   ▼
LIR → 寄存器分配（线性扫描算法）
   │
   ▼
LIR → 代码生成
   │
   ▼
本地机器码
```

**C1 的优化：**
- 方法内联（仅简单内联）
- 常量折叠
- 去死代码
- 简单的循环优化

### 2.2 C2 编译器（Server Compiler）

C2 编译器设计目标是生成**极度优化的本地代码**，适合长时间运行的服务端应用。它的优化能力媲美传统的静态编译器（如 GCC -O3 级别）。

**C2 的核心优化技术：**
- 内联（深度内联，可达多次嵌套）
- 逃逸分析（后续文章详细讲）
- 锁消除与锁粗化
- 循环优化（展开、向量化、分离）
- 全局值编号（Global Value Numbering）
- 类型继承关系分析（CHA）
- 空值检查消除
- 边界检查消除

**C2 的缺点是**：编译速度慢，对 CPU 占用较大。

### 2.3 C1 与 C2 的对比

```java
// 一个简单的比较场景
public class CompilerComparison {
    public static int sum(int n) {
        int result = 0;
        for (int i = 0; i < n; i++) {
            result += i;
        }
        return result;
    }
    
    public static void main(String[] args) {
        long start = System.nanoTime();
        for (int i = 0; i < 100000; i++) {
            sum(10000);
        }
        long end = System.nanoTime();
        System.out.println("耗时: " + (end - start) / 1000000 + "ms");
    }
}
```

- **C1 编译**：编译时间短，产生的机器码优化一般
- **C2 编译**：编译时间长，产生高度优化的机器码

## 三、热点检测（Hot Spot Detection）

"热点"是 JIT 编译的基本单位。HotSpot VM 通过两种计数器来检测热点：

### 3.1 方法调用计数器（Method Invocation Counter）

统计一段时间内方法的调用次数。

```
方法被调用 → 计数器 +1
    │
    ▼
是否达到 CompileThreshold？
├── 否 → 继续解释执行
└── 是 → 提交编译请求
        │
        ▼
    编译队列 → 编译完成 → 替换为编译后的机器码
```

**编译阈值（JDK 8 默认值）：**
- **C1 编译阈值**：`-XX:CompileThreshold=1500`
- **C2 编译阈值**：`-XX:CompileThreshold=10000`

**计数器衰减：**
- 方法调用计数器会随时间衰减（半衰期）
- 防止"方法曾经很热但现在已经冷下来"的误判
- 衰减由 `-XX:CounterHalfLifeTime` 控制

### 3.2 回边计数器（Back Edge Counter）

统计方法中循环体的执行次数。回边（Back Edge）是指跳转到方法中较早字节码指令的行为，对应循环的继续执行。

```
循环回边 → 回边计数器 +1
    │
    ▼
回边计数 + 调用计数 > 阈值？
├── 否 → 继续
└── 是 → 提交 OSR（On-Stack Replacement）编译请求
        │
        ▼
    在栈上替换，将循环体编译为机器码后直接跳转
```

**OSR（On-Stack Replacement）：**
- 当循环体被判定为热点时，可以在方法仍在栈上执行时替换为编译后的代码
- 从循环开始处跳转到编译后的机器码继续执行
- **OSR 编译的优化质量通常不如普通 JIT 编译**（因为需要保留栈帧结构）

### 3.3 热点阈值计算

```bash
# 查看当前 JVM 的编译阈值
java -XX:+PrintFlagsFinal -version | findstr CompileThreshold

# 示例输出：
# intx CompileThreshold = 10000
```

## 四、分层编译（Tiered Compilation）

JDK 7 引入了分层编译（默认在 JDK 8 中开启），将编译过程分为多个等级，结合了解释器、C1 和 C2 的优势。

### 4.1 五层结构

```
Level 0：解释执行
  - 不收集 profiling 信息
  - 启动最快

Level 1：C1 编译（简单优化）
  - 不做 profiling
  - 适用于稳定且简单的方法

Level 2：C1 编译（部分 profiling）
  - 收集有限的 profiling 信息
  - 适用于少量优化的方法

Level 3：C1 编译（完整 profiling）
  - 收集所有 profiling 信息
  - 为 C2 做数据准备

Level 4：C2 编译
  - 使用 Level 3 收集的 profiling 信息进行深度优化
  - 生成最高质量的机器码
```

### 4.2 编译流程示例

```
方法开始时：
Level 0 ──▶ 解释执行
   │
   ▼ （调用计数达到 C1 阈值）
Level 3 ──▶ C1 编译（完整 profiling）
   │
   ▼ （调用计数达到 C2 阈值且 profiling 信息足够）
Level 4 ──▶ C2 编译
```

### 4.3 分层编译的权衡

**为什么要先经过 C1 再 C2？**

```
纯 C2 方案：
  解释执行 ───▶ C2 编译 ───▶ 执行
  （等待时间长，启动慢）

分层编译：
  解释执行 ───▶ C1 编译 ───▶ C2 编译
  （C1 先提供中等性能的编译版本，减少等待时间）
```

**C2 编译失败后的降级：**
- 如果 C2 编译耗时过长或失败，会回退到 Level 1（C1 简单优化）
- 不会回退到 Level 3，因为不再需要 profiling

```bash
# 分层编译相关参数
-XX:+TieredCompilation       # 启用分层编译（JDK 8 默认开启）
-XX:Tier3MinInvocationThreshold  # Level 3 的最小调用阈值
-XX:Tier4MinInvocationThreshold  # Level 4 的最小调用阈值
-XX:CICompilerCount           # 编译器线程数（默认 2，C1 和 C2 各一）
```

## 五、方法内联（Method Inlining）

方法内联是 JIT 编译中**最重要的优化手段之一**。它将方法的调用点替换为方法体本身，消除方法调用开销。

### 5.1 为什么要内联？

```java
// 没有内联时：
public int add(int a, int b) {
    return a + b;
}

public void caller() {
    int result = add(1, 2);  // 方法调用：压栈、跳转、返回
    System.out.println(result);
}

// 内联后：
public void caller() {
    int result = 1 + 2;      // 直接计算结果，没有方法调用
    System.out.println(result);
}
```

**方法调用的开销：**
- 创建栈帧（分配局部变量表等）
- 保存调用现场
- 跳转与返回
- 参数传递

**内联后消除了这些开销，同时为后续优化创造了条件**——比如常量传播、死代码消除等。

### 5.2 内联决策

C2 编译器基于 **CHA（Class Hierarchy Analysis，类继承关系分析）** 决定是否内联。

```java
// 可以被内联的情况：

// 1. 非虚方法（编译期即可确定目标）
//    - 静态方法（invokestatic）
//    - 私有方法（invokespecial）
//    - 构造器（invokespecial）

// 2. 虚方法但 CHA 确认只有一个实现
public interface Service {
    void execute();
}

public class ServiceImpl implements Service {
    @Override
    public void execute() {
        // 如果只有一个实现类，直接内联
    }
}

// 3. 最常见的调用——实际只有少数实现
//   即使 CHA 发现多个实现，也会做"激进内联"：
//   内联最常见的实现，并加入类型检查守卫
```

**激进内联（Guarded Inlining）：**

```java
// 源代码
service.execute();

// 编译后的伪代码（激进内联）
if (service.getClass() == ServiceImpl.class) {
    // 内联的 ServiceImpl.execute() 代码
} else {
    // 退化为虚方法调用
    service.execute();
}
```

如果类型检查成功则直接执行内联代码（极快），否则走慢路径（并且 JIT 会重新编译优化）。

### 5.3 内联限制

**不会内联的情况：**

```java
// 1. 方法体过大
// -XX:MaxInlinedLevel=9      # 最大内联嵌套深度
// -XX:InlineSmallCode=2000   # 被调用方法编译后的机器码大小限制

// 2. 调用点过于频繁但目标不确定（接口多实现）

// 3. 递归方法
//    通常不会内联（除非尾递归优化，但 HotSpot 不直接优化尾递归）

// 4. 由 -XX:MaxInlineSize 控制方法体大小（默认 35 字节码）
```

**`-XX:CompileCommand` 可用于强制内联：**

```bash
# 强制内联某个方法
-XX:CompileCommand=dontinline,com.example.MyService::slowMethod

# 排除某个方法
-XX:CompileCommand=exclude,com.example.MyService::buggyMethod
```

### 5.4 方法内联实战——如何编写利于内联的代码

```java
// ❌ 不利于内联：方法体过大
public void processOrder(Order order) {
    // 100 行代码...
}

// ✅ 利于内联：小而明确的方法
public void processOrder(Order order) {
    validateOrder(order);
    calculatePrice(order);
    saveOrder(order);
    sendNotification(order);
}

private void validateOrder(Order order) { /* 小方法 */ }
private void calculatePrice(Order order) { /* 小方法 */ }
private void saveOrder(Order order) { /* 小方法 */ }
private void sendNotification(Order order) { /* 小方法 */ }
```

**经验原则：**
- **方法尽量小**（小于 35 字节码——大概是 10~15 行 Java 代码）
- 使用 **final** 或 **private** 方法（非虚方法更容易内联）
- 避免过深的继承层次（CHA 分析更复杂）
- 接口只有一个实现类时更容易内联

## 六、如何观察 JIT 编译

### 6.1 打印编译信息

```bash
# 基础编译日志
-XX:+PrintCompilation

# 输出示例：
#     45    1       3       java.lang.String::hashCode (55 bytes)
#     46    2       3       java.lang.String::equals (68 bytes)
#    102   28       4       com.example.MyService::process (120 bytes)
#
# 格式：时间戳  编译ID  编译层  方法名 (方法体大小)
# 编译层：0=解释, 1=C1无profiling, 2=C1部分profiling, 3=C1完整profiling, 4=C2
```

### 6.2 查看编译后的机器码（需要额外配置）

```bash
# 1. 下载 hsdis 插件（HotSpot Disassembler）
# 2. 启动参数：
-XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly

# 输出：显示 JIT 编译后的 x86/x64 机器码
```

### 6.3 查看内联情况

```bash
-XX:+PrintInlining

# 输出示例：
# @ 25   com.example.Service::caller (40 bytes)
#   @ 12   com.example.ServiceImpl::execute (5 bytes)   inline (hot)
#   @ 18   com.example.Util::helper (10 bytes)          inline (success)
```

## 七、总结

### JIT 编译流程速记

```
解释执行 ──▶ 热点检测 ──▶ C1 编译（Level 1/2/3）──▶ 继续 profiling
                                           │
                                           ▼
                                      C2 编译（Level 4）
                                           │
                                           ▼
                                      高度优化的机器码
```

### 核心参数

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `-XX:+TieredCompilation` | true | 启用分层编译 |
| `-XX:+PrintCompilation` | false | 打印编译日志 |
| `-XX:CompileThreshold` | 10000 | C2 编译阈值 |
| `-XX:MaxInlineSize` | 35 | 最大内联方法大小（字节码）|
| `-XX:CICompilerCount` | 2 | 编译器线程数 |
| `-XX:+PrintInlining` | false | 打印内联信息 |

### 性能调优思路

1. **利用 PrintCompilation**：确认热点方法被正确编译
2. **利用 PrintInlining**：确认关键路径上的方法被内联
3. **编写小方法**：让 JIT 更容易内联
4. **预热**：服务启动后进行初步请求，触发 JIT 编译后再承接真实流量

---

**相关阅读：**
- [JIT 即时编译（下）：逃逸分析与循环优化]({{< relref "post/jvm-jit-compilation-02" >}})
- [Java 字节码详解]({{< relref "post/java-bytecode" >}})
