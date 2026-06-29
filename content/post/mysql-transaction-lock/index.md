---
title: "MySQL（四）：事务与锁机制"
date: 2018-07-14
draft: false
categories: ["数据库"]
tags: ["MySQL", "事务", "ACID", "MVCC", "行锁", "间隙锁", "临键锁", "死锁"]
toc: true
---

## 前言

事务和锁是数据库两大核心机制。事务保证数据的一致性，锁控制并发访问的隔离性。InnoDB 通过多版本并发控制（MVCC）和行级锁的结合，在保证数据一致性的同时提供了高并发能力。

本文从事务的 ACID 特性出发，逐步深入到 MVCC、锁类型、加锁规则和死锁排查。

<!--more-->

## 一、事务的 ACID 特性

| 特性 | 含义 | InnoDB 实现 |
|------|------|-------------|
| **A**tomicity（原子性）| 事务要么全做，要么全不做 | Undo Log（回滚）|
| **C**onsistency（一致性）| 事务前后数据保持一致状态 | 约束 + 其他三个特性保证 |
| **I**solation（隔离性）| 并发事务互不干扰 | MVCC + 锁 |
| **D**urability（持久性）| 提交后数据永久保存 | Redo Log + Doublewrite |

---

## 二、隔离级别与并发问题

### 2.1 三种并发问题

```sql
-- (1) 脏读：读到另一个事务未提交的数据
事务A：UPDATE users SET name='T2' WHERE id=1;  -- 未提交
事务B：SELECT name FROM users WHERE id=1;       -- 读到 'T2'（脏数据）
事务A：ROLLBACK;  -- 实际回滚了

-- (2) 不可重复读：同一事务两次读同一行，结果不同
事务A：SELECT name FROM users WHERE id=1;  -- 'T1'
事务B：UPDATE users SET name='T2' WHERE id=1;  COMMIT;
事务A：SELECT name FROM users WHERE id=1;  -- 'T2'（和上次不一样）

-- (3) 幻读：同一事务两次范围查询，结果集不同
事务A：SELECT * FROM users WHERE age > 20;  -- 3 行
事务B：INSERT INTO users(age) VALUES(25);  COMMIT;
事务A：SELECT * FROM users WHERE age > 20;  -- 4 行（多了幻行）
```

### 2.2 四种隔离级别

| 隔离级别 | 脏读 | 不可重复读 | 幻读 | 实现方式 |
|---------|:----:|:---------:|:----:|---------|
| READ UNCOMMITTED | ✅ 可能 | ✅ 可能 | ✅ 可能 | 读不加锁 |
| READ COMMITTED | ❌ | ✅ 可能 | ✅ 可能 | 语句级快照读 |
| REPEATABLE READ（MySQL 默认）| ❌ | ❌ | ❌ | 事务级快照读 + 间隙锁 |
| SERIALIZABLE | ❌ | ❌ | ❌ | 所有读都加锁 |

```sql
-- 查看和设置隔离级别
SELECT @@transaction_isolation;   -- MySQL 8.0
-- REPEATABLE-READ

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET GLOBAL TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

---

## 三、InnoDB 锁类型

### 3.1 锁的分类

```
按粒度分：
├── 行级锁（Row Lock）— InnoDB 特有的细粒度锁
├── 间隙锁（Gap Lock）— 锁定范围，防止幻读
├── 临键锁（Next-Key Lock）— 行锁 + 间隙锁的组合
└── 表级锁（Table Lock）— 意向锁、DDL 锁

按模式分：
├── 共享锁（S Lock）— 允许其他事务读，不允许写
├── 排他锁（X Lock）— 不允许其他事务读或写
├── 意向共享锁（IS）— 表级别，准备加行级 S 锁
└── 意向排他锁（IX）— 表级别，准备加行级 X 锁
```

### 3.2 行锁

```sql
-- 共享锁（S Lock）：SELECT ... LOCK IN SHARE MODE
-- 排他锁（X Lock）：SELECT ... FOR UPDATE / UPDATE / DELETE

-- 加锁方式
SELECT * FROM users WHERE id = 1 LOCK IN SHARE MODE;  -- 加 S 锁
SELECT * FROM users WHERE id = 1 FOR UPDATE;           -- 加 X 锁
UPDATE users SET name = 'T2' WHERE id = 1;             -- 加 X 锁
DELETE FROM users WHERE id = 1;                        -- 加 X 锁
```

### 3.3 意向锁

意向锁是表级锁，用于快速检测表级锁与行级锁的冲突。

```sql
-- 意向锁的加锁规则：
-- 要加行 S 锁之前，自动加表 IS 锁
-- 要加行 X 锁之前，自动加表 IX 锁

-- 表级锁兼容性矩阵：
        ┌─────┬─────┬─────┬─────┐
        │  IS │  IX │  S  │  X  │
├───────┼─────┼─────┼─────┼─────┤
│   IS  │  ✅  │  ✅  │  ✅  │  ❌ │
│   IX  │  ✅  │  ✅  │  ❌  │  ❌ │
│   S   │  ✅  │  ❌  │  ✅  │  ❌ │
│   X   │  ❌  │  ❌  │  ❌  │  ❌ │
└───────┴─────┴─────┴─────┴─────┘
```

### 3.4 间隙锁与临键锁

```sql
-- 间隙锁是 InnoDB REPEATABLE READ 级别下，解决幻读的关键

-- 数据：id(1, 2, 5, 10)
-- 间隙：(负无穷,1), (1,2), (2,5), (5,10), (10,正无穷)

-- 场景：在 REPEATABLE READ 下执行
SELECT * FROM users WHERE id BETWEEN 3 AND 8 FOR UPDATE;

-- InnoDB 会加锁哪些范围？
-- 行锁：id=5（符合条件的现有行）
-- 间隙锁：(2,5) 和 (5,10)（防止插入 id=3 或 id=7）

-- 临键锁 = 行锁 + 间隙锁
-- 默认情况下，InnoDB 的加锁单位就是临键锁（Next-Key Lock）
```

**间隙锁的副作用：**

```sql
-- 间隙锁可能导致其他事务无法在锁定范围内插入数据
-- 即使插入的数据不存在

-- 场景：
事务A：UPDATE users SET name='T2' WHERE id BETWEEN 3 AND 8;
-- 表中只有 id=1,2,5,10
-- 锁定了：(2,5) 和 (5,10) 两个间隙 + id=5 的行锁

事务B：INSERT INTO users(id, name) VALUES(3, 'New');  
-- ❌ 等待（间隙锁阻止插入 id=3）
```

---

## 四、MVCC 与锁的配合

### 4.1 快照读与当前读

```sql
-- 在 REPEATABLE READ 下：

-- 快照读（MVCC 版本链 — 不加锁）
SELECT * FROM users WHERE id = 1;
-- 读到事务开始时的快照版本

-- 当前读（加锁 — 读最新数据）
SELECT * FROM users WHERE id = 1 FOR UPDATE;
UPDATE users SET name = 'T2' WHERE id = 1;
DELETE FROM users WHERE id = 1;
-- 读到最新提交的数据，同时加锁
```

### 4.2 REPEATABLE READ 防止幻读的原理

```
1. 快照读：MVCC 保证整个事务期间读取同一快照
   → 幻读不会出现

2. 当前读：间隙锁（Gap Lock）防止其他事务插入
   → 幻读不会出现
```

---

## 五、加锁规则

### 5.1 等值查询的加锁

```sql
-- 数据：id(1, 2, 5, 10)  age(18, 20, 25, 30)

-- ★ 规则1：主键等值查询，存在 → 行锁
SELECT * FROM users WHERE id = 5 FOR UPDATE;
-- 锁：id=5 的行锁

-- ★ 规则2：主键等值查询，不存在 → 间隙锁
SELECT * FROM users WHERE id = 3 FOR UPDATE;
-- 锁：(2,5) 的间隙锁

-- ★ 规则3：普通索引等值查询，存在 → 临键锁 + 间隙锁
SELECT * FROM users WHERE age = 25 FOR UPDATE;
-- 锁：age=25 的临键锁 + (20,25) 的间隙锁
-- 因为二级索引还要加主键行锁
```

### 5.2 范围查询的加锁

```sql
-- ★ 规则4：范围查询 → 扫描到范围内的所有记录的临键锁
SELECT * FROM users WHERE id >= 5 FOR UPDATE;
-- 锁：id=5 的行锁 + (5,10) 的间隙锁 + id=10 的临键锁

-- ★ 规则5：DESC 排序时范围边界向左扩展
SELECT * FROM users WHERE id >= 5 ORDER BY id DESC FOR UPDATE;
-- 锁：(2,5) 的间隙锁 + id=5 的行锁 + (5,10) 的间隙锁 + id=10 的行锁 + ...
```

---

## 六、死锁

### 6.1 死锁示例

```sql
-- 场景：事务A 和 事务B 相互等待

事务A：
UPDATE users SET name='T2' WHERE id = 1;  -- 锁 id=1
UPDATE users SET name='T2' WHERE id = 2;  -- 等待事务B释放 id=2

事务B：
UPDATE users SET name='T2' WHERE id = 2;  -- 锁 id=2
UPDATE users SET name='T2' WHERE id = 1;  -- 等待事务A释放 id=1

-- InnoDB 会自动检测死锁，回滚其中一个事务
-- 通过 SHOW ENGINE INNODB STATUS 查看最后一条死锁信息
```

### 6.2 查看死锁信息

```sql
-- 查看最近一次死锁
SHOW ENGINE INNODB STATUS\G;

-- 重点关注：
-- *** (1) TRANSACTION:         第一个事务
-- *** (2) TRANSACTION:         第二个事务
-- *** WE ROLL BACK TRANSACTION (1)   被回滚的事务

-- 或使用 information_schema
SELECT * FROM information_schema.INNODB_TRX\G;
SELECT * FROM performance_schema.data_locks\G;  -- MySQL 8.0
SELECT * FROM performance_schema.data_lock_waits\G;  -- MySQL 8.0
```

### 6.3 避免死锁的建议

```
1. 固定访问顺序：多个事务按相同顺序访问表/行
   ✅ 事务A：按 id=1→2→3 更新
   ✅ 事务B：按 id=1→2→3 更新

2. 缩短事务长度：减少锁持有时间
3. 使用 READ COMMITTED 隔离级别（减少间隙锁）
4. 合理设计索引，避免大范围锁扫描
5. 如果事务需要 FOR UPDATE 或 UPDATE 大量行，
   考虑分成小批量执行
```

---

## 七、事务的 MVCC 实现细节

### 7.1 事务的启动与 ReadView

```sql
START TRANSACTION;
-- 或 BEGIN;
-- 在第一次执行快照读时生成 ReadView
```

**READ COMMITTED vs REPEATABLE READ 的 ReadView 生成时机：**

```
READ COMMITTED：
  每条 SELECT 都生成一个新的 ReadView
  → 可以读到其他事务提交的最新数据
  → 不可重复读

REPEATABLE READ：
  事务中第一条 SELECT 生成 ReadView
  之后一直复用这个 ReadView
  → 整个事务期间读到同一份快照
  → 可重复读
```

### 7.2 一致性非锁定读

```sql
-- 即使某个行正在被其他事务更新（加了 X 锁）
-- 快照读也不会等待，而是读取 undo log 中的旧版本

-- 这就是 "非锁定读" 的含义：
-- 读操作不等待写锁释放，直接读旧版本

-- 实现前提：undo log 中存在旧版本数据
-- 如果旧版本被 purge 线程清理，则需要等待锁释放
```

---

## 八、总结

### 事务隔离级别

| 级别 | 实现方式 | 适用场景 |
|------|---------|---------|
| READ UNCOMMITTED | 不加锁 | 几乎不用 |
| READ COMMITTED | 语句级 ReadView | 大部分业务 |
| REPEATABLE READ | 事务级 ReadView + 间隙锁 | MySQL 默认 |
| SERIALIZABLE | 所有读加锁 | 强一致性要求 |

### 锁类型速查

| 锁 | 类型 | 作用 |
|----|------|------|
| 共享锁（S）| 行锁 | `LOCK IN SHARE MODE` |
| 排他锁（X）| 行锁 | `FOR UPDATE` / UPDATE / DELETE |
| 意向共享锁（IS）| 表锁 | 准备加 S 锁时自动加 |
| 意向排他锁（IX）| 表锁 | 准备加 X 锁时自动加 |
| 间隙锁（Gap）| 范围锁 | 防止幻读（RR 级别）|
| 临键锁（Next-Key）| 组合锁 | 行锁 + 间隙锁（InnoDB 默认）|

### 死锁排查步骤

```
1. 发现死锁错误（ERROR 1213）
2. SHOW ENGINE INNODB STATUS 查看死锁详情
3. 确定两个事务的加锁顺序
4. 优化代码，统一事务的访问顺序
```

---

**上一篇：** [MySQL（三）：InnoDB 存储引擎]({{< relref "post/mysql-innodb" >}})

**下一篇：** [MySQL（五）：日志系统]({{< relref "post/mysql-log" >}})
