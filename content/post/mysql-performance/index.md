---
title: "MySQL（七）：性能优化与架构"
date: 2018-07-20
draft: false
categories: ["数据库"]
tags: ["MySQL", "性能优化", "慢查询", "分库分表", "分区表", "SQL优化", "连接池"]
toc: true
---

## 前言

性能优化是一个系统工程，涉及 SQL 语句、索引设计、表结构、系统配置、硬件资源等多个层面。

本文从实际优化流程出发，覆盖从 SQL 优化到架构层面的分层优化策略。

<!--more-->

## 一、优化流程与分层

### 1.1 优化金字塔

```
         ┌──────────────┐
         │  硬件升级      │ ← 成本高，收益有限
         ├──────────────┤
         │  架构优化      │ ← 分库分表、缓存、读写分离
         ├──────────────┤
         │  参数调优      │ ← Buffer Pool、连接数、刷盘策略
         ├──────────────┤
         │  表结构优化    │ ← 字段类型、范式、冗余设计
         ├──────────────┤
         │  SQL 与索引    │ ← 索引优化、查询重写
         └──────────────┘

优化顺序：从上到下，从低成本高收益开始
SQL 优化往往投入最小、效果最明显
```

### 1.2 优化流程

```
1. 定位问题
   ├── 业务反馈慢查询
   ├── 监控告警（慢查询日志）
   └── 定期巡检（pt-query-digest）

2. 分析原因
   ├── EXPLAIN 执行计划
   ├── SHOW PROFILE 耗时分布
   └── 查看系统负载（CPU、IO、连接数）

3. 制定方案
   ├── SQL 改写 / 加索引
   ├── 调整表结构
   ├── 调整参数
   └── 架构调整

4. 验证效果
   ├── 对比优化前后的执行计划
   └── 对比优化前后的响应时间
```

---

## 二、SQL 优化

### 2.1 查询优化原则

```sql
-- (1) 避免 SELECT *
-- 使用覆盖索引，减少回表
SELECT id, name FROM users WHERE status = 1;  -- 如果 (status, id, name) 是复合索引
-- 不需要回表

-- (2) 拆分复杂查询
-- 一条大 SQL 拆成多条小 SQL，更容易利用缓存和索引

-- (3) 使用 JOIN 代替子查询
-- 大多数情况 JOIN 比子查询高效
-- 但 MySQL 5.7+ 的优化器会优化子查询，差异减小

-- (4) 避免 ORDER BY RAND()
SELECT * FROM users ORDER BY RAND() LIMIT 10;  -- 全表排序，极慢
-- ✅ 替代方案
SELECT * FROM users WHERE id >= (SELECT FLOOR(MAX(id) * RAND()) FROM users) LIMIT 10;
```

### 2.2 分页优化

```sql
-- ❌ 大偏移量分页
SELECT * FROM users ORDER BY id LIMIT 100000, 20;
-- MySQL 需要扫描 100020 行，然后丢弃前 100000 行

-- ✅ 方案1：子查询优化
SELECT * FROM users
WHERE id > (SELECT id FROM users ORDER BY id LIMIT 100000, 1)
ORDER BY id LIMIT 20;

-- ✅ 方案2：应用层记录上次最后 ID
SELECT * FROM users WHERE id > 100000 ORDER BY id LIMIT 20;
-- 前端传 last_id 参数

-- ✅ 方案3：覆盖索引排序
SELECT id, name FROM users ORDER BY id LIMIT 100000, 20;
-- 如果 id 是主键，只需要扫描 100020 个主键值
-- 避免回表排序
```

### 2.3 COUNT 优化

```sql
-- COUNT(*) vs COUNT(1) vs COUNT(column)
-- MySQL 对 COUNT(*) 有专门优化，性能最好

-- 大表 COUNT 优化
-- 方案1：使用近似值（系统表）
SHOW TABLE STATUS LIKE 'orders'\G;
-- Rows 列是估算值，不精确但快

-- 方案2：维护计数表
-- 通过事务单独更新计数

-- 方案3：走二级索引
SELECT COUNT(*) FROM orders;
-- InnoDB 会选择最小的二级索引扫描（比主键小）
```

### 2.4 UNION 优化

```sql
-- UNION vs UNION ALL
-- UNION 会去重（多一步排序/临时表）
-- 不需要去重的场景用 UNION ALL

SELECT * FROM users WHERE status = 1
UNION ALL
SELECT * FROM users WHERE level = 'VIP';
-- 如果两个查询有交集，用 UNION 去重
```

---

## 三、表结构优化

### 3.1 字段类型优化

```sql
-- (1) 够用原则：选择最小的合适类型
INT(4B) → 够用？不要用 BIGINT(8B)
VARCHAR → 长度合理（VARCHAR(255) 和 VARCHAR(5000) 在内存中占不同空间）

-- (2) 用整型存枚举
-- ❌ 不推荐
status VARCHAR(10) DEFAULT 'active'
-- ✅ 推荐
status TINYINT DEFAULT 1 COMMENT '1=active, 2=inactive, 3=deleted'

-- (3) 时间类型
-- DATETIME(8B)：范围大，不受时区影响
-- TIMESTAMP(4B)：2038 年问题，受时区影响
-- 推荐：业务场合选 DATETIME

-- (4) NOT NULL
-- 尽量所有列设置为 NOT NULL
-- NULL 使索引维护更复杂，占用更多空间
-- 用默认值代替 NULL
```

### 3.2 反范式设计

```sql
-- 场景：频繁查询用户信息和订单数量
-- 范式设计：需要 JOIN
SELECT u.*, COUNT(o.id) AS order_count
FROM users u
LEFT JOIN orders o ON u.id = o.user_id
WHERE u.id = 1;

-- 反范式：在 users 表冗余 order_count 字段
-- UPDATE 订单时同步更新 order_count
-- 读时直接查，不用 JOIN

-- 权衡：
-- 读多写少 → 反范式（加冗余字段）
-- 写多读少 → 范式（减少更新成本）
```

---

## 四、分区表

### 4.1 分区类型

```sql
-- 分区不是分表，是表内物理存储的拆分
-- 对应用透明，SQL 写法不变

-- RANGE 分区（最常用）
CREATE TABLE orders (
    id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    amount DECIMAL(10,2)
) PARTITION BY RANGE (YEAR(created_at)) (
    PARTITION p2022 VALUES LESS THAN (2023),
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- LIST 分区
PARTITION BY LIST (status) (
    PARTITION p_active VALUES IN (1, 2),
    PARTITION p_inactive VALUES IN (3, 4, 5)
);

-- HASH 分区（均匀分布）
PARTITION BY HASH (user_id) PARTITIONS 8;

-- KEY 分区（类似 HASH，但使用 MySQL 内部哈希函数）
PARTITION BY KEY (id) PARTITIONS 8;
```

### 4.2 分区优势与局限

```sql
-- 优势：
-- 1. 分区裁剪：只扫描相关分区（WHERE created_at BETWEEN '2024-01-01' AND '2024-06-30'）
-- 2. 批量删除：TRUNCATE PARTITION 比 DELETE 快得多
-- 3. 历史数据归档：移动分区即可

-- 局限：
-- 1. 分区数有限（建议不超过 1024）
-- 2. 唯一索引必须包含所有分区键
-- 3. 分区列不能为 NULL（或特殊处理）
-- 4. 分区表不支持全文索引（空间索引）
```

---

## 五、分库分表

### 5.1 垂直拆分

```sql
-- 按业务模块拆分到不同数据库
-- 原单库：users, orders, products, payments
-- 拆分为：
--   user_db：users, user_addresses, user_logins
--   order_db：orders, order_items
--   product_db：products, categories
--   payment_db：payments, refunds

-- 优点：业务解耦，单库压力分散
-- 缺点：跨库 JOIN 困难
```

### 5.2 水平拆分

```sql
-- 按某种规则将数据分散到多个表/库中

-- 常用分片键：
-- user_id % 16 → 16 个分片
-- order_id % 128 → 128 个分片

-- 分片算法：
-- 1. 取模：user_id % 16
-- 2. 范围：user_id 1-1000000 → shard0, 1000001-2000000 → shard1
-- 3. 哈希：hash(user_id) % 16

-- 主要问题：
-- 1. 跨分片查询（需要聚合结果）
-- 2. 分布式事务（最终一致性 vs 强一致性）
-- 3. 全局主键（雪花算法、Segment 模式）
-- 4. 动态扩容（需要迁移数据）
```

### 5.3 分库分表中间件

| 中间件 | 优势 | 局限 |
|--------|------|------|
| **ShardingSphere** | Java 生态好，功能全面 | 应用层接入 |
| **MyCat** | 支持多种分片策略 | 社区活跃度下降 |
| **Vitess** | 云原生，YouTube 使用 | 运维复杂 |

---

## 六、连接池配置

### 6.1 连接池参数

```yaml
# HikariCP（Spring Boot 2.x 默认）
spring.datasource.hikari.minimum-idle=10         # 最小空闲连接
spring.datasource.hikari.maximum-pool-size=20    # 最大连接数
spring.datasource.hikari.connection-timeout=30000  # 连接超时(ms)
spring.datasource.hikari.idle-timeout=600000     # 空闲超时(ms)
spring.datasource.hikari.max-lifetime=1800000    # 最大存活时间(ms)

# 连接数估算
# 公式：连接数 = (CPU核心数 × 2) + 有效机械磁盘数
# 生产建议：20-50（大部分场景足够）
# 注意：连接数不是越大越好，MySQL 每个连接需要线程和内存
```

### 6.2 常见问题

```sql
-- Too many connections 错误
SHOW VARIABLES LIKE 'max_connections';  -- 默认 151
-- 临时解决
SET GLOBAL max_connections = 500;

-- 查看当前连接
SHOW PROCESSLIST;
-- 重点关注：
-- Sleep 状态的连接是否过多
-- 是否有长时间运行的事务
-- 是否有锁等待

-- 查看正在执行的事务
SELECT * FROM information_schema.INNODB_TRX\G;
```

---

## 七、MySQL 缓存策略

### 7.1 应用层缓存

```java
// Redis + MySQL 常见缓存模式

// (1) Cache Aside（旁路缓存）
public User getUser(Long id) {
    // 1. 先查缓存
    User user = redis.get("user:" + id);
    if (user != null) return user;
    
    // 2. 缓存未命中 → 查数据库
    user = userDao.findById(id);
    
    // 3. 写入缓存
    redis.set("user:" + id, user);
    return user;
}

// (2) 写操作先更新数据库，再删除缓存
public void updateUser(User user) {
    userDao.update(user);           // 1. 更新数据库
    redis.del("user:" + user.getId()); // 2. 删除缓存
}
```

### 7.2 MySQL 内部缓存

```ini
# MySQL 8.0 移除了 Query Cache（不推荐使用）
# 主要靠 InnoDB Buffer Pool 做数据缓存

# Buffer Pool 大小（最重要）
innodb_buffer_pool_size = 4G

# 查看 Buffer Pool 命中率
SHOW STATUS LIKE 'Innodb_buffer_pool_read%';
-- Innodb_buffer_pool_reads：从磁盘读取次数
-- Innodb_buffer_pool_read_requests：总读取请求数
-- 命中率 ≈ (read_requests - reads) / read_requests
-- 99% 以上为理想
```

---

## 八、总结

### 优化优先级

```
1. SQL + 索引优化      → 最常见的优化手段，投入小见效快
2. 表结构优化           → 选择合适类型、反范式
3. 参数调优             → Buffer Pool、连接数配置
4. 架构优化             → 读写分离、缓存、分库分表
5. 硬件升级             → SSD、内存、CPU（最后手段）
```

### 性能监控命令

```sql
-- 慢查询
SHOW VARIABLES LIKE 'slow_query_log';
SHOW STATUS LIKE 'Slow_queries';

-- 当前连接
SHOW PROCESSLIST;
SHOW FULL PROCESSLIST;

-- InnoDB 状态
SHOW ENGINE INNODB STATUS\G;

-- 表状态
SHOW TABLE STATUS\G;

-- 索引使用情况
SELECT * FROM performance_schema.table_io_waits_summary_by_index_usage\G;
```

---

**上一篇：** [MySQL（六）：主从复制与高可用]({{< relref "post/mysql-replication" >}})

**下一篇：** [MySQL（八）：配置与运维]({{< relref "post/mysql-configuration" >}})
