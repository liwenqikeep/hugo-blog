---
title: "JVM 性能监控与调优"
date: 2018-04-22
draft: false
categories: ["Java"]
tags: ["JVM", "性能调优", "GC", "Full GC", "内存泄漏", "CPU飙高"]
toc: true
---

## 前言

前面几篇文章分别深入了 JVM 的内存结构、GC 机制、类加载、字节码和 JIT 编译。这篇文章将这些知识落地到实战——当线上出现问题（CPU 飙升、频繁 Full GC、内存泄漏）时，如何系统性地排查和解决。

调优没有银弹，掌握方法论和工具链，比记住具体参数更重要。

<!--more-->

## 一、GC 日志分析

GC 日志是诊断 JVM 问题的第一手资料。

### 1.1 开启 GC 日志

```bash
# 基础 GC 日志
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log

# 推荐生产配置（JDK 8）
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-XX:+PrintGCTimeStamps
-XX:+PrintGCApplicationStoppedTime   # 记录 STW 时间
-XX:+PrintTenuringDistribution       # 打印对象年龄分布
-XX:+PrintHeapAtGC                   # GC 前后堆信息
-Xloggc:/path/to/gc.log
-XX:+UseGCLogFileRotation            # GC 日志滚动
-XX:NumberOfGCLogFiles=10
-XX:GCLogFileSize=10M
```

**JDK 9+ 的 GC 日志参数有变化：**

```bash
# JDK 9+ 统一使用 -Xlog
-Xlog:gc*:file=gc.log:time,uptime,level,tags
-Xlog:gc+metaspace=debug
-Xlog:safepoint=info
```

### 1.2 读懂 GC 日志

```bash
# 一段典型的 CMS GC 日志：
2026-04-22T10:15:30.123+0800: [GC (CMS Initial Mark) [1 CMS-initial-mark: 4096000K(8388608K)]
2026-04-22T10:15:30.456+0800: [CMS-concurrent-mark-start]
2026-04-22T10:15:30.789+0800: [CMS-concurrent-mark: 0.333 secs]
2026-04-22T10:15:30.789+0800: [CMS-concurrent-preclean-start]
2026-04-22T10:15:30.801+0800: [CMS-concurrent-preclean: 0.012 secs]
2026-04-22T10:15:30.801+0800: [CMS-concurrent-abortable-preclean-start]
2026-04-22T10:15:31.205+0800: [GC (CMS Final Remark) [YG occupancy: 204800K(2097152K)]
2026-04-22T10:15:31.210+0800: [Rescan (parallel) , 0.032 secs]
2026-04-22T10:15:31.242+0800: [weak refs processing, 0.001 secs]
2026-04-22T10:15:31.243+0800: [class unloading, 0.015 secs]
2026-04-22T10:15:31.258+0800: [CMS-concurrent-sweep-start]
2026-04-22T10:15:31.521+0800: [CMS-concurrent-sweep: 0.263 secs]
```

```bash
# 一段典型的 G1 GC 日志：
2026-04-22T10:15:30.123+0800: [GC pause (G1 Evacuation Pause) (young)
  Desired survivor size 104857600 bytes, new threshold 7 (max threshold 15)
  [ 22 Oct 2026 10:15:30.123  Eden: 1024.0M(1024.0M)->0.0B(1024.0M)
    Survivors: 0.0B->16.0M  Heap: 1.5G(4.0G)->32.5M(4.0G)]
  [Times: user=0.12 sys=0.01, real=0.05 secs]
```

**GC 日志关键指标解读：**

| 指标 | 含义 | 警戒线 |
|------|------|--------|
| Young GC 频率 | 年轻代回收的频率 | 几秒一次正常，几十毫秒一次需关注 |
| Full GC 频率 | 老年代回收的频率 | 出现即需关注 |
| STW 时间 | 用户线程暂停时间 | > 1s 需关注，> 5s 严重 |
| GC 后堆占用 | GC 后的堆内存使用量 | 持续上升说明有内存泄漏 |
| Promotion 大小 | 对象晋升到老年代的大小 | 突然增大需关注 |
| Metaspace | 元空间使用量 | 持续上升说明类加载泄漏 |

### 1.3 GC 日志分析工具

```bash
# 1. GCeasy（在线工具）
#    - 上传 gc.log 即可
#    - 自动识别问题模式（频繁 Full GC、内存泄漏等）

# 2. GCViewer（本地工具）
#    - 开源 Java 工具
#    - 图形化展示吞吐量、停顿时间、内存使用趋势

# 3. gceasy.io
#    - 在线分析，推荐给到客户使用时注意数据安全
```

## 二、堆内存调优

### 2.1 参数配置原则

**1. -Xms 和 -Xmx 设为相同值**

```bash
# 推荐：设成相同值，避免 JVM 运行时动态调整堆大小
-Xms4g -Xmx4g
```

**2. 新生代大小**

```bash
# 方式一：直接指定新生代大小
-Xmn1g

# 方式二：指定比例（NewRatio 默认 2，即老年代:新生代 = 2:1）
-XX:NewRatio=2
```

**3. Survivor 区比例**

```bash
# SurvivorRatio 默认 8，即 Eden:Survivor = 8:1
# 最终 Eden 占新生代的 80%
-XX:SurvivorRatio=8
```

**4. 元空间**

```bash
# JDK 8+，元空间默认只受本地内存限制
# 建议设置上限防止无限增长
-XX:MetaspaceSize=256m
-XX:MaxMetaspaceSize=256m
```

### 2.2 典型场景的堆配置

```bash
# 场景 1：高并发 Web 服务
# 特点：创建大量临时对象，适合大新生代
-Xms4g -Xmx4g -Xmn2g -XX:SurvivorRatio=8 -XX:+UseG1GC

# 场景 2：大内存批处理（如大数据处理）
# 特点：对象存活率高，适合大老年代
-Xms16g -Xmx16g -Xmn4g -XX:SurvivorRatio=6 -XX:+UseParallelGC

# 场景 3：低延迟响应服务
# 特点：对 STW 敏感
-Xms4g -Xmx4g -XX:MaxGCPauseMillis=100 -XX:+UseZGC

# 场景 4：内存敏感型（容器环境受限）
# 特点：需要精确控制内存
-Xms512m -Xmx512m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC
```

### 2.3 最佳实践——不要太早优化

```
调优之前先回答三个问题：
1. 真的有性能问题吗？— 先监控，不凭感觉
2. 问题在 JVM 层面吗？— 排除代码层面的问题（SQL、I/O 等）
3. 你能量化目标和结果吗？— 没有量化就没有优化
```

## 三、实战案例 1：频繁 Full GC

### 3.1 问题现象

```bash
# GC 日志中出现频繁的 Full GC
2026-04-22T10:15:00.123+0800: [Full GC (Allocation Failure) 4.0G->3.8G(4.0G), 2.5 secs]
2026-04-22T10:15:03.456+0800: [Full GC (Allocation Failure) 3.9G->3.7G(4.0G), 2.3 secs]
2026-04-22T10:15:06.789+0800: [Full GC (Allocation Failure) 3.8G->3.6G(4.0G), 2.7 secs]
# 特征：Full GC 后老年代占用下降很少（4.0G → 3.8G），且间隔极短
```

### 3.2 排查流程

```
Full GC 频繁
    │
    ▼
确认 GC 后老年代占用
├── 占用很高且降不下来 → 内存泄漏（步骤 A）
└── 占用不高或持续下降 → 可能是 GC 参数不合理（步骤 B）
    │
    ▼
步骤 A：内存泄漏排查
  1. 获取堆转储：jmap -dump:format=b,file=heap.hprof <pid>
  2. 使用 MAT 分析
     - 查看 biggest objects
     - 查找 GC Roots 引用链
     - 计算 retained size
  3. 常见泄漏模式：
     - 集合类未清理（HashMap 不断 put 不 remove）
     - ThreadLocal 未 remove
     - 连接/流未关闭
     - 静态集合持有对象引用
    │
    ▼
步骤 B：参数调整
  1. 检查是否 CMS 并发模式失败
     - 降低触发阈值：-XX:CMSInitiatingOccupancyFraction=70
  2. 检查新生代是否过小（对象过早晋升）
     - 增大新生代：-Xmn
     - 增大 Survivor 区
  3. 检查大对象分配
     - G1：调整 Region 大小，关注 Humongous 分配
```

### 3.3 内存泄漏排查实例

```java
// 示例：ThreadLocal 未清理导致的内存泄漏
public class ThreadLocalLeakFix {
    private static final ThreadLocal<byte[]> threadLocal = new ThreadLocal<>();
    
    // 错误写法
    public void processBad() {
        byte[] data = loadData();
        threadLocal.set(data);  // 存入后忘记 remove
        doProcess(data);
        // threadLocal 中的值在线程池场景下不会被回收
    }
    
    // 正确写法
    public void processGood() {
        byte[] data = loadData();
        threadLocal.set(data);
        try {
            doProcess(data);
        } finally {
            threadLocal.remove();  // 确保用完清理
        }
    }
}
```

```java
// 示例：静态集合泄漏
public class CacheLeakFix {
    // 错误写法——静态 Map 只增不减
    private static final Map<String, Session> SESSION_CACHE = new HashMap<>();
    
    public void cacheSession(String token, Session session) {
        SESSION_CACHE.put(token, session);  // 永远不 remove
    }
    
    // 正确写法——使用过期机制
    private static final Cache<String, Session> SESSION_CACHE = 
            Caffeine.newBuilder()
                    .expireAfterAccess(30, TimeUnit.MINUTES)
                    .maximumSize(10000)
                    .build();
}
```

## 四、实战案例 2：CPU 飙升

### 4.1 问题现象

```bash
# top 或任务管理器发现 Java 进程 CPU 占用 > 100%
# 通常表现为整机响应变慢，接口超时
```

### 4.2 排查流程

```
CPU 飙升
    │
    ▼
1. 定位占用 CPU 最高的线程
   top -Hp <pid>         # Linux
   或
   Process Explorer      # Windows
   找到最耗 CPU 的线程 ID（十进制）
    │
    ▼
2. 将线程 ID 转换为十六进制
   printf '%x\n' <tid>   # 如 12345 → 0x3039
    │
    ▼
3. 获取线程栈
   jstack <pid> | findstr "0x3039" -A 30
    │
    ▼
4. 分析栈信息
   ├── GC 线程（GC task thread#0）→ GC 问题
   ├── 业务代码热点 → 代码问题
   ├── 锁竞争 → 并发问题
   └── 死循环 → bug
```

### 4.3 常见原因与解决

**原因 1：GC 线程占满 CPU**

```bash
# 特征：jstack 显示大量 "GC task thread"
# 对策：分析 GC 日志，解决 GC 问题
```

**原因 2：业务代码死循环**

```java
// 特征：某个业务线程持续占 CPU
// 排查：jstack 发现线程卡在某个方法

// 常见死循环模式：
while (queue.peek() != null) {  // 应该用 poll()
    // queue 有可能为空但多次 peek 返回同一个不为 null 的元素
    doProcess(queue.peek());
}

// 修复：使用 poll() 消费元素
while (true) {
    Task task = queue.poll();
    if (task == null) break;
    doProcess(task);
}
```

**原因 3：频繁的锁竞争**

```bash
# 特征：jstack 显示大量线程在 BLOCKED 状态
# 并且堆栈中都卡在同一个锁上

# 排查方法：
# 1. 找到锁对象（看堆栈中的 synchronized 或 Lock）
# 2. 确认锁的持有者在干什么
# 3. 评估是否需要优化锁粒度或使用无锁数据结构
```

## 五、实战案例 3：内存泄漏排查

### 5.1 发现阶段

```bash
# 监控指标：堆内存使用量持续上升
# 即使 Full GC 后也不回落到基线水平

# 使用 jstat 监控
jstat -gcutil <pid> 1000 10

# 输出示例：
# S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT     GCT
# 0.00  98.24  85.32  92.15  85.21  78.15  1234   12.34    5     15.67   28.01
# 0.00  98.45  86.10  93.20  85.22  78.16  1235   12.36    6     18.90   31.26
# 0.00  98.56  87.05  94.35  85.23  78.17  1236   12.38    7     22.13   34.51
#                                ↑ 老年代占用持续上升

# 重点观察：O（Old）列是否持续上升
```

### 5.2 诊断阶段

```bash
# 1. 获取堆转储
jmap -dump:live,format=b,file=dump.hprof <pid>

# 注意：-dump:live 会触发 Full GC，建议在低峰期执行
# 如果不触发 Full GC，使用 -dump:all
```

### 5.3 分析阶段（使用 MAT）

```bash
# MAT 分析步骤：
# 1. 打开 dump.hprof 文件
# 2. 查看 "Leak Suspects"（泄漏嫌疑分析）
# 3. 查看 "Biggest Objects"（最大对象）
# 4. 对嫌疑对象执行 "Path to GC Roots"（GC Roots 路径）
#    - 排查时排除弱引用（弱引用可能是 ThreadLocal 的正常情况）
# 5. 确认哪个对象持有引用导致泄漏
```

**常见泄漏模式速查表：**

| 泄漏模式 | 特征 | 修复方案 |
|---------|------|---------|
| 集合未清理 | HashMap 持续增长 | 使用 WeakHashMap 或带过期策略的缓存 |
| ThreadLocal | GC 后堆占用不降 | 在 finally 中 remove() |
| 类加载器泄漏 | Metaspace 持续增长 | 检查是否有热部署或动态代理创建类 |
| 连接池泄漏 | 大量连接对象 | 确保 close() 在 finally 中执行 |
| 回调/监听器 | 注册后未取消 | 使用弱引用或确保取消注册 |

### 5.4 实战：一个完整的内存泄漏排查

```java
// 问题代码
@Component
public class EventBusManager {
    private final Map<String, List<EventListener>> listeners = new HashMap<>();
    
    public void register(String eventType, EventListener listener) {
        listeners.computeIfAbsent(eventType, k -> new ArrayList<>())
                .add(listener);
    }
    
    // 用户登出时，用户的 listener 没有被移除
}

// 排查结果：MAT 发现 listener 列表中持有大量已登录用户的 Listener 对象
// 修复：登出时调用 unregister，或使用 WeakReference 包装 listener
```

## 六、JVM 调优参数速查

### 6.1 通用参数

| 参数 | 作用 | 推荐值 |
|------|------|--------|
| `-Xms` / `-Xmx` | 堆初始/最大大小 | 设为相同值 |
| `-Xmn` | 新生代大小 | 堆的 1/3 ~ 1/4 |
| `-XX:MetaspaceSize` | 元空间初始大小 | 256m |
| `-XX:MaxMetaspaceSize` | 元空间最大大小 | 256m |
| `-Xss` | 线程栈大小 | 256k~1m（视业务定）|
| `-XX:+AlwaysPreTouch` | 启动时预分配物理内存 | 减少运行时页错误 |

### 6.2 GC 收集器选择

```bash
# 小内存（< 2G）、单核
-XX:+UseSerialGC

# 高吞吐量、后台任务、批处理
-XX:+UseParallelGC -XX:+UseParallelOldGC

# 低延迟、Web 服务（JDK 8 推荐 G1）
-XX:+UseG1GC -XX:MaxGCPauseMillis=200

# 极低延迟、大堆（JDK 15+）
-XX:+UseZGC
```

### 6.3 调试诊断参数

```bash
# OOM 时自动堆转储（必须开启）
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/path/to/dumps/

# GC 日志（生产必备）
-Xloggc:/path/to/gc.log
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps

# 记录应用停顿时间
-XX:+PrintGCApplicationStoppedTime
-XX:+PrintGCApplicationConcurrentTime
```

### 6.4 调优参数参考（容器环境）

```bash
# Docker/K8s 环境下推荐
# 使用 JDK 10+ 可以自动识别容器内存限制
-XX:+UseContainerSupport
-XX:InitialRAMPercentage=50.0
-XX:MaxRAMPercentage=75.0
-XX:MinRAMPercentage=50.0
```

## 七、排查问题方法论总结

### 7.1 系统化排查流程

```
问题现象（CPU 高 / 响应慢 / OOM）
    │
    ▼
1. 确认问题范围
   ├── 单机还是一组？
   ├── 全接口还是特定接口？
   └── 持续问题还是偶发？
    │
    ▼
2. 收集现场信息
   ├── top / jstack / jstat / jmap
   ├── GC 日志
   └── 监控指标
    │
    ▼
3. 提出假设
   ├── GC 频繁？
   ├── 死锁？
   ├── 内存泄漏？
   └── 代码 bug？
    │
    ▼
4. 验证假设
   └── 分析数据 → 找到根因
    │
    ▼
5. 修复验证
   └── 修复后确认问题解决
```

### 7.2 常用工具速记

```bash
# 进程级
jps -l                # 列出所有 Java 进程
jinfo <pid>           # 查看 JVM 参数配置

# 线程级
jstack <pid>          # 打印线程栈
jstack -l <pid>       # 打印线程栈 + 锁信息

# 堆级
jmap -heap <pid>      # 查看堆配置和使用情况
jmap -histo <pid>     # 查看堆中对象分布
jmap -dump:format=b,file=dump.hprof <pid>  # 堆转储

# GC 级
jstat -gcutil <pid> 1000 10  # 每 1 秒打印一次 GC 信息

# 其他
jcmd <pid> help              # 多功能诊断命令
jcmd <pid> VM.flags          # 查看生效的 JVM 参数
```

## 八、总结

### 调优三个原则

1. **先度量，再优化** — 没有数据支撑的调优是瞎调
2. **一次只改一个参数** — 改多个参数无法确定效果来源
3. **以业务指标为准** — 调优的目标是业务响应时间和吞吐量，不是某个 JVM 指标

### 最常见问题的快速判断

| 症状 | 可能原因 | 第一排查方向 |
|------|---------|------------|
| 频繁 Full GC | 内存泄漏 / 堆太小 | jstat -gcutil 看老年代趋势 |
| CPU 飙高 | GC 线程 / 业务热点 | top -H + jstack |
| 接口超时 | GC STW / 锁竞争 | jstat 看 GC 停顿 + jstack 看阻塞 |
| OOM 重启 | 内存泄漏 / 内存不足 | 开启 HeapDumpOnOutOfMemoryError |
| MetaSpace OOM | 类加载泄漏 | jstat -gc 看 Metaspace 增长趋势 |

---

**相关阅读：**
- [JVM 内存区域与内存溢出]({{< relref "post/jvm-memory-area" >}})
- [JVM 垃圾回收机制]({{< relref "post/jvm-gc" >}})
- [JIT 即时编译（下）：逃逸分析与循环优化]({{< relref "post/jvm-jit-compilation-02" >}})
