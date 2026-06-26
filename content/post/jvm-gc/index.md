---
title: "JVM 垃圾回收机制"
date: 2018-04-12
draft: false
categories: ["Java"]
tags: ["JVM", "GC", "垃圾回收", "CMS", "G1", "ZGC", "三色标记"]
toc: true
---

## 前言

垃圾回收（GC）是 JVM 最核心的能力之一。自动管理内存让 Java 开发者的生产力大幅提升，但 GC 同时也是很多性能问题的根源——理解 GC 原理，才能在排查问题和调优时游刃有余。

本文从判定方法、经典算法到具体收集器，系统梳理 GC 的完整知识体系。

<!--more-->

## 一、对象存活判定

在 GC 回收堆中对象之前，首先要判定哪些对象还"活着"，哪些已经"死了"。

### 1.1 引用计数法（Reference Counting）

**原理：** 每个对象维护一个引用计数器，被引用时 +1，引用失效时 -1，计数为 0 时即可回收。

```
  对象A               对象B               对象C
  引用计数: 2        引用计数: 1         引用计数: 0  ← 可回收
    ↑   ↑              ↑
  栈引用 对象B       对象C
```

**缺点（为什么主流 JVM 不用它）：**
- 循环引用问题：两个对象互相引用但没有外部引用时，计数永远不为 0
- 每次引用赋值都要同步更新计数器，有额外性能开销

```java
// 循环引用示例——引用计数法无法回收
public class CircularReferenceDemo {
    public static void main(String[] args) {
        MyObject a = new MyObject();
        MyObject b = new MyObject();
        a.instance = b;
        b.instance = a;
        a = null;
        b = null;
        // 此时 a 和 b 互相引用，引用计数均为 1，无法被回收
    }
}
```

### 1.2 可达性分析（Reachability Analysis）

**原理：** 从一组称为 **GC Roots** 的根对象开始，向下搜索引用链，不可达的对象即为可回收对象。

```
GC Roots
  ├── 虚拟机栈中引用的对象（局部变量、参数等）
  ├── 静态属性引用的对象（类的静态变量）
  ├── 常量引用的对象（字符串常量池）
  ├── 本地方法栈中 JNI 引用的对象
  └── JVM 内部引用（基本数据类型对应的 Class 对象、常驻异常对象等）
```

```
GC Roots ──▶ 对象A ──▶ 对象B ──▶ 对象C
                │
                └──▶ 对象D ──▶ 对象E (E 不再被任何存活对象引用)
                                    │
                                    ▼
                                 可回收
```

**什么样的对象可以作为 GC Roots？**
- 虚拟机栈（栈帧中的本地变量表）中引用的对象
- 方法区中静态属性引用的对象
- 方法区中常量引用的对象
- 本地方法栈中 JNI（即 Native 方法）引用的对象
- JVM 内部的引用（Class 对象、系统类加载器等）
- 所有被同步锁（synchronized）持有的对象

### 1.3 对象引用类型

Java 提供了四种引用类型，影响 GC 的回收决策：

| 引用类型 | 回收时机 | 用途 | 类名 |
|---------|---------|------|------|
| 强引用（Strong） | 永不回收 | 普通 new 对象 | 默认 |
| 软引用（Soft） | OOM 前回收 | 缓存 | `SoftReference` |
| 弱引用（Weak） | 下次 GC 即回收 | ThreadLocal、WeakHashMap | `WeakReference` |
| 虚引用（Phantom） | 任何时候都可能回收 | 对象回收跟踪 | `PhantomReference` |

```java
// 软引用示例——适合做缓存
SoftReference<byte[]> cache = new SoftReference<>(new byte[10 * 1024 * 1024]);
byte[] data = cache.get(); // 如果未被 GC 回收则返回对象，否则返回 null

// 弱引用示例——下次 GC 即回收
WeakReference<Object> ref = new WeakReference<>(new Object());
System.gc(); // 执行 GC 后 ref.get() 返回 null
```

### 1.4 finalize() 与对象自救

JVM 中一个对象的"死亡"需要经过两次标记：

```
第一次标记：可达性分析发现不可达
    │
    ▼
是否需要执行 finalize()？
├── 否（类未覆盖 finalize 或已执行过）→ 直接回收
└── 是 → 放入 F-Queue 队列，由 Finalizer 线程执行
        │
        ▼
第二次标记：在 finalize() 中是否重新建立引用？
├── 是 → 对象自救成功，从队列移除
└── 否 → 真正回收
```

> **注意：** `finalize()` 不推荐使用（JDK 9 已标记为弃用）。它的执行时机不可控，且性能差。对象自救只是一种语言机制演示，实际代码中永远不应该依赖它。

```java
public class FinalizeEscapeGC {
    public static FinalizeEscapeGC SAVE_HOOK = null;
    
    public void isAlive() {
        System.out.println("我还活着");
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        System.out.println("finalize 执行");
        SAVE_HOOK = this; // 自救：重新建立引用
    }
    
    public static void main(String[] args) throws InterruptedException {
        SAVE_HOOK = new FinalizeEscapeGC();
        
        SAVE_HOOK = null;
        System.gc();
        Thread.sleep(500); // 等待 Finalizer 线程执行
        if (SAVE_HOOK != null) {
            SAVE_HOOK.isAlive();
        } else {
            System.out.println("已死亡");
        }
        
        // 第二次自救尝试——失败，因为 finalize 只会执行一次
        SAVE_HOOK = null;
        System.gc();
        Thread.sleep(500);
        if (SAVE_HOOK != null) {
            SAVE_HOOK.isAlive();
        } else {
            System.out.println("已死亡");
        }
    }
}
```

## 二、经典 GC 算法

### 2.1 标记-清除（Mark-Sweep）

最基础的收集算法。

```
初始状态：    标记后：        清除后：
[A][B][C]    [✓A][✓B][✓C]    [  ][  ][  ]
[D][E][F]    [✓D][E][✓F]     [  ][E][  ]
[G][H][I]    [G][✓H][I]      [G][  ][I]
   ↓                              ↓
存活: D,G,I                   内存碎片化
```

**优点：** 实现简单，不需要对象移动
**缺点：**
- 执行效率不稳定，标记和清除两个过程效率随对象数量增长而下降
- 产生大量内存碎片，可能导致无法分配大对象而提前触发 Full GC

### 2.2 标记-复制（Mark-Copy）

将可用内存按容量划分为大小相等的两块，每次只使用其中一块。GC 时将存活对象复制到另一块，然后清理当前块。

```
使用中：        GC 后：
[A][B][C]      [  ][  ][  ]
[D][E][F]      [D][E][  ]
[↑      ]      [↑      ]
 from            to（变为新的 from）
```

**优点：** 无内存碎片，分配速度快（指针碰撞）
**缺点：** 内存利用率低（可用内存减半）

**优化——Appel 式回收（HotSpot 新生代采用）：**
- 将新生代分为：一个较大的 Eden 区和两个较小的 Survivor 区（默认 8:1:1）
- 每次使用 Eden 和一个 Survivor
- GC 时将存活对象复制到另一个 Survivor
- 当 Survivor 空间不足时，需要老年代分配担保（Handle Promotion）

```
Eden: 80%   |   Survivor From: 10%   |   Survivor To: 10%
  ↓ GC
存活对象复制到 To 区
  ↓
清空 Eden 和 From，交换 From/To 角色
```

### 2.3 标记-整理（Mark-Compact）

标记阶段同标记-清除，但后续将所有存活对象向一端移动，直接清理端边界以外的内存。

```
初始状态：    标记后：        整理后：        清理后：
[A][B][C]    [✓A][✓B][✓C]    [D][G][H]      [D][G][H][  ]
[D][E][F]    [✓D][E][✓F]     [  ][  ][  ]   [  ][  ][  ]
[G][H][I]    [G][✓H][I]      [  ][  ][  ]   [  ][ ][  ]
存活: D,G,H                     ↑
                        存活对象集中到一端
```

**优点：** 无内存碎片，内存利用率高
**缺点：** 对象移动需要 Stop The World，且移动对象需要更新引用

### 2.4 分代收集理论（Generational Collection）

HotSpot 的实践原则：

```
┌───────────────────────────────────────────────┐
│                   堆                            │
│  ┌─────────────────────┬─────────────────────┐ │
│  │     新生代（Young）   │     老年代（Old）     │ │
│  ├─────────┬─────┬─────┤                     │ │
│  │  Eden   │ S0  │  S1 │                     │ │
│  └─────────┴─────┴─────┘                     │ │
│        复制算法              标记-整理/标记-清除  │ │
└───────────────────────────────────────────────┘
```

**三条假说：**
1. **弱分代假说（Weak Generational Hypothesis）**：绝大多数对象朝生夕灭
2. **强分代假说（Strong Generational Hypothesis）**：熬过多次 GC 的对象越来越难回收
3. **跨代引用假说（Intergenerational Reference）**：跨代引用相对于同代引用极少

**跨代引用的处理——记忆集（Remembered Set）：**
- 在新生代建立一个全局数据结构（卡表 Card Table）
- 老年代对象引用新生代对象时，标记对应的卡片为 dirty
- GC 时只需扫描 dirty card，无需扫描整个老年代

## 三、三色标记算法（Tri-color Marking）

### 3.1 基本概念

现代 GC 收集器（CMS、G1）的并发标记阶段均基于三色标记算法：

| 颜色 | 含义 | 已扫描引用 | 已访问所有子对象 |
|------|------|-----------|----------------|
| 白色（White）| 尚未被 GC 访问 | 否 | 否 |
| 灰色（Gray）| 已被 GC 访问，但子对象未扫描完 | 是 | 否 |
| 黑色（Black）| 已被 GC 访问，子对象已扫描完 | 是 | 是 |

```
初始状态：所有对象均为白色
    │
    ▼
从 GC Roots 开始遍历，将根对象标记为灰色
    │
    ▼
从灰色对象中取出一个，遍历其引用：
├── 引用的白色对象 → 标记为灰色
└── 当前对象的所有引用遍历完毕 → 标记为黑色
    │
    ▼
重复直到没有灰色对象
    │
    ▼
剩余的白色对象 → 可回收
```

### 3.2 并发标记的问题——对象消失

当 GC 线程与用户线程并发执行时，可能出现**对象消失**问题：本应存活的对象被错误标记为白色。

```
正确的标记：
               黑色对象
                  │(引用)
                  ▼
               白色对象 → (应被标记为存活)

并发问题：
用户线程在 GC 标记过程中：
1. 黑色对象新增了对白色对象的引用
2. 同时又删除了原本指向该白色对象的所有灰色引用
            ↓
白色对象变成"被黑色引用但不可达"的悬浮对象 → 被错误回收
```

### 3.3 解决方案

要解决并发标记时的对象消失问题，必须破坏以下两个条件中的**任意一个**：

```
对象消失的条件（需同时满足）：
1. 赋值器插入了一条从黑色对象到白色对象的新引用
2. 赋值器删除了全部从灰色对象到该白色对象的直接或间接引用
```

**方案一：增量更新（Incremental Update）——CMS 使用**

破坏条件 1：当黑色对象插入新引用指向白色对象时，将黑色对象重新标记为灰色。

```
黑色对象新增引用白色对象
    ↓
将黑色对象变回灰色，重新扫描
    ↓
黑色对象 → 灰色（重新扫描其引用链，确保白色对象不被遗漏）
```

**方案二：原始快照（SATB, Snapshot At The Beginning）——G1 使用**

破坏条件 2：当灰色对象删除指向白色对象的引用时，记录这个引用，在并发标记结束后以快照为准重新扫描。

```
灰色对象删除指向白色对象的引用
    ↓
SATB 在删除前记录该引用
    ↓
标记结束后，以 GC 开始时的快照为准重新扫描被删除的引用
    ↓
白色对象仍被视为存活
```

**对比：**

| 特性 | 增量更新（CMS） | 原始快照 SATB（G1） |
|------|---------------|-------------------|
| 关注点 | 引用插入 | 引用删除 |
| 额外扫描 | 重新扫描黑色对象 | 扫描 SATB 队列 |
| 浮动垃圾 | 少 | 稍多（基于快照的残留）|
| 实现复杂度 | 较低 | 较高 |

## 四、经典垃圾收集器

### 4.1 Serial 收集器

单线程、新生代、复制算法。

```
GC 线程：   [Thread-0] ──── 工作 ────▶
用户线程：                  ❌ STW
```

- 特点：单线程工作，GC 时必须暂停所有用户线程（Stop The World）
- 适用场景：客户端模式、单核环境、小内存应用
- 参数：`-XX:+UseSerialGC`

### 4.2 ParNew 收集器

Serial 的多线程版本，新生代、复制算法。

```
GC 线程：   [Thread-0] ──┤
           [Thread-1] ──┤ 并行工作
           [Thread-2] ──┤
用户线程：                  ❌ STW
```

- 特点：多线程并行执行，仍需要 STW
- 适用场景：服务端模式，与 CMS 配合使用
- 参数：`-XX:+UseParNewGC`（JDK 9 后已弃用）

### 4.3 Parallel Scavenge / Parallel Old 收集器

吞吐量优先收集器，新生代复制算法 + 老年代标记-整理。

**核心关注点：吞吐量**

```
吞吐量 = 用户代码运行时间 / (用户代码运行时间 + GC 时间)
```

**参数控制：**
- `-XX:MaxGCPauseMillis`：最大停顿时间目标（毫秒）
- `-XX:GCTimeRatio`：吞吐量大小（0~100，默认 99，即允许 1% 的 GC 时间）
- `-XX:+UseAdaptiveSizePolicy`：自适应调整策略（GC Ergonomics）

| 特性 | Parallel Scavenge | Parallel Old |
|------|------------------|-------------|
| 适用分代 | 新生代 | 老年代 |
| 算法 | 复制 | 标记-整理 |
| 线程 | 多线程 | 多线程 |
| 关注点 | 吞吐量优先 | 吞吐量优先 |

- 参数：`-XX:+UseParallelGC` 或 `-XX:+UseParallelOldGC`

### 4.4 CMS 收集器（Concurrent Mark Sweep）

以**最短回收停顿时间**为目标的收集器，老年代、标记-清除算法。

```
GC 阶段：                       用户线程：
1. 初始标记（STW）────────────▶ ❌ 暂停
   标记 GC Roots 直接关联的对象
   
2. 并发标记 ──────────────────▶ ✅ 并发
   从 GC Roots 开始三色标记遍历

3. 重新标记（STW）────────────▶ ❌ 暂停
   修正并发标记期间变动的引用

4. 并发清除 ──────────────────▶ ✅ 并发
   清除死亡对象

5. 并发重置 ──────────────────▶ ✅ 并发
   重置数据结构，等待下次 GC
```

**优点：** 并发收集、低延迟
**缺点：**
1. **CPU 敏感**：并发阶段占用 CPU 资源
2. **浮动垃圾**：并发标记阶段产生的垃圾无法在本次 GC 中处理
3. **内存碎片**：标记-清除算法导致的内存碎片
4. **Concurrent Mode Failure**：老年代空间不足时，退化为 Serial Old 收集（Full GC 且 STW）

```bash
# 触发 Concurrent Mode Failure
-XX:CMSInitiatingOccupancyFraction=75  # 设置触发阈值（默认 92%）
-XX:+UseCMSInitiatingOccupancyOnly     # 仅使用此阈值（不自动调整）
```

### 4.5 G1 收集器（Garbage First）

JDK 7 引入，JDK 9 成为默认收集器。将堆划分为多个大小相等的 **Region**，新生代和老年代不再是物理隔离。

**Region 布局：**

```
┌────┬────┬────┬────┬────┬────┬────┬────┐
│ E  │ E  │ S  │ O  │ O  │ H  │ O  │ O  │
├────┼────┼────┼────┼────┼────┼────┼────┤
│ O  │ E  │ E  │ E  │ S  │ O  │ O  │ H  │
├────┼────┼────┼────┼────┼────┼────┼────┤
│ O  │ O  │ E  │ E  │ O  │ O  │ O  │ O  │
└────┴────┴────┴────┴────┴────┴────┴────┘
E: Eden    S: Survivor    O: Old    H: Humongous（大对象）
```

**G1 的特点：**
- **可预测的停顿时间模型**：`-XX:MaxGCPauseMillis`（默认 200ms）
- **Region 化**：每个 Region 的大小为 1MB~32MB（2 的幂）
- **优先回收收益最大的 Region**（"Garbage First" 的由来）
- **整体是标记-整理算法**：不会产生 CMS 的内存碎片问题

**G1 的 GC 周期：**

```
Young GC（新生代回收） ──▶ 并发标记周期（当堆使用率达阈值）──▶ Mixed GC
    │                           │                              │
  复制算法                  初始标记(STW)                 混合回收年轻代+老年代
                           并发标记
                           重新标记(STW)
                           清理(STW)
```

**关键参数：**
- `-XX:+UseG1GC`
- `-XX:MaxGCPauseMillis=200`：目标停顿时间
- `-XX:G1HeapRegionSize=4m`：Region 大小
- `-XX:InitiatingHeapOccupancyPercent=45`：触发并发标记的堆占用率
- `-XX:G1MixedGCLiveThresholdPercent=85`：Mixed GC 中 Region 的存活对象阈值

### 4.6 ZGC 收集器

JDK 11 引入的**低延迟**收集器（JDK 15 转正），GC 停顿时间不超过 **10ms**，且与堆大小无关。

**核心技术：**

1. **染色指针（Colored Pointers）**
   - 在 64 位指针中，将高 4 位用于标记状态
   - 对象本身不需要 Mark Word，标记信息直接存在于引用中

   ```
   64-bit 指针布局：
   [0000][42-bit 地址][4-bit 预留][4-bit 元数据]
    ↑                                      ↑
   颜色位                               Finalizable/Remapped/Marked0/Marked1
   ```

2. **读屏障（Load Barrier）**
   - 应用程序读取对象引用时触发
   - 如果发现指针状态异常，在读取时修正（自愈）
   - 并发标记的核心支撑

3. **基于 Region 的并发整理**
   - 与 G1 类似，但 Region 大小可变（动态）
   - 并发移动对象，不需要 STW

**ZGC vs G1：**

| 特性 | G1 | ZGC |
|------|-----|-----|
| 最大停顿 | ~200ms | <10ms |
| 停顿与堆大小关系 | 成正比 | 基本无关 |
| 算法 | 增量更新 + SATB | 染色指针 + 读屏障 |
| JDK 版本 | JDK 7+ | JDK 11+（实验）/ JDK 15+（正式）|
| 适用场景 | 通用 | 大堆低延迟 |

```bash
# 启用 ZGC
-XX:+UseZGC

# ZGC 关键参数
-Xmx16g               # ZGC 推荐配置足够大的堆
-XX:ConcGCThreads=4   # 并发 GC 线程数
-XX:ZCollectionInterval=120  # 最大 GC 间隔（秒）
```

## 五、总结

### 收集器选型指南

| 场景 | 推荐收集器 | 组合参数 |
|------|-----------|---------|
| 单核/小内存/客户端 | Serial | `-XX:+UseSerialGC` |
| 高吞吐量/后台计算 | Parallel | `-XX:+UseParallelGC` |
| 低延迟/响应式服务 | G1（JDK 9+ 默认） | `-XX:+UseG1GC` |
| 极低延迟/大堆 | ZGC | `-XX:+UseZGC` |

### GC 核心参数速查

| 参数 | 作用 |
|------|------|
| `-Xms` / `-Xmx` | 堆最小/最大大小 |
| `-Xmn` | 新生代大小 |
| `-XX:NewRatio` | 老年代/新生代比例（默认 2）|
| `-XX:SurvivorRatio` | Eden/Survivor 比例（默认 8）|
| `-XX:+PrintGCDetails` | 打印 GC 详细日志 |
| `-XX:+PrintGCDateStamps` | GC 时间戳 |
| `-Xloggc:gc.log` | GC 日志输出文件 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 时自动堆转储 |

---

**相关阅读：**
- [JVM 内存区域与内存溢出]({{< relref "post/jvm-memory-area" >}})
- [JVM 类加载机制]({{< relref "post/jvm-classloader" >}})
