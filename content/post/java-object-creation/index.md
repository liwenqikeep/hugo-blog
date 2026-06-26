---
title: "Java 对象创建与访问：从字节码到 HotSpot 源码"
date: 2018-04-24
draft: false
categories: ["Java"]
tags: ["JVM", "HotSpot", "对象创建", "OOP-Klass", "TLAB", "源码分析", "指针压缩"]
toc: true
---

## 前言

当你在 Java 代码中写下一个 `new` 关键字时，JVM 在背后做了多少工作？

从字节码指令到操作系统分配内存，这中间经过了一条完整且精心设计的链路。本文将逐层深入，从字节码出发，沿着 HotSpot 源码的执行路径，完整还原一个 Java 对象从无到有的全过程。

<!--more-->

## 一、一切始于 new 指令

### 1.1 字节码层面

先看一段最简单的 Java 代码：

```java
public class ObjectCreation {
    public static void main(String[] args) {
        Object obj = new Object();
    }
}
```

用 `javap -c -p -verbose` 看到的字节码：

```java
public static void main(java.lang.String[]);
    descriptor: ([Ljava/lang/String;)V
    flags: ACC_PUBLIC, ACC_STATIC
    Code:
        stack=2, locals=2, args_size=1
           0: new           #7      // class java/lang/Object
           3: dup
           4: invokespecial #1      // Method java/lang/Object."<init>":()V
           7: astore_1
           8: return
```

`new` 指令只是第一步，它负责在堆上分配内存（相当于 C 语言的 `malloc`），但此时对象的字段都还是零值，构造器（`<init>`）还没有执行。`dup` 复制引用用于构造器调用，`invokespecial` 执行构造器，`astore_1` 将引用存入局部变量表。

### 1.2 HotSpot 字节码解释器中的 new

在 HotSpot 源码中，`new` 指令的解释执行入口在 [`bytecodeInterpreter.cpp`](http://hg.openjdk.java.net/jdk8/jdk8/hotspot/file/87ee5ee27509/src/share/vm/interpreter/bytecodeInterpreter.cpp) 中，核心路径如下：

```cpp
// bytecodeInterpreter.cpp (JDK 8 HotSpot)
CASE(_new) {
    u2 index = Bytes::get_Java_u2(pc + 1);
    // 从常量池中获取类信息
    ConstantPoolEntry* cp = istate->constants();
    // 如果类尚未解析，触发类加载
    if (!cp->is_resolved(index)) {
        // 通过 SystemDictionary 解析类
        CALL_VM(InterpreterRuntime::_new(THREAD, (ConstantPool*)cp, index),
                handle_exception);
        // 确保类已初始化
        CALL_VM(InterpreterRuntime::initialize_for_new(THREAD, (ConstantPool*)cp, index),
                handle_exception);
    }
    // 获取 Klass（类的元数据），分配对象
    Klass* klass = cp->resolved_klass_at(index);
    // 核心分配逻辑
    oop obj = InstanceKlass::allocate_instance(klass, THREAD);
    // 将对象引用压入操作数栈
    SET_STACK_OBJECT(obj, 0);
}
```

关键调用链：
```
new 字节码
  → InterpreterRuntime::_new()    // 类加载检查
  → InstanceKlass::allocate_instance()  // 实际分配
```

## 二、类加载检查

### 2.1 常量池解析

当 `new` 指令首次执行时，类还未被加载到 JVM 中。HotSpot 在 [`InterpreterRuntime::_new()`](http://hg.openjdk.java.net/jdk8/jdk8/hotspot/file/87ee5ee27509/src/share/vm/interpreter/interpreterRuntime.cpp) 中触发类解析：

```cpp
// interpreterRuntime.cpp
IRT_ENTRY(void, InterpreterRuntime::_new(JavaThread* thread, ConstantPool* pool, int index))
    Klass* k = pool->klass_at(index, CHECK);  // 触发类解析
    // 如果类已经解析过，直接返回
IRT_END
```

而 `ConstantPool::klass_at()` 最终会调用 `SystemDictionary::resolve_or_fail()`，触发完整的类加载过程：

```
ConstantPool::klass_at()
  → SystemDictionary::resolve_or_fail()
    → SystemDictionary::resolve_instance_class_or_null()
      → ClassFileParser::parseClassFile()  // 解析 .class 文件
        → 返回 InstanceKlass（类的运行时元数据）
```

### 2.2 初始化触发

类加载完成后，还需要确保类已经完成**初始化**（`<clinit>` 已执行）。HotSpot 通过 `InstanceKlass::initialize()` 检查：

```cpp
// instanceKlass.cpp
void InstanceKlass::initialize(TRAPS) {
    if (this->is_initialized()) return;  // 已初始化，直接返回
    // 1. 如果父类未初始化，先初始化父类
    if (this->super() != NULL && !this->super()->is_initialized()) {
        this->super()->initialize(CHECK);
    }
    // 2. 执行 <clinit> 方法
    this->link_class(CHECK);  // 确保已链接
    this->call_class_initializer(THREAD);  // 调用 <clinit>
    // 3. 设置初始化完成标志
    this->set_initialized();
}
```

这里保证了 JVM 规范中六种**主动引用**场景——`new` 就是第一种，会触发类的初始化。

## 三、内存分配

类确认已初始化后，进入真正的内存分配阶段。HotSpot 的分配路径在 `InstanceKlass::allocate_instance()`：

### 3.1 对象大小确定

```cpp
// instanceKlass.cpp
instanceOop InstanceKlass::allocate_instance(TRAPS) {
    // 1. 获取对象大小（在类加载时已经计算好）
    int size = size_helper();  // 以 HeapWord 为单位
    // 2. 分配内存
    Klass* klass = this;
    HeapWord* result = CollectedHeap::allocate_from_tlab(klass, size, CHECK_NULL);
    if (result == NULL) {
        // TLAB 分配失败，退化为堆分配
        result = CollectedHeap::allocate_outside_tlab(klass, size, CHECK_NULL);
    }
    // 3. 将 HeapWord* 包装为 oop 并初始化对象头
    instanceOop obj = (instanceOop)result;
    obj->set_mark(markOopDesc::prototype());
    obj->set_klass(klass);
    return obj;
}
```

### 3.2 TLAB（Thread Local Allocation Buffer）

TLAB 是 HotSpot 最重要的优化之一——每个线程在堆中预分配一块缓冲区，线程内对象分配**无需同步**。

```cpp
// collectedHeap.inline.hpp
HeapWord* CollectedHeap::allocate_from_tlab(Klass* klass, int size, TRAPS) {
    Thread* thread = THREAD;
    if (UseTLAB) {  // 默认开启
        HeapWord* obj = thread->tlab().allocate(size);
        if (obj != NULL) return obj;
        // TLAB 空间不足，尝试重新填充
        return thread->tlab().allocate_or_fill_slow(klass, size);
    }
    return NULL;  // 未开启 TLAB，走堆分配
}
```

**TLAB 的分配逻辑 (`threadLocalAllocBuffer.cpp`)：**

```cpp
// threadLocalAllocBuffer.cpp
HeapWord* ThreadLocalAllocBuffer::allocate(size_t size) {
    // top 指向当前可用位置，end 指向 TLAB 边界
    if (pointer_delta(end(), _top) >= size) {
        HeapWord* obj = _top;
        _top = obj + size;
        return obj;  // 简单移动指针，无锁！
    }
    return NULL;  // TLAB 空间不足
}
```

**TLAB 的核心参数：**

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `-XX:+UseTLAB` | true | 启用 TLAB |
| `-XX:TLABSize` | 0（自动计算）| TLAB 初始大小 |
| `-XX:TLABRefillWasteFraction` | 64 | TLAB 重填充浪费比例 |
| `-XX:ResizeTLAB` | true | 是否动态调整 TLAB 大小 |

当 TLAB 空间不足以分配当前对象时，TLAB 有两种处理方式：

1. **浪费剩余空间，申请新 TLAB** — 如果剩余空间浪费比例小于 `TLABRefillWasteFraction`
2. **直接在堆上分配（不浪费 TLAB）** — 如果废弃比例过大

```cpp
// threadLocalAllocBuffer.cpp
HeapWord* ThreadLocalAllocBuffer::allocate_or_fill_slow(Klass* klass, size_t size) {
    // 计算剩余空间浪费比例
    size_t waste = pointer_delta(end(), _top);
    if (waste < (size_t)_refill_waste_limit) {
        // 浪费小，重新填充 TLAB（丢弃现有 TLAB）
        this->clear();
        this->fill(klass, size);
        return this->allocate(size);
    } else {
        // 浪费大，直接在堆上分配（保留当前 TLAB 的剩余空间）
        return NULL;  // 走 allocate_outside_tlab
    }
}
```

### 3.3 堆分配：指针碰撞 vs 空闲列表

当 TLAB 分配失败后，进入 `CollectedHeap::allocate_outside_tlab()`。这里的选择取决于垃圾收集器：

```cpp
// 不同 GC 的分配逻辑

// 1. Serial / ParNew 等（使用标记-复制/标记-整理）
//    堆内存规整 → 指针碰撞（Bump-the-Pointer）
HeapWord* result = _heap->allocate_from_contiguous_space(size);
// 只需移动 top 指针，CAS 保证线程安全

// 2. CMS（使用标记-清除）
//    堆内存碎片化 → 空闲列表（Free List）
HeapWord* result = _heap->allocate_from_free_list(size);
// 遍历空闲列表找到合适的块
```

对于 G1 收集器，分配还要考虑 Region：

```cpp
// G1CollectedHeap.cpp
HeapWord* G1CollectedHeap::allocate_new_tlab(size_t min_size,
                                             size_t requested_size,
                                             size_t* actual_size) {
    // G1 在分配新 TLAB 时，需要保证 Region 内有连续空间
    HeapRegion* hr = _hrm->allocate_region();
    // ...
}
```

**指针碰撞（Bump-the-Pointer）**

```
分配前：
┌────┬────┬────┬────┬────┬────┬────┬────┐
│   已分配对象   │                free                │
└────┴────┴────┴────┴────┴────┴────┴────┘
                  ↑
                 top → 下一个可用位置

分配后：
┌────┬────┬────┬────┬────┬────┬────┬────┐
│   已分配对象   │ new obj │          free          │
└────┴────┴────┴────┴────┴────┴────┴────┘
                           ↑
                          top
```

**空闲列表（Free List）：**

```
分配前：
┌────┬────┬────┬────┬────┬────┬────┬────┐
│  A  │free1│  B  │free2│  C  │free3│     │
└────┴────┴────┴────┴────┴────┴────┴────┘
              ↑            ↑
          16 bytes      32 bytes

分配 24 bytes → 选中 free2 → 拆分：
┌────┬────┬────┬────┬────┬────┬────┬────┐
│  A  │free1│  B  │new obj│  C  │ 8B  │     │
└────┴────┴────┴────┴────┴────┴────┴────┘
                           ↑
                      剩余 8 字节放回空闲列表
```

### 3.4 分配并发安全

对于非 TLAB 的堆分配，需要处理线程安全问题。HotSpot 使用 **CAS + 失败重试**：

```cpp
// 指针碰撞场景的 CAS 分配
HeapWord* ContiguousSpace::par_allocate(size_t size) {
    while (true) {
        HeapWord* old_top = _top;
        HeapWord* new_top = old_top + size;
        if (new_top <= _end) {
            // CAS 更新 top 指针
            if (Atomic::cmpxchg_ptr(_top, old_top, new_top) == old_top) {
                return old_top;  // 分配成功
            }
            // CAS 失败，重试
        } else {
            return NULL;  // 空间不足
        }
    }
}
```

## 四、对象内存初始化

### 4.1 零值填充

在 C++ 层面，HotSpot 分配的内存通过 `CollectedHeap` 的 `allocate()` 返回，此时内存尚未清零。零值填充在分配时完成，取决于不同的 GC 实现：

```cpp
// 以 Parallel GC 为例
HeapWord* ParallelScavengeHeap::mem_allocate(size_t size) {
    HeapWord* result = _young_gen->allocate(size);
    if (result != NULL) {
        // 对象分配后，确保已清零
        // Parallel GC 使用预清零的内存（precleaned），或在分配时 memset
        Copy::fill_to_words(result, size, 0);  // 零值填充
    }
    return result;
}
```

**这意味着什么：**
- 对象的 `boolean` 字段初始为 `false`
- `int` 字段初始为 `0`
- 引用类型字段初始为 `null`
- 这一切发生在构造器执行之前

### 4.2 对象头设置

对象头由两部分组成：**Mark Word** 和 **Klass Pointer**。

**Mark Word（`markOop.hpp`）：**

Mark Word 是对象头的核心，在 64 位 JVM 中占 8 字节，其格式随对象状态变化：

```
64位 Mark Word（无锁状态）：
|------------------------------------------------------------------------|
|  unused:25  |  identity_hashcode:31  |  unused:1  |  age:4  |  biased_lock:1  |  lock:2  |
|------------------------------------------------------------------------|

各状态下的 Mark Word 复用：
┌──────────────┬──────────────────────────────────────────────────────┐
│ 对象状态       │ Mark Word 内容                                      │
├──────────────┼──────────────────────────────────────────────────────┤
│ 无锁          │ biased_lock=0 | lock=01 → 存储 hashcode + GC age    │
│ 偏向锁        │ biased_lock=1 | lock=01 → 存储线程 ID + epoch + age │
│ 轻量级锁      │ lock=00 → 指向栈中锁记录的指针                       │
│ 重量级锁      │ lock=10 → 指向管程 Monitor 的指针                   │
│ GC 标记       │ lock=11 → 标记信息（GC 转发指针等）                  │
└──────────────┴──────────────────────────────────────────────────────┘
```

在对象分配时，初始的 Mark Word 通过 `markOopDesc::prototype()` 设置：

```cpp
// markOop.hpp
static markOop prototype() {
    return markOop( no_hash_in_place | no_lock_in_place );
}
```

**Klass Pointer（类型指针）：**

指向方法区的 `InstanceKlass` 元数据，记录对象所属的类信息：

- 64 位 JVM 默认开启指针压缩时占 **4 字节**（堆大小 < 32GB）
- 64 位 JVM 关闭指针压缩时占 **8 字节**

### 4.3 对象头初始化源码

回到 `InstanceKlass::allocate_instance()`，对象头初始化的完整逻辑：

```cpp
// instanceKlass.cpp — 简化核心路径
instanceOop InstanceKlass::allocate_instance(TRAPS) {
    int size = size_helper();
    Klass* klass = this;
    
    // 1. 从 TLAB 或堆分配原始内存
    HeapWord* result = CollectedHeap::allocate_from_tlab(klass, size, CHECK_NULL);
    if (result == NULL) {
        result = CollectedHeap::allocate_outside_tlab(klass, size, CHECK_NULL);
    }
    
    // 2. 包装为 OOP
    instanceOop obj = (instanceOop)result;
    
    // 3. 设置 Mark Word — 初始化为无锁状态的原型
    obj->set_mark(markOopDesc::prototype());
    
    // 4. 设置 Klass Pointer — 指向方法区的 InstanceKlass
    obj->set_klass(klass);
    
    // 5. 注意：实例数据已经在分配阶段清零（零值初始化）
    //    不需要额外操作
    
    return obj;
}
```

### 4.4 对象内存布局总览

以一个简单的 `Point` 类为例：

```java
public class Point {
    int x;     // 实例数据
    int y;     // 实例数据
}
```

**对象在 64 位 JVM + 指针压缩下的内存布局：**

```
offset  │ 内容
────────┼──────────────────────────────────
0 ~ 7   │ Mark Word（8 字节）
        │  [hashcode:25|GC age:4|biased:1|lock:2] (初始无锁态)
8 ~ 11  │ Klass Pointer（4 字节，指针压缩）
        │  → 指向方法区的 InstanceKlass<Point>
12      │ （对齐填充起始）
12 ~ 13 │ padding（4 字节对齐需要）
14 ~ 15 │ padding
16 ~ 19 │ Point.x（int，4 字节）
20 ~ 23 │ Point.y（int，4 字节）
────────┼──────────────────────────────────
总大小   │ 24 字节（8 字节对齐的整数倍）
```

**关闭指针压缩时（`-XX:-UseCompressedOops`）：**

```
offset  │ 内容
────────┼──────────────────────────────────
0 ~ 7   │ Mark Word（8 字节）
8 ~ 15  │ Klass Pointer（8 字节，未压缩）
16 ~ 19 │ Point.x（int，4 字节）
20 ~ 23 │ Point.y（int，4 字节）
────────┼──────────────────────────────────
总大小   │ 24 字节（天然对齐）
```

可以看到指针压缩在这个例子里没有减少总大小（因为对齐填充），但当对象字段较多时效果显著。

## 五、对象访问定位

对象创建完成后，程序如何访问它？这涉及 HotSpot 的 OOP-Klass 二分模型。

### 5.1 OOP-Klass 二分模型

HotSpot 采用 **OOP（Ordinary Object Pointer）** 和 **Klass** 两个概念来分别描述对象：

```
OOP（堆中的对象实例）
┌──────────────────┐
│   Mark Word       │  ← 运行时状态（锁、hashcode、GC 年龄）
├──────────────────┤
│   Klass Pointer   │───→ 指向 InstanceKlass（方法区）
├──────────────────┤
│   实例数据         │  ← 字段值
├──────────────────┤
│   对齐填充         │
└──────────────────┘
       ↑
       │ Java 程序通过引用（reference）持有
```

```cpp
// oop.hpp — OOP 的 C++ 定义
class oopDesc {
    friend class VMStructs;
private:
    volatile markOop _mark;   // Mark Word
    union _metadata {
        Klass*      _klass;   // 未压缩的 Klass 指针
        narrowKlass _compressed_klass;  // 压缩后的 Klass 指针
    } _metadata;
    
public:
    // 获取对象头的 Mark Word
    markOop mark() const { return _mark; }
    
    // 获取 Klass（元数据）
    Klass* klass() const;
    
    // 判断对象类型
    bool is_instance() const;  // 普通对象
    bool is_array() const;     // 数组对象
};
```

**为什么分成 OOP 和 Klass？**

- **OOP** — 存储在堆中，是对象实例数据的载体，每个对象一个
- **Klass** — 存储在方法区（元空间），是类的元数据，每个类一个

这种分离使一个 Klass 对应多个 OOP，节省了大量元数据存储空间。

### 5.2 Klass 层次结构

```cpp
// klass.hpp
class Klass {
    // 所有类元数据的基类
    // 存储在方法区（元空间）
};

class InstanceKlass : public Klass {
    // 普通 Java 类的元数据
    // 包含：常量池、方法表、字段布局、vtable 等
};

class ArrayKlass : public Klass {
    // 数组类的元数据
};

class ObjArrayKlass : public ArrayKlass {
    // 对象数组的元数据
};

class TypeArrayKlass : public ArrayKlass {
    // 基本类型数组的元数据
};
```

**Klass 中存储的部分关键信息：**

```cpp
class InstanceKlass : public Klass {
    // 类本身的信息
    Symbol* _name;                  // 类名
    u2      _major_version;         // Class 文件主版本号
    u2      _minor_version;         // Class 文件次版本号
    
    // 字段和方法的元数据
    Array<u2>* _fields;             // 字段表（按声明顺序）
    Array<Method*>* _methods;       // 方法表
    Array<u2>* _methods_annotations; // 方法注解
    
    // 运行时支持
    ConstantPool* _constants;       // 运行时常量池
    OopMapCache* _oop_map_cache;    // OopMap 缓存（GC 用）
    jint _nonstatic_field_size;     // 非静态字段大小（确定对象 size）
    Klass* _array_klasses;          // 数组关联类
    Klass* _super;                  // 父类
    Array<Klass*>* _local_interfaces; // 直接实现的接口
};
```

### 5.3 直接指针访问

HotSpot 采用**直接指针访问**：

```
Java 栈帧（reference）             堆（OOP）
┌─────────────┐                ┌─────────────────────────┐
│  reference   │ ────────────▶ │  Mark Word              │
│ （直接地址）  │                │  Klass Pointer ──────┐  │
└─────────────┘                │  实例数据              │  │
                               └─────────────────────┼──┘
                                                     │
                                                     ▼
                                              方法区（InstanceKlass）
                                              ┌───────────────────┐
                                              │  常量池             │
                                              │  方法表（vtable）    │
                                              │  字段布局            │
                                              └───────────────────┘
```

**优点：** 访问对象字段只需一次指针跳转（reference → 对象的偏移量），速度快。

**对比句柄访问（HotSpot 不采用）：**

```
Java 栈帧          句柄池                    堆
┌─────────┐    ┌────────────┐          ┌─────────────┐
│ reference│──▶│ OOP 指针    │────────▶ │ 对象实例     │
└─────────┘    │ Klass 指针  │───┐     └─────────────┘
               └────────────┘   │
                                ▼
                          ┌─────────────┐
                          │ 类型数据      │
                          └─────────────┘
```

**两种方式的对比：**

| 维度 | 直接指针（HotSpot） | 句柄 |
|------|-------------------|------|
| 访问速度 | 快（一次跳转） | 慢（两次跳转）|
| 对象移动（GC） | 需更新所有 reference | 只需改句柄池指针 |
| 实现复杂度 | 较低 | 较高 |
| 适用场景 | 追求执行效率 | 追求 GC 友好 |

### 5.4 指针压缩（Compressed OOPs）

为了在 64 位 JVM 上节省内存，HotSpot 引入了指针压缩，将 64 位引用压缩为 32 位。

```cpp
// oop.inline.hpp — 压缩和解压缩逻辑
Klass* oopDesc::klass() const {
    if (UseCompressedClassPointers) {
        // 从 _metadata._compressed_klass（32 位）解压
        return Klass::decode_klass(_metadata._compressed_klass);
    } else {
        return _metadata._klass;
    }
}

void oopDesc::set_klass(Klass* k) {
    if (UseCompressedClassPointers) {
        // 压缩 64 位 Klass 指针为 32 位
        _metadata._compressed_klass = Klass::encode_klass(k);
    } else {
        _metadata._klass = k;
    }
}
```

**压缩原理：**

```cpp
// 解码：compressed → 实际地址
// compressed = (address - heap_base) >> object_alignment_shift
// 其中 heap_base 是堆起始地址，通常为 0

static Klass* decode_klass(narrowKlass v) {
    // 左移 3 位（因为对象按 8 字节对齐，低 3 位恒为 0）
    return (Klass*)((address)base + (size_t)(v << klass_alignment_shift));
}

static narrowKlass encode_klass(Klass* v) {
    // 右移 3 位，丢弃低 3 位对齐信息
    return (narrowKlass)(((address)v - (address)base) >> klass_alignment_shift);
}
```

**为什么能压缩？**

因为堆中所有对象都按 8 字节对齐，地址的低 3 位永远是 0。所以 32 位压缩指针实际上可以寻址 32+3=35 位的地址空间，即最大 32GB 堆。

```cpp
// 压缩 OOP 的解码公式
address decode_heap_oop(narrowOop v) {
    return (address)((uint64_t)v << 3);  // 左移 3 位 = 乘以 8
}

// 堆大小上限：2^32 × 8 = 32GB
```

**关键参数：**

```bash
-XX:+UseCompressedOops       # 压缩对象引用（默认开启）
-XX:+UseCompressedClassPointers  # 压缩 Klass 指针（默认开启）

# 当堆 > 32GB 时，指针压缩自动关闭
# 也可以通过 -XX:-UseCompressedOops 手动关闭
```

### 5.5 实例字段的访问偏移量

以之前 `Point` 类的 `x` 字段为例，JVM 如何通过引用访问 `point.x`？

```
point.reference ──→ 对象基地址 + 16（x 字段偏移量）→ 读取 4 字节
                    ↓
                 [Mark Word: 8B][Klass Ptr: 4B][padding: 4B][x: 4B][y: 4B]
                                                            ↑
                                                        偏移量 16
```

这个偏移量在类加载阶段就已计算好，存储在 `InstanceKlass` 的字段布局中。**每次字段访问都不需要查找，直接通过偏移量读写**，这是 Java 字段访问效率接近 C++ 的原因。

## 六、完整的对象创建流程总结

```
Java 代码：Point p = new Point(1, 2);
    │
    ▼
【1. 字节码层面】
  new              → 分配内存
  dup              → 复制引用
  invokespecial    → 调用 <init>
  astore_1         → 存入局部变量
    │
    ▼
【2. HotSpot 解释 new 指令】
  常量池解析     → ConstantPool::klass_at()
  类加载触发     → SystemDictionary::resolve_or_fail()
  类初始化检查   → InstanceKlass::initialize()
    │
    ▼
【3. 内存分配（InstanceKlass::allocate_instance）】
  TLAB 分配     → thread->tlab().allocate(size)  [无锁]
  失败时
  ├── 指针碰撞  → CAS + 重试  [Serial/Parallel/G1 规整堆]
  └── 空闲列表  → 遍历 Free List  [CMS 碎片化堆]
    │
    ▼
【4. 对象头初始化】
  Mark Word     → markOopDesc::prototype()  [无锁态]
  Klass Pointer → 指向方法区的 InstanceKlass
    │
    ▼
【5. 零值初始化】
  所有字段置零值（0 / false / null）
  （由分配阶段的 Copy::fill_to_words 或预清零保证）
    │
    ▼
【6. 执行 <init> 构造器】
  按字节码顺序执行构造器中的赋值逻辑
    │
    ▼
【7. 返回引用】
  栈中的 reference 指向堆中的对象
  对象访问通过 OOP-Klass 模型 → 直接指针 + 偏移量
```

## 七、总结

### 各环节源码路径速查

| 阶段 | 关键源码文件 | 核心类/方法 |
|------|------------|-----------|
| 字节码解释 | `bytecodeInterpreter.cpp` | `CASE(_new)` |
| 类加载解析 | `interpreterRuntime.cpp` | `InterpreterRuntime::_new()` |
| 类加载器 | `systemDictionary.cpp` | `SystemDictionary::resolve_or_fail()` |
| 对象大小 | `instanceKlass.cpp` | `InstanceKlass::size_helper()` |
| TLAB | `threadLocalAllocBuffer.cpp` | `ThreadLocalAllocBuffer::allocate()` |
| 堆分配 | `collectedHeap.inline.hpp` | `CollectedHeap::allocate_from_tlab()` |
| CAS 分配 | `contiguousSpace.cpp` | `ContiguousSpace::par_allocate()` |
| 对象头 | `markOop.hpp` | `markOopDesc::prototype()` |
| OOP | `oop.hpp` | `oopDesc` |
| Klass | `klass.hpp` / `instanceKlass.hpp` | `InstanceKlass` |

### 内存相关 JVM 参数

| 参数 | 默认 | 作用 |
|------|------|------|
| `-XX:+UseTLAB` | true | 开启线程本地分配缓冲区 |
| `-XX:TLABSize` | 自动 | TLAB 初始大小 |
| `-XX:+UseCompressedOops` | true | 开启对象引用压缩 |
| `-XX:+UseCompressedClassPointers` | true | 开启 Klass 指针压缩 |
| `-XX:ObjectAlignmentInBytes` | 8 | 对象对齐字节数 |
| `-XX:+AlwaysPreTouch` | false | 启动时预分配物理内存 |

---

**相关阅读：**
- [JVM 内存区域与内存溢出]({{< relref "post/jvm-memory-area" >}})
- [JVM 垃圾回收机制]({{< relref "post/jvm-gc" >}})
- [Java 字节码详解]({{< relref "post/java-bytecode" >}})
