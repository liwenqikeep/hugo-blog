---
title: "MySQL（五）：日志系统"
date: 2018-07-16
draft: false
categories: ["数据库"]
tags: ["MySQL", "binlog", "redo log", "undo log", "两阶段提交", "crash-safe"]
toc: true
---

## 前言

MySQL 的日志系统是保证数据持久性和一致性的关键。三大日志（**binlog**、**redo log**、**undo log**）各司其职，共同支撑了崩溃恢复、主从复制和事务回滚等核心能力。

理解日志系统，才能真正理解 MySQL 的 crash-safe 能力。

<!--more-->

## 一、三大日志概览

| 日志 | 所属层级 | 作用 | 文件 |
|------|---------|------|------|
| **Redo Log** | InnoDB 引擎层 | 保证事务持久性（Crash Safe）| ib_logfile0/1 |
| **Undo Log** | InnoDB 引擎层 | 事务回滚 + MVCC 版本链 | ibdata / 独立表空间 |
| **Binlog** | MySQL Server 层 | 主从复制 + 数据恢复 | mysql-bin.xxxxx |

---

## 二、Redo Log——重做日志

### 2.1 为什么需要 Redo Log？

```
问题：Buffer Pool 在内存中，数据修改先改内存
如果事务提交后、脏页刷盘前宕机，内存中的数据会丢失

Redo Log 解决了这个问题：
1. 事务提交时，先把修改记录写到 Redo Log（磁盘，顺序 IO）
2. 即使脏页没有刷盘，宕机后可以通过 Redo Log 恢复
```

### 2.2 Redo Log 的写入机制

```sql
-- Redo Log 以物理日志的形式记录"对某个页做了什么修改"
-- 如：page 5, offset 100, write 'Tom'

-- Redo Log 的大小固定（循环写）
SHOW VARIABLES LIKE 'innodb_log_file_size';   -- 默认 48MB
SHOW VARIABLES LIKE 'innodb_log_files_in_group';  -- 默认 2 个
```

**Redo Log 的环形结构：**

```
┌────────────────────────────────────────────────────────┐
│  Redo Log 文件组（ib_logfile0 + ib_logfile1，循环写）   │
│                                                        │
│  ┌───── write_pos ─────→ ┌─────────────────────┐       │
│  │                        │   已写入，可以覆盖   │       │
│  │                        └─────────────────────┘       │
│  │                        ┌─────────────────────┐       │
│  │← checkpoint ────────── │   已写入，未刷盘     │       │
│  │                        │   （不能覆盖）       │       │
│  │                        └─────────────────────┘       │
│  │                        ┌─────────────────────┐       │
│  │                        │   空闲区域           │       │
│  │                        └─────────────────────┘       │
└────────────────────────────────────────────────────────┘

write pos：当前写入位置
checkpoint：当前已刷盘的位置（checkpoint 之前的可以覆盖）

write_pos 追上 checkpoint 时 → 需要等待脏页刷盘 → checkpoint 推进
```

### 2.3 Redo Log 的写入流程

```
事务执行
    │
    ├── 修改 Buffer Pool 中的页（脏页）
    │
    ├── 将修改记录写入 Redo Log Buffer（内存）
    │
    └── 事务提交时
          ├── innodb_flush_log_at_trx_commit = 1（推荐）
          │     → fsync → 写入 Redo Log 磁盘文件
          │
          ├── innodb_flush_log_at_trx_commit = 0
          │     → 每秒一次 fsync（可能丢失 1 秒数据）
          │
          └── innodb_flush_log_at_trx_commit = 2
                → 写入操作系统缓存（OS 宕机丢失，MySQL 不丢失）
```

### 2.4 Crash Safe 原理

```
宕机前：
Buffer Pool 中有脏页（已修改内存，未刷盘）
Redo Log 中已经记录修改

重启后恢复流程：
1. 从 Redo Log 中读取已提交事务的修改
2. 将这些修改重新执行到数据页中
3. 数据恢复到宕机前的状态
4. Binlog 不一致时，两阶段提交保证一致

这就是 Crash Safe：只要 Redo Log 写成功了，
数据就一定不会丢失。
```

---

## 三、Undo Log——回滚日志

### 3.1 Undo Log 的作用

```
1. 事务回滚：当事务执行 ROLLBACK 时，通过 Undo Log 恢复数据
2. MVCC 版本链：为快照读提供历史版本数据
```

### 3.2 Undo Log 的类型

```sql
-- (1) INSERT 的 Undo Log
-- 记录插入行的主键 ID，回滚时删除该行

-- (2) UPDATE 的 Undo Log
-- 记录被更新行的旧值，回滚时恢复旧值

-- (3) DELETE 的 Undo Log
-- 记录被删除行的完整数据，回滚时重新插入
```

### 3.3 Undo Log 的清理

```sql
-- undo log 在事务提交后不会立即删除
-- 因为可能有其他事务还需要通过 MVCC 读取旧版本

-- purge 线程：在确定没有事务需要该版本后清理
-- 通过以下参数控制
SHOW VARIABLES LIKE 'innodb_purge_threads';     -- 默认 4
SHOW VARIABLES LIKE 'innodb_max_purge_lag';     -- 延迟阈值
```

---

## 四、Binlog——二进制日志

### 4.1 Binlog 的作用

```
1. 主从复制：从库读取主库的 binlog 并重放
2. 数据恢复：通过 binlog 恢复到某个时间点
3. 增量备份：基于 binlog 的增量备份
```

### 4.2 Binlog 的三种格式

| 格式 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **STATEMENT** | 记录原始 SQL 语句 | 日志量小 | 不定性函数可能主从不一致 |
| **ROW（推荐）** | 记录每行变更 | 精确一致 | 日志量大 |
| **MIXED** | 自动选择 | 折中 | 复杂 |

```sql
-- 查看 binlog 格式
SHOW VARIABLES LIKE 'binlog_format';
-- ROW（MySQL 8.0 默认）

-- 推荐：ROW 格式，数据一致性最好

-- 查看 binlog 文件列表
SHOW BINARY LOGS;

-- 查看正在写入的 binlog
SHOW MASTER STATUS\G;

-- 查看 binlog 内容
SHOW BINLOG EVENTS IN 'mysql-bin.000001' LIMIT 10;
```

### 4.3 Binlog 的写入机制

```sql
-- binlog 在事务提交时写入
-- 通过 sync_binlog 参数控制刷盘策略

SHOW VARIABLES LIKE 'sync_binlog';
-- sync_binlog = 1（推荐）：每次事务提交 fsync
-- sync_binlog = 0：操作系统控制刷盘
-- sync_binlog = N：每 N 个事务 fsync 一次
```

---

## 五、两阶段提交

### 5.1 为什么需要两阶段提交？

```
Redo Log 和 Binlog 是两个独立的日志：
- Redo Log：InnoDB 引擎层，记录对页的修改
- Binlog：Server 层，记录逻辑 SQL

如果不做两阶段提交：
- 先写 Redo Log 再写 Binlog → Redo Log 写完后宕机
  → 重启后恢复数据，但 Binlog 没记录 → 主从不一致
- 先写 Binlog 再写 Redo Log → Binlog 写完后宕机
  → Binlog 传播到从库，但主库没恢复 → 主从不一致
```

### 5.2 两阶段提交流程

```
事务提交时的两阶段提交：

                   事务提交
                      │
                      ▼
                  Redo Log prepare（写入 prepare 状态）
                      │
                      ▼
                  写 Binlog
                      │
                      ▼
                  Redo Log commit（写入 commit 状态）
                      │
                      ▼
                   提交完成

如果在不同阶段宕机：
1. prepare 前宕机 → 事务未开始，不影响
2. prepare 后、binlog 前宕机 → 回滚事务（binlog 没有，从库不会执行）
3. binlog 后、commit 前宕机 → 重启时检查：
   - 发现 prepare 的 redo log + 对应的 binlog
   → 直接完成 commit（binlog 已有，必须提交以保持主从一致）
```

**崩溃恢复的判断逻辑：**

```
重启扫描 Redo Log：
├── 事务状态 = commit → 直接提交（Redo Log 恢复数据）
│
└── 事务状态 = prepare
      ├── 对应的 Binlog 完整 → 提交事务（保证主从一致）
      └── 对应的 Binlog 不完整 → 回滚事务
```

---

## 六、WAL 技术

WAL（Write-Ahead Logging）是所有日志写入的核心原则。

### 6.1 WAL 原则

```
WAL：在修改数据文件之前，先写日志

MySQL 的写入顺序：
1. 修改 Buffer Pool（内存）
2. 写入 Redo Log（磁盘，顺序 IO）
3. 写入 Binlog（磁盘，顺序 IO）
4. 事务提交
5. 后台线程将脏页刷盘（随机 IO）
```

### 6.2 为什么 WAL 快？

```
直接写数据文件：随机 IO（每个页在不同位置，每次 16KB）
写 Redo Log：   顺序 IO（连续写入，每次追加）

顺序 IO 比随机 IO 快 10-100 倍

所以 WAL 的核心思想：
1. 把随机写转换为顺序写
2. 事务提交时只写 Redo Log（顺序 IO）
3. 脏页刷盘延迟到后台慢慢做（系统空闲时）
```

**Redo Log 大小与刷盘的关系：**

```ini
# Redo Log 总大小 = innodb_log_file_size × innodb_log_files_in_group
# 默认 48MB × 2 = 96MB

# 如果 Redo Log 太小 → 频繁触发脏页刷盘 → 性能抖动
# 如果 Redo Log 太大 → 宕机后恢复时间变长

# 建议：Redo Log 总大小设为 Buffer Pool 的 25-50%
# 例如 Buffer Pool = 4G，Redo Log 设为 1G-2G
innodb_log_file_size = 512M
innodb_log_files_in_group = 2  # 总 1G
```

---

## 七、常见问题

### 7.1 Binlog 和 Redo Log 的区别

| 对比 | Binlog | Redo Log |
|------|--------|----------|
| 层级 | Server 层 | InnoDB 引擎层 |
| 内容 | 逻辑 SQL / 行变更 | 物理页变更 |
| 记录范围 | 所有引擎 | 仅 InnoDB |
| 写入方式 | 追加（可滚动）| 循环写（固定大小）|
| 用途 | 主从复制、数据恢复 | Crash Safe、事务持久性 |

### 7.2 删库后如何恢复？

```sql
-- 前提：有全量备份 + 后续的 binlog

-- 恢复步骤：
-- 1. 恢复最近的全量备份
mysql -u root -p < backup.sql

-- 2. 从 binlog 恢复备份后的增量数据
mysqlbinlog mysql-bin.000001 --start-datetime="2024-01-01 02:00:00" \
  --stop-datetime="2024-01-01 03:00:00" | mysql -u root -p

-- 注意：要跳过误操作的 DROP TABLE 语句
-- 可以通过 --stop-position 精确定位
```

---

## 八、总结

### 三大日志角色

| 日志 | 作用 | 写入时机 | 刷盘策略 |
|------|------|---------|---------|
| Redo Log | Crash Safe | 事务执行中 | `innodb_flush_log_at_trx_commit=1` |
| Binlog | 复制+恢复 | 事务提交时 | `sync_binlog=1` |
| Undo Log | 回滚+MVCC | 事务执行中 | 由 purge 线程自动管理 |

### 两阶段提交流程

```
① Redo Log prepare → ② Binlog write → ③ Redo Log commit

崩溃恢复依据：Binlog 是否完整
  ├── 完整 → 提交（从 prepare 到 commit）
  └── 不完整 → 回滚
```

### 推荐日志配置

```ini
# Redo Log
innodb_log_file_size = 512M
innodb_log_files_in_group = 2
innodb_flush_log_at_trx_commit = 1   # 每次事务提交刷盘

# Binlog
log_bin = mysql-bin
binlog_format = ROW                    # 使用 ROW 格式
sync_binlog = 1                        # 每次事务提交刷盘
expire_logs_days = 7                   # 保留 7 天
```

---

**上一篇：** [MySQL（四）：事务与锁机制]({{< relref "post/mysql-transaction-lock" >}})

**下一篇：** [MySQL（六）：主从复制与高可用]({{< relref "post/mysql-replication" >}})
