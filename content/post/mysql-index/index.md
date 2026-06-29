---
title: "MySQL（二）：索引原理与优化"
date: 2018-07-10
draft: false
categories: ["数据库"]
tags: ["MySQL", "索引", "B+树", "EXPLAIN", "慢查询", "索引优化"]
toc: true
---

## 前言

索引是 MySQL 性能优化的核心手段。理解索引的底层原理（B+ 树）和正确使用方式是每个后端开发者的必备技能。

本文从索引的数据结构出发，逐步深入到如何通过 EXPLAIN 分析执行计划、如何设计高效的索引，以及常见的索引失效场景。

<!--more-->

## 一、索引的底层结构

### 1.1 B+ 树结构

MySQL InnoDB 使用 B+ 树作为索引的底层数据结构。

```
B+ 树（以主键索引为例）：

                  [50, 90]
                 /    |    \
           [10,30]  [60,70]  [100,120]
           /  |  \    /  \     /  |   \
         (1-9)       (51-59)  (91-99)

特点：
- 非叶子节点只存储索引键（不存数据）
- 叶子节点存储完整的数据行（聚簇索引）或主键值（二级索引）
- 叶子节点之间通过双向链表连接（支持范围查询）
```

**B+ 树 vs B 树：**

| 对比 | B 树 | B+ 树 |
|------|------|-------|
| 非叶子节点 | 存索引和数据 | 只存索引 |
| 叶子节点 | 独立 | 链表连接（范围查询友好）|
| 查询效率 | 不稳定（到某层命中就返回）| 稳定（必须到叶子节点）|
| 范围查询 | 需中序遍历 | 叶子链表直接扫 |

### 1.2 聚簇索引与二级索引

InnoDB 中，数据即索引，索引即数据。

```sql
-- 聚簇索引（主键索引）
-- 叶子节点存储整行数据
-- 一张表只有一个聚簇索引

-- 二级索引（辅助索引）
-- 叶子节点存储主键值
-- 通过二级索引查询需要回表

-- 回表过程：
-- ① 二级索引 B+ 树查找 → 找到主键值
-- ② 通过主键值到聚簇索引 B+ 树查找 → 找到完整数据行
```

**聚簇索引的规则：**

```
1. 有主键 → 主键作为聚簇索引
2. 没有主键 → 第一个 NOT NULL UNIQUE 列作为聚簇索引
3. 都没有 → InnoDB 自动生成 6 字节的 ROWID 作为隐藏聚簇索引
```

### 1.3 覆盖索引

```sql
-- 如果查询的所有列都在二级索引中，不需要回表
-- 这就是覆盖索引

-- 有索引：idx_name_email(name, email)
SELECT name, email FROM users WHERE name = 'Tom';
-- → 直接在 idx_name_email 的 B+ 树上找到所有数据
-- → 不需要回表

SELECT name, email, phone FROM users WHERE name = 'Tom';
-- → phone 不在索引中 → 需要回表查询
```

---

## 二、索引类型

### 2.1 索引分类

| 索引类型 | 关键字 | 特点 |
|---------|--------|------|
| 主键索引 | PRIMARY KEY | 聚簇索引，唯一非空 |
| 唯一索引 | UNIQUE INDEX | 允许 NULL（可多个 NULL）|
| 普通索引 | INDEX | 无唯一性约束 |
| 复合索引 | INDEX(a,b,c) | 多列组合，最左前缀原则 |
| 全文索引 | FULLTEXT INDEX | 文本内容的全文检索 |
| 空间索引 | SPATIAL INDEX | 地理空间数据 |

### 2.2 复合索引与最左前缀原则

```sql
-- 复合索引 idx_a_b_c(a, b, c)

-- ✅ 可以利用索引
WHERE a = 1                     -- 用了一列
WHERE a = 1 AND b = 2           -- 用了两列
WHERE a = 1 AND b = 2 AND c = 3  -- 用了三列
WHERE a = 1 ORDER BY b          -- a 用于过滤，b 用于排序
WHERE a IN (1,2) AND b = 3      -- 可以用索引

-- ⚠️ 部分利用索引
WHERE a = 1 AND c = 3           -- a 利用了索引，c 不能利用（跳过了 b）
                                 -- 但 MySQL 8.0 有索引跳跃扫描(Index Skip Scan)优化

-- ❌ 不能利用索引
WHERE b = 2                     -- 没有从最左列开始
WHERE c = 3                     -- 没有从最左列开始
WHERE a LIKE '%abc'             -- 模糊匹配以 % 开头
```

### 2.3 索引下推（Index Condition Pushdown, ICP）

MySQL 5.6+ 引入的优化，将 WHERE 条件中可以被索引过滤的部分下推到存储引擎层处理。

```sql
-- 复合索引 idx_name_age(name, age)

-- 没有 ICP 时：
-- 1. 存储引擎通过 name LIKE '张%' 找到主键
-- 2. 回表读取完整行
-- 3. Server 层再过滤 age > 18

-- 有 ICP 时：
-- 1. 存储引擎通过 name LIKE '张%' 找到索引行
-- 2. ★ 在存储引擎层直接判断 age > 18（不需要回表）
-- 3. 满足条件的再回表
```

---

## 三、EXPLAIN 执行计划

### 3.1 EXPLAIN 输出解读

```sql
EXPLAIN SELECT * FROM users u
JOIN orders o ON u.id = o.user_id
WHERE u.status = 1 AND o.amount > 100;
```

| 列名 | 示例值 | 说明 |
|------|--------|------|
| id | 1 | 查询序号 |
| select_type | SIMPLE | 查询类型 |
| table | users | 表名 |
| **type** | ref | ★ 访问类型（性能关键）|
| **possible_keys** | idx_status | 可能使用的索引 |
| **key** | idx_status | ★ 实际使用的索引 |
| key_len | 2 | 索引使用的字节数 |
| ref | const | 与索引比较的值 |
| rows | 1000 | 预估扫描行数 |
| **Extra** | Using index | ★ 额外信息 |

### 3.2 type 访问类型（性能从好到差）

| type | 说明 | 性能 |
|------|------|:----:|
| system | 系统表，只有一行 | ★★★★★ |
| const | 主键或唯一索引等值查询 | ★★★★★ |
| eq_ref | JOIN 时被驱动表主键/唯一索引等值匹配 | ★★★★☆ |
| ref | 普通索引等值查询 | ★★★★☆ |
| range | 索引范围查询（>、<、BETWEEN、IN）| ★★★☆☆ |
| index | 扫描整个索引树 | ★★☆☆☆ |
| ALL | 全表扫描 | ★☆☆☆☆ |

**理想目标：** 查询至少达到 `ref` 级别，避免 `ALL` 和 `index`。

### 3.3 Extra 信息解读

| Extra | 含义 | 好/坏 |
|-------|------|:----:|
| Using index | 使用覆盖索引 | ✅ 好 |
| Using index condition | 使用索引下推（ICP）| ✅ 好 |
| Using where | 索引后还需要 Server 层过滤 | ⚠️ 一般 |
| Using temporary | 使用了临时表（如 GROUP BY 无索引） | ❌ 坏 |
| Using filesort | 文件排序（ORDER BY 无索引）| ❌ 坏 |
| Using join buffer | JOIN 没有用索引 | ❌ 坏 |
| Using index for group-by | 松散索引扫描 | ✅ 好 |

---

## 四、常见索引失效场景

```sql
-- ★ 场景 1：对索引列使用函数
WHERE DATE(created_at) = '2024-01-01'  -- 索引失效
-- ✅ 改用范围
WHERE created_at >= '2024-01-01' AND created_at < '2024-01-02'

-- ★ 场景 2：隐式类型转换
WHERE phone = 13800138000  -- phone 是 VARCHAR，索引失效
-- ✅ 
WHERE phone = '13800138000'

-- ★ 场景 3：LIKE 以通配符开头
WHERE name LIKE '%张三'  -- 索引失效
-- ✅ 
WHERE name LIKE '张三%'  -- 索引可用

-- ★ 场景 4：复合索引违反最左前缀
-- 复合索引 (a, b, c)
WHERE b = 1   -- 索引失效
WHERE a = 1 AND c = 3  -- 只用到了 a

-- ★ 场景 5：使用 != 或 NOT IN
WHERE status != 1  -- 索引失效（除非大部分行都匹配）

-- ★ 场景 6：OR 条件中有非索引列
WHERE name = 'Tom' OR email = 'tom@test.com'  -- 如果 email 没有索引，name 的索引也失效
-- ✅ 用 UNION 代替
SELECT * FROM users WHERE name = 'Tom'
UNION
SELECT * FROM users WHERE email = 'tom@test.com'

-- ★ 场景 7：索引列参与运算
WHERE age + 1 = 20   -- 索引失效
-- ✅ 
WHERE age = 19
```

---

## 五、索引设计原则

### 5.1 索引建立的场景

```sql
-- ✅ 适合建索引的场景
-- 1. WHERE 条件中的列
-- 2. JOIN 的连接列
-- 3. ORDER BY / GROUP BY 的列
-- 4. 需要 DISTINCT 的列

-- ❌ 不适合建索引的场景
-- 1. 数据量很小的表（< 1000 行）
-- 2. 频繁更新的列（索引维护成本高）
-- 3. 区分度低的列（如性别）
-- 4. 很少出现在 WHERE 中的列
```

### 5.2 复合索引设计策略

```sql
-- 策略：将区分度高的列放在前面
-- idx(a, b) 优于 idx(b, a)

-- 统计列区分度：
SELECT COUNT(DISTINCT column) / COUNT(*) FROM table;

-- 区分度越高，索引效率越好

-- 范例：针对常见查询设计索引
-- 查询：WHERE status = 1 AND created_at > '2024-01-01' ORDER BY created_at
-- 索引：idx_status_created_at(status, created_at)
--  → status 用于过滤，created_at 用于排序（利用索引有序性，避免 filesort）
```

### 5.3 冗余索引与无用索引

```sql
-- ❌ 冗余索引
INDEX idx_a (a),
INDEX idx_a_b (a, b)      -- idx_a 是冗余的

-- ❌ 几乎不用的索引
-- 使用以下查询找出无用索引：
SELECT * FROM sys.schema_unused_indexes;

-- 删除无用索引可以减少写入开销和磁盘占用
```

---

## 六、慢查询日志

### 6.1 开启慢查询

```sql
-- 查看配置
SHOW VARIABLES LIKE 'slow_query%';
SHOW VARIABLES LIKE 'long_query_time';

-- 开启慢查询（my.cnf）
slow_query_log = ON
slow_query_log_file = /var/log/mysql/mysql-slow.log
long_query_time = 1          -- 超过 1 秒的查询
log_queries_not_using_indexes = ON  -- 也记录没有使用索引的查询
```

### 6.2 分析慢查询

```sql
-- 使用 mysqldumpslow 分析
mysqldumpslow -s t -t 10 /var/log/mysql/mysql-slow.log
-- 按耗时倒序，取前 10 条

-- 使用 pt-query-digest（Percona Toolkit）
pt-query-digest /var/log/mysql/mysql-slow.log
```

**慢查询分析步骤：**

```
1. 找到慢查询 SQL
2. EXPLAIN 分析执行计划
   → type 是不是 ALL/index？
   → Extra 有没有 Using temporary/filesort？
3. 检查是否走索引？索引设计是否合理？
4. 优化 SQL 或添加索引
5. 验证优化效果
```

---

## 七、总结

### 索引使用速查

| 目标 | 做法 |
|------|------|
| 快速定位行 | 主键/唯一索引等值查询 |
| 范围查询 | B+ 树叶子节点链表 |
| 避免回表 | 覆盖索引（查询列全在索引中）|
| 避免 filesort | ORDER BY 列在索引中 |
| 避免临时表 | GROUP BY 列在索引中 |
| JOIN 加速 | JOIN 列建索引（被驱动表）|

### 索引失效检查清单

- [ ] 对索引列使用了函数？
- [ ] 隐式类型转换？
- [ ] LIKE 以 % 开头？
- [ ] 复合索引违反最左前缀？
- [ ] 使用了 != 或 NOT IN？
- [ ] OR 条件中有非索引列？
- [ ] 索引列参与了运算？

### EXPLAIN 重点关注

| 列 | 警戒线 |
|----|--------|
| type | ALL 或 index |
| rows | 远大于预期 |
| Extra | Using temporary / Using filesort |

---

**上一篇：** [MySQL（一）：SQL 基础与语法]({{< relref "post/mysql-sql-basics" >}})

**下一篇：** [MySQL（三）：InnoDB 存储引擎]({{< relref "post/mysql-innodb" >}})
