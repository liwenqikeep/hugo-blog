---
title: "JIT 即时编译（下）：逃逸分析与循环优化"
date: 2018-04-20
draft: false
categories: ["Java"]
tags: ["JVM", "JIT", "逃逸分析", "栈上分配", "标量替换", "锁消除", "循环优化"]
toc: true
---

## 前言

上一篇我们讲了 JIT 编译的整体流程、分层编译和方法内联。本篇聚焦于 C2 编译器最核心的两类优化：**逃逸分析**和**循环优化**。

逃逸分析是 JIT 优化的基石——栈上分配、标量替换、锁消除都依赖于它。循环优化则负责提升热点循环的执行效率。理解这些优化，有助于写出对 JIT 更"友好"的代码。

<!--more-->

## 一、逃逸分析（Escape Analysis）

逃逸分析是 C2 编译器的一项**关键分析技术**，它分析对象的作用域，判断对象是否"逃逸"出方法或线程。

### 1.1 逃逸的三种状态

```java
public class EscapeStates {
    
    // 1. 不逃逸（No Escape）：对象在方法内部创建，未传出方法
    public int noEscape() {
        Point p = new Point(1, 2);  // p 只在方法内使用
        return p.x + p.y;
    }
    
    // 2. 方法逃逸（Method Escape）：对象被方法返回或传入其他方法
    public Point methodEscape() {
        Point p = new Point(1, 2);
        return p;  // p 逃逸出当前方法
    }
    
    public void methodEscape2() {
        Point p = new Point(1, 2);
        setPoint(p);  // p 逃逸到其他方法
    }
    
    // 3. 线程逃逸（Thread Escape）：对象可以被其他线程访问
    private Point sharedPoint;
    
    public void threadEscape() {
        Point p = new Point(1, 2);
        this.sharedPoint = p;  // p 逃逸到其他线程
    }
}
```

**逃逸分析的结果决定后续优化：**
- **不逃逸** → 可以进行栈上分配、标量替换、锁消除
- **仅方法逃逸** → 不能栈上分配，但可能锁消除
- **线程逃逸** → 无法做任何优化（按常规堆分配处理）

### 1.2 栈上分配（Stack Allocation）

**原理：** 如果对象不逃逸，JIT 可以将对象分配到线程栈上而非堆中，方法退出时自动销毁。

```
堆分配：                       栈上分配：
┌───── 堆 ─────┐             ┌───── 栈 ─────┐
│   Point 对象   │             │  Point 对象   │
│   (需 GC)      │             │  (方法退出即销毁)│
└───────────────┘             └──────────────┘
```

**优势：**
- 减少 GC 压力（对象随方法退出自动销毁）
- 减少内存分配开销

**限制：** 对象必须**完全不逃逸**。HotSpot 目前对栈上分配支持有限，更多依赖标量替换。

### 1.3 标量替换（Scalar Replacement）

**标量（Scalar）**：不可再分解的数据类型，如 int、long、reference。

**聚合量（Aggregate）**：可以继续分解的数据类型，如对象。

**标量替换**：如果逃逸分析证明对象不逃逸，JIT 会将对象的字段拆解为独立的局部变量，直接在栈上或寄存器中操作。

```java
// 原始代码
public int scalarReplacement() {
    Point p = new Point(1, 2);
    return p.x + p.y;
}

// 标量替换后的等效代码（不存在 Point 对象了）
public int scalarReplacement() {
    int x = 1;    // p.x 变为局部变量
    int y = 2;    // p.y 变为局部变量
    return x + y;
}
```

**标量替换的效果验证：**

```java
public class ScalarReplaceDemo {
    
    // 创建一个不逃逸的对象
    public static int sum(int count) {
        int total = 0;
        for (int i = 0; i < count; i++) {
            // Point 对象不逃逸，会被标量替换
            Point p = new Point(i, i + 1);
            total += p.x + p.y;
        }
        return total;
    }
    
    public static void main(String[] args) {
        // 预热
        for (int i = 0; i < 10000; i++) {
            sum(100);
        }
        System.out.println(sum(1000));
    }
    
    static class Point {
        private int x;
        private int y;
        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
```

在这个例子中，如果不开逃逸分析，每次循环都会创建 `Point` 对象，产生大量 GC 压力。开启逃逸分析后，`Point` 被优化掉，零内存分配。

```bash
# 开启/关闭逃逸分析的参数
-XX:+DoEscapeAnalysis        # 开启逃逸分析（默认开启）
-XX:-DoEscapeAnalysis        # 关闭逃逸分析
-XX:+EliminateAllocations    # 开启标量替换（默认开启，依赖逃逸分析）
-XX:+PrintEscapeAnalysis     # 打印逃逸分析结果
```

### 1.4 锁消除（Lock Elimination / Lock Elision）

如果逃逸分析发现对象**不会线程逃逸**（仅在一个线程内使用），JIT 可以**消除**对该对象的同步操作。

```java
// 原始代码
public class LockEliminationDemo {
    
    // sb 不会逃逸，锁可以被消除
    public String concat(String a, String b) {
        StringBuffer sb = new StringBuffer();  // StringBuffer 内部方法有 synchronized
        sb.append(a);
        sb.append(b);
        return sb.toString();
    }
}
```

**标量替换 + 锁消除的效果：**

没有逃逸分析时：
```
每次调用 concat：
  1. 创建 StringBuffer 对象（堆分配）
  2. append 方法加锁 → 释放锁
  3. append 方法加锁 → 释放锁
  4. 返回 toString()
  5. StringBuffer 等待 GC
```

开启逃逸分析后：
```
每次调用 concat（等效于）：
  StringBuilder sb = new StringBuilder();  // 锁消除等同于 StringBuilder
  sb.append(a);
  sb.append(b);
  return sb.toString();
  // 甚至可以进一步标量替换
```

**注意：** 锁消除消除的是**不必要的锁**，而不是所有锁。对确实需要线程安全保护的代码不会有影响。

```bash
-XX:+EliminateLocks  # 开启锁消除（默认开启，依赖逃逸分析）
```

### 1.5 逃逸分析的局限

虽然逃逸分析很强大，但在实际应用中仍有局限：

1. **只能分析当前 JIT 编译单元内的代码**——跨方法的逃逸分析受内联深度限制
2. **反射、动态代理等**场景会破坏逃逸分析
3. **条件分支复杂**时，分析结果可能不准确
4. 栈上分配在 HotSpot 中的实现有限，**主要靠标量替换**

## 二、循环优化（Loop Optimizations）

循环是程序中最常见的热点区域，C2 对循环的优化非常激进。

### 2.1 循环不变量外提（Loop Invariant Code Motion）

将循环体内每次迭代都产生相同结果的表达式提到循环外执行。

```java
// 优化前
public void before(int[] array, int count) {
    for (int i = 0; i < count; i++) {
        // array.length 每次循环都一样
        // Math.PI / 2 是常量
        array[i] = (int)(i * Math.PI / 2);
    }
}

// 优化后（等效）
public void after(int[] array, int count) {
    double factor = Math.PI / 2;  // 提到循环外
    for (int i = 0; i < count; i++) {
        array[i] = (int)(i * factor);
    }
}
```

### 2.2 循环展开（Loop Unrolling）

将循环体复制多次，减少循环控制（条件判断和跳转）次数。

```java
// 优化前
for (int i = 0; i < 4; i++) {
    sum += array[i];
}
// 循环控制开销：4 次条件判断 + 4 次自增 + 4 次跳转

// 优化后（完全展开）
sum += array[0];
sum += array[1];
sum += array[2];
sum += array[3];
// 0 次循环控制开销

// 部分展开（迭代次数不确定时）
// 原始循环
for (int i = 0; i < n; i++) {
    sum += array[i];
}

// 展开为每次处理 4 个元素
int i;
for (i = 0; i < n - 3; i += 4) {
    sum += array[i];
    sum += array[i + 1];
    sum += array[i + 2];
    sum += array[i + 3];
}
// 处理剩余元素
for (; i < n; i++) {
    sum += array[i];
}
```

**循环展开的优点：**
- 减少循环控制指令的执行次数
- 增加指令级并行度（现代 CPU 可以同时执行多条无关指令）
- 为后续优化创造机会

**循环展开的控制参数：**
```bash
-XX:LoopUnrollLimit        # 循环展开的上限（默认 60）
-XX:UnrollLimitCheck       # 循环展开长度检查
```

### 2.3 循环分离（Loop Peeling）

将循环的第一次迭代（或前几次迭代）从循环体中分离出来单独处理。

```java
// 优化前
for (int i = 0; i < n; i++) {
    if (i == 0) {
        // 特殊处理第一次
        result = array[0];
    } else {
        result += array[i];
    }
}

// 优化后（循环分离）
if (n > 0) {
    result = array[0];  // 首次迭代分离
}
for (int i = 1; i < n; i++) {
    result += array[i];  // 循环体内不再有条件分支
}
```

**循环分离的作用：**
- 消除循环体内的条件分支（分支预测失败的成本很高）
- 让循环体内的代码路径更简单，便于向量化等其他优化

### 2.4 循环向量化（Loop Vectorization）

将循环中的标量操作转换为 SIMD（Single Instruction, Multiple Data）指令，一次处理多个数据。

```java
// 优化前（标量操作）
for (int i = 0; i < 1024; i++) {
    a[i] = b[i] + c[i];
}
// 每次循环：1 次加法，处理 1 个元素

// 优化后（向量化 - 假设使用 256 位 AVX 指令）
// 每次 SIMD 指令：1 次加法，处理 8 个 int 元素
// 循环次数从 1024 减少到 128
```

**HotSpot 的自动向量化**主要依赖于 **SuperWord** 优化（基于数据依赖图的分析）。

**可以自动向量化的典型模式：**

```java
// 1. 简单数组运算
for (int i = 0; i < n; i++) {
    c[i] = a[i] + b[i];
}

// 2. 规约操作（需要一定的模式匹配）
for (int i = 0; i < n; i++) {
    sum += a[i];
}

// 3. 连续的数组赋值
for (int i = 0; i < n; i++) {
    a[i] = 0;
}
```

**不能自动向量化的模式：**

```java
// 1. 不连续的内存访问
for (int i = 0; i < n; i++) {
    a[random[i]] = b[i];  // 随机访问，无法向量化
}

// 2. 循环携带依赖
for (int i = 1; i < n; i++) {
    a[i] = a[i - 1] + b[i];  // 每次依赖前一次结果
}

// 3. 有复杂条件分支
for (int i = 0; i < n; i++) {
    if (a[i] > 0) {
        b[i] = a[i] * 2;
    }
}
```

```bash
-XX:+UseSuperWord  # 开启自动向量化（默认开启）
```

### 2.5 其他循环优化

**循环反转（Loop Reversal）：**

```java
// 将 for 循环转换为 do-while 形式，减少一次跳转
// 优化前
for (int i = 0; i < n; i++) {
    body();
}

// 优化后（等效）
int i = 0;
if (i < n) {
    do {
        body();
        i++;
    } while (i < n);
}
```

**循环融合（Loop Fusion）：**

```java
// 优化前：两个循环各遍历一次
for (int i = 0; i < n; i++) {
    a[i] = b[i] + c[i];
}
for (int i = 0; i < n; i++) {
    d[i] = a[i] * 2;
}

// 优化后：一个循环完成所有操作
for (int i = 0; i < n; i++) {
    a[i] = b[i] + c[i];
    d[i] = a[i] * 2;
}
// 减少了一次循环控制开销，提高了缓存局部性
```

## 三、其他 C2 优化

### 3.1 边界检查消除（Range Check Elimination）

Java 对数组的每个访问都会进行边界检查（越界则抛出 `ArrayIndexOutOfBoundsException`）。C2 可以消除已知安全的检查。

```java
public int sum(int[] array) {
    int sum = 0;
    // 循环的 i 范围为 [0, array.length)，编译器可以证明所有访问都安全
    for (int i = 0; i < array.length; i++) {
        sum += array[i];  // 边界检查被消除
    }
    return sum;
}
```

**如何帮助编译器消除边界检查：**

```java
// ✅ 好的模式：标准的 0..length 循环
for (int i = 0; i < array.length; i++) { ... }

// ❌ 差的模式：复杂条件，编译器无法推理
for (int i = start; i < end && someCondition; i += step) { ... }
```

### 3.2 空值检查消除（Null Check Elimination）

与边界检查类似，JIT 可以消除已知非空对象的空值检查。

```java
public void process(Object obj) {
    if (obj != null) {  // 如果调用方传参已确保非空
        obj.toString();
    }
}
```

### 3.3 常量折叠与传播（Constant Folding & Propagation）

```java
// 常量折叠
int a = 1 + 2;  // → int a = 3;

// 常量传播
int a = 3;
int b = a + 4;  // → int b = 7;
```

### 3.4 死代码消除（Dead Code Elimination）

```java
public int demo() {
    int a = 1;
    int b = 2;
    int c = a + b;  // 如果 c 从未被使用，此代码被消除
    return a;
}
```

## 四、如何验证 JIT 优化效果

### 4.1 使用 JMH 测试

```java
// 验证标量替换的效果
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class EscapeAnalysisBenchmark {
    
    @Benchmark
    @Fork(value = 1, jvmArgs = "-XX:+DoEscapeAnalysis")
    public int withEA() {
        Point p = new Point(1, 2);
        return p.x + p.y;
    }
    
    @Benchmark
    @Fork(value = 1, jvmArgs = "-XX:-DoEscapeAnalysis")
    public int withoutEA() {
        Point p = new Point(1, 2);
        return p.x + p.y;
    }
    
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }
}
```

### 4.2 使用 JVM 参数观察

```bash
# 打印逃逸分析结果
-XX:+PrintEscapeAnalysis

# 查看标量替换的效果（配合 PrintCompilation）
-XX:+PrintCompilation -XX:+EliminateAllocations

# 查看循环展开
-XX:+PrintLoopOptimization  # 需要诊断模式
```

### 4.3 使用 -XX:+PrintAssembly

```bash
# 查看 JIT 编译后的机器码（需要 hsdis 插件）
-XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly -XX:CompileCommand=print,*ClassName.methodName
```

## 五、如何编写对 JIT 友好的代码

### 5.1 为逃逸分析编写的建议

```java
// ✅ 局部对象尽量不逃逸
public int process() {
    Point p = new Point(x, y);  // 不返回，不传入其他方法
    return p.x + p.y;
}

// ❌ 不必要地传入其他方法
public int process() {
    Point p = new Point(x, y);
    return helper(p);  // p 逃逸到 helper
}
```

### 5.2 为循环优化编写的建议

```java
// ✅ 使用标准的 for 循环
// ✅ 每次迭代访问连续的内存
// ✅ 减少循环体内的条件分支
// ✅ 将循环不变量提到循环外（编译器也会做，但手动做更可靠）

// ✅ 好的循环
for (int i = 0; i < array.length; i++) {
    sum += array[i];
}

// ❌ 不利于优化的循环
for (int i = 0; i < n; ) {
    if (condition) {
        sum += array[i];
        i += step;
    }
}
```

### 5.3 为方法内联与逃逸分析编写的建议

```java
// ✅ 编写小方法（便于内联）
// ✅ 局部创建的对象不要返回（便于标量替换）
// ✅ 使用 final class（便于类型分析）
// ✅ 使用简单数据结构，减少继承层次

// ❌ 避免在热点中创建大量逃逸对象
public void hotMethod() {
    for (int i = 0; i < 1000000; i++) {
        Result r = new Result(i);  // 每次创建新对象
        database.save(r);          // r 逃逸到外部
    }
}

// ✅ 改为对象池或批量处理
public void hotMethod() {
    List<Result> batch = new ArrayList<>(1000);
    for (int i = 0; i < 1000000; i++) {
        batch.add(new Result(i));
        if (batch.size() == 1000) {
            database.batchSave(batch);
            batch.clear();
        }
    }
    if (!batch.isEmpty()) {
        database.batchSave(batch);
    }
}
```

## 六、总结

### 逃逸分析的三项核心优化

| 优化 | 条件 | 效果 |
|------|------|------|
| **栈上分配** | 不逃逸 | 对象分配到栈，无 GC 压力 |
| **标量替换** | 不逃逸 | 对象被拆解为局部变量，消除对象 |
| **锁消除** | 不线程逃逸 | 消除不必要的锁操作 |

### 循环优化汇总

| 优化 | 目标 | 效果 |
|------|------|------|
| 循环展开 | 减少循环控制开销 | 减少跳转次数 |
| 循环分离 | 消除分支 | 简化循环体 |
| 循环不变量外提 | 避免重复计算 | 将常量计算提到循环外 |
| 循环向量化 | SIMD 加速 | 一次指令处理多个数据 |
| 循环融合 | 减少循环次数 | 提高缓存局部性 |

### 一句话原则

> **编写小方法、使用局部对象、写简单的标准循环**——让 JIT 编译器替你做好优化。

---

**相关阅读：**
- [JIT 即时编译（上）：编译过程与分层编译]({{< relref "post/jvm-jit-compilation-01" >}})
- [JVM 性能监控与调优]({{< relref "post/jvm-performance-tuning" >}})
