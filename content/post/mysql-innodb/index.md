---
title: "MySQL（三）：InnoDB 存储引擎"
date: 2018-07-12
draft: false
categories: ["数据库"]
tags: ["MySQL", "InnoDB", "行格式", "页结构", "表空间", "MVCC", "Redo Log"]
toc: true
---

## 前言

InnoDB 是 MySQL 默认的存储引擎，也是生产环境中最常用的引擎。理解 InnoDB 的底层存储结构——行格式、页结构、表空间、MVCC 实现——是深入理解 MySQL 事务、锁和性能优化的基础。

<!--more-->

## 一、InnoDB 架构概览

```
┌──────────────────────────────────────────────────────┐
│                      内存结构                          │
│  ┌─────────────────────┐  ┌────────────────────────┐ │
│  │  Buffer Pool        │  │  Adaptive Hash Index   │ │
│  │  (数据页 + 索引页)    │  │  (自适应哈希索引)       │ │
│  ├─────────────────────┤  ├────────────────────────┤ │
│  │  Change Buffer      │  │  Log Buffer            │ │
│  │  (变更缓冲区)         │  │  (重做日志缓冲区)       │ │
│  └─────────────────────┘  └────────────────────────┘ │
├──────────────────────────────────────────────────────┤
│                      磁盘结构                          │
│  ┌─────────────────────┐  ┌────────────────────────┐ │
│  │  表空间 (.ibd)      │  │  系统表空间             │ │
│  │  ├── 段 (Segment)   │  │  (ibdata1)             │ │
│  │  ├── 区 (Extent)    │  │  双写缓冲区             │ │
│  │  ├── 页 (Page)      │  │  (Doublewrite Buffer)  │ │
│  │  └── 行 (Row)       │  │                        │ │
│  └─────────────────────┘  └────────────────────────┘ │
│  ┌─────────────────────┐  ┌────────────────────────┐ │
│  │  Redo Log           │  │  Undo Log              │ │
│  │  (ib_logfile0/1)    │  │  (存储在 ibdata 或独立) │ │
│  └─────────────────────┘  └────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

## 二、行格式（Row Format）

### 2.1 四种行格式

```sql
-- 查看行格式
SHOW TABLE STATUS LIKE 'users'\G;
-- Row_format: Dynamic（默认）

-- MySQL 8.0 默认行格式
-- 支持：REDUNDANT, COMPACT, DYNAMIC（默认）, COMPRESSED
```

### 2.2 COMPACT 行格式

```
COMPACT 行格式：

┌──────────┬──────────────┬──────────────────┬─────────────┬──────────┐
│ 变长字段  │ NULL 标志位   │ 记录头信息        │ 真实数据     │ 隐藏列   │
│ 长度列表  │ (1-2 字节)   │ (5 字节)          │             │         │
└──────────┴──────────────┴──────────────────┴─────────────┴──────────┘
                                                             │
                                           ┌─────────────────┤
                                           │                 │
                                     ROW_ID(6B)     TRX_ID(6B) + ROLL_PTR(7B)
                                     (无主键时)    (事务ID + 回滚指针)
```

**隐藏列：**

| 隐藏列 | 大小 | 说明 |
|--------|:----:|------|
| DB_ROW_ID | 6 字节 | 行 ID（无主键时自动生成）|
| DB_TRX_ID | 6 字节 | 最后修改该行的事务 ID |
| DB_ROLL_PTR | 7 字节 | 回滚指针，指向 undo log 记录 |

### 2.3 DYNAMIC 行格式（默认）

与 COMPACT 的区别在于：**大字段（TEXT、BLOB）溢出处理**。

```
COMPACT：大字段的前 768 字节存储在行中，其余存储在溢出页
DYNAMIC：大字段完全存储在溢出页，行中只存 20 字节指针

DYNAMIC 的优点：
- 行更紧凑，一页能容纳更多行
- 减少 B+ 树的层级
- MySQL 8.0 默认使用
```

---

## 三、页结构（Page Structure）

InnoDB 数据页（索引页）是磁盘和内存交互的基本单位，默认大小为 **16KB**。

### 3.1 页结构布局

```
┌─────────────────────────────┐
│ File Header (38 字节)        │ ← 页头（上一页/下一页指针）
├─────────────────────────────┤
│ Page Header (56 字节)        │ ← 页状态信息
├─────────────────────────────┤
│ Infimum + Supremum           │ ← 最小/最大记录
├─────────────────────────────┤
│ User Records                 │ ← ★ 实际存储的数据行
│   ┌───────────────────────┐  │
│   │ 行 1                   │  │
│   │ 行 2                   │  │
│   │ ...                    │  │
│   └───────────────────────┘  │
├─────────────────────────────┤
│ Free Space                   │ ← 空闲空间
├─────────────────────────────┤
│ Page Directory               │ ← 页目录（槽，用于二分查找）
├─────────────────────────────┤
│ File Trailer (8 字节)        │ ← 校验和（检查页完整性）
└─────────────────────────────┘
```

### 3.2 页内部的数据组织

```
页目录（Slot）结构：
┌──────┬──────┬──────┬──────┬──────┐
│ Slot0│ Slot1│ Slot2│ Slot3│ Slot4│ → 每个 Slot 指向一条记录
│  →1  │  →5  │  →10 │  →15 │  →20 │     Slot 内部单向链表
└──────┴──────┴──────┴──────┴──────┘

查找过程（二分查找 + 遍历）：
1. 在 Slot 数组中二分定位到 Slot2（指向第 10 条记录）
2. 在 Slot2 范围内单向链表遍历找到目标行
```

### 3.3 数据页大小与行数估算

```sql
-- 一页 16KB = 16384 字节
-- 一行按 ~200 字节估算
-- 一页 ≈ 80 行

-- B+ 树三层：
-- 第一层：1 页
-- 第二层：约 1000 页（非叶子存主键+指针，每页约 1000 个条目）
-- 第三层：约 1000 * 1000 = 100 万页
-- 总行数：100 万 * 80 ≈ 8000 万行

-- 所以三层 B+ 树可以管理约 8000 万行数据
-- 这就是 MySQL 三层 B+ 树容纳千万级数据的原因
```

---

## 四、表空间（Tablespace）

### 4.1 表空间结构

```
表空间文件 (.ibd)
    │
    ├── 段 (Segment)
    │     ├── 索引段（B+ 树的非叶子节点）
    │     └── 数据段（B+ 树的叶子节点）
    │
    ├── 区 (Extent) — 1MB = 64 个连续页
    │     ├── 碎片区（小表共享）
    │     └── 完整区（大表独占）
    │
    └── 页 (Page) — 16KB
```

**区的两种类型：**

```sql
-- 碎片区（Fragmentation Extent）：
--   - 小表共享
--   - 同一个碎片区中的页可能属于不同的表

-- 完整区（Full Extent）：
--   - 大表独占 64 个连续页
--   - 当表占用超过 32 个碎片页后，转入完整区
```

### 4.2 独立表空间 vs 系统表空间

```sql
-- 查看配置
SHOW VARIABLES LIKE 'innodb_file_per_table';
-- ON：每个表独立 .ibd 文件（推荐）
-- OFF：所有表存储在 ibdata1 系统表空间

-- 独立表空间的优点：
-- 1. 删除表时直接删除 .ibd 文件，释放磁盘
-- 2. 便于单独备份和恢复
-- 3. 减少系统表空间的碎片
```

---

## 五、MVCC 实现原理

MVCC（Multi-Version Concurrency Control）是 InnoDB 实现高并发事务的核心机制。

### 5.1 Undo Log 版本链

```
每次对记录做修改时，InnoDB 都会生成一条 undo log 记录，
通过 DB_ROLL_PTR 指针串联成版本链。

事务对 id=1 的行执行 UPDATE：

第1次修改（TRX_ID=100）：
┌─────────┐    DB_ROLL_PTR    ┌────────────────┐
│ 最新数据  │ ───────────────▶ │ undo log 1      │
│ name=T1  │                  │ name: Tom → T1  │
│ TRX_ID=100│                  │ TRX_ID=100      │
└─────────┘                   └────────────────┘

第2次修改（TRX_ID=150）：
┌─────────┐  DB_ROLL_PTR  ┌─────────┐  DB_ROLL_PTR  ┌────────────────┐
│ 最新数据  │────────────▶ │ 版本1    │────────────▶  │ undo log 1      │
│ name=T2  │               │ name=T1  │               │ name: Tom → T1  │
│ TRX_ID=150│               │ TRX_ID=100│               │ TRX_ID=100      │
└─────────┘               └─────────┘               └────────────────┘
```

### 5.2 ReadView 一致性快照

```
ReadView 是 MVCC 实现"快照读"的核心数据结构。

ReadView 包含：
├── m_ids：活跃事务 ID 列表（事务启动时正在执行的事务）
├── min_trx_id：活跃事务列表中的最小事务 ID
├── max_trx_id：系统下个事务 ID（最大事务 ID + 1）
└── creator_trx_id：创建该 ReadView 的事务 ID

可见性判断规则：
- trx_id < min_trx_id：已提交，可见
- trx_id >= max_trx_id：将来事务，不可见
- min_trx_id <= trx_id < max_trx_id：
    ├── trx_id 在 m_ids 中：未提交，不可见
    └── trx_id 不在 m_ids 中：已提交，可见
```

**快照读 vs 当前读：**

```sql
-- 快照读（Snapshot Read）：不加锁，通过 MVCC 读取
SELECT * FROM users WHERE id = 1;  -- 不加锁

-- 当前读（Current Read）：加锁，读取最新提交的数据
SELECT * FROM users WHERE id = 1 FOR UPDATE;  -- 加锁读
SELECT * FROM users WHERE id = 1 LOCK IN SHARE MODE;
UPDATE users SET name = 'T2' WHERE id = 1;   -- UPDATE 也是当前读
DELETE FROM users WHERE id = 1;               -- DELETE 也是当前读
```

---

## 六、Buffer Pool

Buffer Pool 是 InnoDB 内存中最重要的组成部分，用于缓存数据页和索引页。

### 6.1 Buffer Pool 配置

```ini
# Buffer Pool 大小（建议设为物理内存的 60-80%）
innodb_buffer_pool_size = 4G

# MySQL 8.0 支持在线调整大小
# MySQL 5.7+ 支持多个 Buffer Pool 实例
innodb_buffer_pool_instances = 4
```

### 6.2 LRU 算法

InnoDB 的 Buffer Pool 使用改进的 LRU（Least Recently Used）算法：

```
传统的 LRU ：
┌──────┬──────┬──────┬──────┬──────┬──────┬──────┐
│ 热1  │ 热2  │ 热3  │ 中4  │ 冷5  │ 冷6  │ 冷7  │ → 淘汰冷7
└──────┴──────┴──────┴──────┴──────┴──────┴──────┘
新数据插入到最左边，淘汰最右边的数据

InnoDB 改进的 LRU（5/8 热区 + 3/8 冷区）：
┌───────────── hot ─────────────┬──────── cold ──────────┐
│ 热1 │ 热2 │ 热3 │ 热4 │ 热5  │ 冷1 │ 冷2 │ 冷3 │ ← 淘汰冷3
└───────────────────────────────┴────────────────────────┘
新数据先插入 cold 区头部，只有再次被访问才移入 hot 区

这种改进防止全表扫描冲掉热点数据：
1. 全表扫描时，数据进入 cold 区
2. 如果只是一次性扫描，数据在 cold 区被淘汰
3. 不会影响 hot 区的热点数据
```

### 6.3 预读

```
InnoDB 的预读机制：预测即将访问的数据页，提前加载到 Buffer Pool。

线性预读：顺序读取一个区的页时触发
随机预读：同一区中页被随机访问时触发（MySQL 5.5+ 默认关闭）
```

---

## 七、双写缓冲区（Doublewrite Buffer）

### 7.1 为什么需要双写？

```
InnoDB 的页大小 = 16KB
文件系统页大小 = 4KB（大多数系统）

写入 16KB 的页需要分 4 次写入（4KB × 4）
如果在写入过程中发生宕机 → 只写入了 8KB → 页损坏（partial page write）

双写缓冲区就是为了解决 partial page write 问题。
```

### 7.2 双写流程

```
1. 数据页写入 Buffer Pool 前，先写入 Doublewrite Buffer
2. Doublewrite Buffer 分两步：
   a. memcopy 到 Doublewrite Buffer（内存）
   b. fsync 到 Doublewrite Buffer（磁盘 ibdata 的连续区域）
3. Doublewrite Buffer 写入成功后，再写入数据文件

恢复时：
- 如果数据文件中的页损坏
- 从 Doublewrite Buffer 中获取完整的副本
- 覆盖损坏的数据页

代价：每次写入多了一次 fsync（约 5-10% 性能开销）
```

---

## 八、总结

### InnoDB 关键特性速查

| 特性 | 说明 |
|------|------|
| 存储结构 | 表空间 → 段 → 区(1MB) → 页(16KB) → 行 |
| 行格式 | DYNAMIC（默认）|
| 聚簇索引 | 数据即索引，索引即数据 |
| MVCC | Undo Log 版本链 + ReadView |
| Buffer Pool | 改进 LRU + 预读 |
| Doublewrite | 防页损坏（2 次写入）|
| Change Buffer | 缓存二级索引的变更操作 |
| 自适应哈希索引 | 自动为热点页建立哈希索引 |

### 核心配置

```ini
# 内存
innodb_buffer_pool_size = 4G          # Buffer Pool（最大的一块内存）
innodb_buffer_pool_instances = 4      # 实例数
innodb_log_buffer_size = 64M          # Log Buffer

# 磁盘
innodb_file_per_table = ON            # 独立表空间
innodb_data_file_path = ibdata1:1G:autoextend  # 系统表空间
innodb_flush_method = O_DIRECT        # 跳过文件系统缓存
innodb_io_capacity = 2000             # IO 容量

# 双写
innodb_doublewrite = ON               # 双写缓冲区
```

---

**上一篇：** [MySQL（二）：索引原理与优化]({{< relref "post/mysql-index" >}})

**下一篇：** [MySQL（四）：事务与锁机制]({{< relref "post/mysql-transaction-lock" >}})
