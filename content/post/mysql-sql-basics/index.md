---
title: "MySQL（一）：SQL 基础与语法"
date: 2018-07-08
draft: false
categories: ["数据库"]
tags: ["MySQL", "SQL", "SELECT", "JOIN", "子查询", "窗口函数"]
toc: true
---

## 前言

SQL（Structured Query Language）是操作关系型数据库的标准语言。虽然大部分开发者每天都在写 SQL，但很多人在 JOIN、子查询、窗口函数等方面掌握得并不扎实。

本文从 MySQL 的实际执行行为出发，覆盖了日常开发中最常用的 SQL 语法与技巧，为后续的索引、事务、优化等内容打好基础。

<!--more-->

## 一、SELECT 查询基础

### 1.1 执行顺序

SQL 的书写顺序与执行顺序不同，理解执行顺序是读懂复杂查询的关键：

```
书写顺序：
SELECT  DISTINCT  FROM  JOIN  ON  WHERE  GROUP BY  HAVING  ORDER BY  LIMIT

执行顺序：
FROM → ON → JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT
```

**每步的作用：**

| 步骤 | 说明 |
|------|------|
| FROM | 确定数据源 |
| ON | 过滤 JOIN 条件 |
| JOIN | 关联其他表 |
| WHERE | 行级过滤 |
| GROUP BY | 分组 |
| HAVING | 分组后过滤 |
| SELECT | 选择输出列 |
| DISTINCT | 去重 |
| ORDER BY | 排序 |
| LIMIT | 限制行数 |

### 1.2 WHERE 条件

```sql
-- 比较运算符
WHERE age > 18
WHERE name != 'Tom'
WHERE salary BETWEEN 5000 AND 10000

-- 集合判断
WHERE city IN ('北京', '上海', '广州')
WHERE city NOT IN ('深圳', '杭州')

-- 模糊匹配
WHERE name LIKE '张%'   -- 以张开头的名字
WHERE name LIKE '%三'   -- 以三结尾的名字
WHERE name LIKE '%明%'  -- 包含明的名字
WHERE email LIKE '%@gmail.com'

-- NULL 判断（注意：不能使用 = NULL 或 != NULL）
WHERE email IS NULL
WHERE email IS NOT NULL

-- 组合条件
WHERE status = 1 AND age > 18
WHERE status = 1 OR level = 'VIP'
WHERE (status = 1 AND age > 18) OR level = 'VIP'
```

---

## 二、JOIN 关联查询

### 2.1 JOIN 类型

假设两张表：

```
users 表：                    orders 表：
id | name   | dept_id        id | user_id | amount | status
1  | Tom    | 1              1  | 1       | 100    | 1
2  | Jerry  | 1              2  | 2       | 200    | 1
3  | Alice  | 2              4  | 4       | 300    | 2
4  | Bob    | NULL
```

```sql
-- (1) INNER JOIN：两张表都匹配的行（交集）
SELECT u.name, o.amount
FROM users u
INNER JOIN orders o ON u.id = o.user_id;
-- 结果：Tom/100, Jerry/200, Bob 没有订单所以不出现

-- (2) LEFT JOIN：左表全部保留，右表无匹配则为 NULL
SELECT u.name, o.amount
FROM users u
LEFT JOIN orders o ON u.id = o.user_id;
-- 结果：Tom/100, Jerry/200, Alice/NULL, Bob/NULL

-- (3) RIGHT JOIN：右表全部保留，左表无匹配则为 NULL
SELECT u.name, o.amount
FROM users u
RIGHT JOIN orders o ON u.id = o.user_id;
-- 结果：Tom/100, Jerry/200, NULL/300（用户4不存在）

-- (4) CROSS JOIN：笛卡尔积
SELECT u.name, o.amount
FROM users u
CROSS JOIN orders o;
-- 结果：4个用户 × 3个订单 = 12行
```

### 2.2 JOIN 的执行顺序

```sql
-- 多个 JOIN 时，MySQL 按照从左到右的顺序依次关联
-- 但优化器可能会调整顺序
SELECT *
FROM A
JOIN B ON A.id = B.a_id
JOIN C ON B.id = C.b_id;

-- 可以理解为：
-- 1. A × B → ON A.id = B.a_id → 中间结果 T1
-- 2. T1 × C → ON B.id = C.b_id → 最终结果
```

### 2.3 JOIN 与子查询的选择

```sql
-- ❌ 子查询（某些情况下性能差）
SELECT * FROM users
WHERE id IN (SELECT user_id FROM orders WHERE amount > 100);

-- ✅ JOIN 改写（通常更快）
SELECT DISTINCT u.*
FROM users u
JOIN orders o ON u.id = o.user_id
WHERE o.amount > 100;
```

> 子查询不一定比 JOIN 慢，MySQL 优化器有时会将子查询优化为 JOIN。建议用 EXPLAIN 验证后决定。

---

## 三、聚合函数与 GROUP BY

### 3.1 常用聚合函数

```sql
SELECT
    COUNT(*),          -- 总行数（包括 NULL）
    COUNT(1),          -- 总行数（同 COUNT(*)）
    COUNT(column),     -- 非空行数
    COUNT(DISTINCT column), -- 去重后的非空行数
    SUM(amount),       -- 求和
    AVG(amount),       -- 平均值
    MAX(amount),       -- 最大值
    MIN(amount),       -- 最小值
    GROUP_CONCAT(name) -- 分组字符串拼接（默认逗号分隔）
FROM orders;
```

### 3.2 GROUP BY + HAVING

```sql
-- 统计每个用户的订单数
SELECT user_id, COUNT(*) AS order_count, SUM(amount) AS total_amount
FROM orders
GROUP BY user_id;

-- HAVING：分组后过滤（与 WHERE 的区别）
SELECT user_id, COUNT(*) AS order_count
FROM orders
WHERE status = 1             -- ★ WHERE：分组前过滤行
GROUP BY user_id
HAVING COUNT(*) > 3           -- ★ HAVING：分组后过滤组
ORDER BY order_count DESC;
```

---

## 四、窗口函数（MySQL 8.0+）

窗口函数是 MySQL 8.0 引入的强大特性，可以在不改变行数的情况下进行聚合计算。

### 4.1 基础语法

```sql
-- 窗口函数语法
函数() OVER (
    PARTITION BY 分组列
    ORDER BY 排序列
    [ROWS/RANGE BETWEEN ... AND ...]
)

-- 常用窗口函数
ROW_NUMBER()     -- 行号（不重复）
RANK()           -- 排名（并列跳号：1,1,3）
DENSE_RANK()     -- 排名（并列不跳号：1,1,2）
NTILE(N)         -- 分桶
LAG() / LEAD()   -- 前后行取值
FIRST_VALUE() / LAST_VALUE()  -- 首尾值
SUM() / AVG() / COUNT()  -- 聚合函数也可用作窗口函数
```

### 4.2 常见应用场景

```sql
-- (1) 分组排序：每个部门工资最高的员工
SELECT name, department, salary,
       RANK() OVER (PARTITION BY department ORDER BY salary DESC) AS rank_in_dept
FROM employees
HAVING rank_in_dept = 1;

-- (2) 移动平均：最近3个月的销售额移动平均值
SELECT month, amount,
       AVG(amount) OVER (ORDER BY month ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS moving_avg
FROM sales;

-- (3) 同比环比：当月销售额与上月的差值
SELECT month, amount,
       LAG(amount, 1) OVER (ORDER BY month) AS last_month,
       amount - LAG(amount, 1) OVER (ORDER BY month) AS diff
FROM sales;

-- (4) 获取每个分组内的前 N 行
WITH ranked AS (
    SELECT *, ROW_NUMBER() OVER (PARTITION BY category ORDER BY sales DESC) AS rn
    FROM products
)
SELECT * FROM ranked WHERE rn <= 3;

-- (5) 累计求和
SELECT day, amount,
       SUM(amount) OVER (ORDER BY day) AS cumulative_sum
FROM daily_sales;
```

---

## 五、子查询

### 5.1 标量子查询

```sql
-- 返回单个值的子查询
SELECT name,
       (SELECT MAX(amount) FROM orders WHERE user_id = users.id) AS max_order
FROM users;
```

### 5.2 行子查询

```sql
-- 返回一行的子查询
SELECT * FROM users
WHERE (name, age) = (SELECT name, age FROM blacklist WHERE id = 1);
```

### 5.3 表子查询

```sql
-- 返回多行多列的子查询（用作临时表）
SELECT dept_name, avg_salary
FROM (
    SELECT d.name AS dept_name, AVG(e.salary) AS avg_salary
    FROM departments d
    JOIN employees e ON d.id = e.dept_id
    GROUP BY d.id
) AS dept_stats
WHERE avg_salary > 10000;

-- EXISTS 子查询（常用于判断存在性）
SELECT * FROM users u
WHERE EXISTS (
    SELECT 1 FROM orders o
    WHERE o.user_id = u.id AND o.amount > 1000
);
```

---

## 六、INSERT / UPDATE / DELETE

### 6.1 批量操作

```sql
-- 批量插入
INSERT INTO users (name, email) VALUES
('Tom', 'tom@test.com'),
('Jerry', 'jerry@test.com'),
('Alice', 'alice@test.com');

-- INSERT ... SELECT
INSERT INTO archive_users (id, name, email)
SELECT id, name, email FROM users WHERE deleted = 1;

-- 更新关联表
UPDATE users u
JOIN orders o ON u.id = o.user_id
SET u.total_amount = u.total_amount + o.amount
WHERE o.status = 1;

-- 删除关联表
DELETE u FROM users u
LEFT JOIN orders o ON u.id = o.user_id
WHERE o.id IS NULL;  -- 删除没有订单的用户
```

### 6.2 INSERT ON DUPLICATE KEY UPDATE

```sql
-- 存在则更新，不存在则插入
INSERT INTO user_stats (user_id, login_count, last_login)
VALUES (1, 1, NOW())
ON DUPLICATE KEY UPDATE
    login_count = login_count + 1,
    last_login = NOW();
```

### 6.3 REPLACE INTO

```sql
-- 替换（如有主键/唯一键冲突，先删后插）
REPLACE INTO users (id, name, email) VALUES (1, 'Tom', 'new_email@test.com');
-- 等同于：DELETE WHERE id=1; INSERT INTO users VALUES(1, 'Tom', ...);
```

---

## 七、DML 与 DDL 基础

### 7.1 常用 DDL

```sql
-- 创建表
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    status TINYINT DEFAULT 1 COMMENT '状态: 1=正常, 0=禁用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_name_status (name, status)  -- 复合索引
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 修改表
ALTER TABLE users ADD COLUMN phone VARCHAR(20) AFTER email;
ALTER TABLE users MODIFY COLUMN name VARCHAR(100);
ALTER TABLE users DROP COLUMN phone;
ALTER TABLE users ADD INDEX idx_phone (phone);

-- 临时表
CREATE TEMPORARY TABLE temp_users SELECT * FROM users WHERE status = 1;
```

### 7.2 数据类型选择建议

| 类型 | 存储 | 建议 |
|------|------|------|
| TINYINT | 1 字节 | 状态标识、枚举 |
| INT | 4 字节 | 常规整数 |
| BIGINT | 8 字节 | 主键、订单号 |
| VARCHAR(N) | N+1/2 字节 | 变长字符串 |
| CHAR(N) | N 字节 | 定长字符串（如身份证号）|
| DECIMAL(M,N) | 变长 | 金额 |
| DATETIME | 8 字节 | 日期时间 |
| TIMESTAMP | 4 字节 | 记录时间戳 |
| TEXT | 变长 | 大文本（尽量不用）|

---

## 八、常见 SQL 写法误区

```sql
-- ❌ 误区1：SELECT * 不指定列名
SELECT * FROM users;  -- 不推荐：无法利用覆盖索引，增加 IO

-- ✅ 只 select 需要的列
SELECT id, name, email FROM users;

-- ❌ 误区2：WHERE 中对索引列使用函数
WHERE DATE(created_at) = '2024-01-01';  -- 索引失效

-- ✅ 改用范围查询
WHERE created_at >= '2024-01-01' AND created_at < '2024-01-02';

-- ❌ 误区3：隐式类型转换
WHERE phone = 13800138000;  -- phone 是 VARCHAR

-- ✅ 保持类型一致
WHERE phone = '13800138000';

-- ❌ 误区4：分页大偏移量
SELECT * FROM users ORDER BY id LIMIT 100000, 20;  -- 扫描 100020 行

-- ✅ 子查询优化
SELECT * FROM users
WHERE id > (SELECT id FROM users ORDER BY id LIMIT 100000, 1)
ORDER BY id LIMIT 20;
```

---

## 九、总结

### SQL 执行顺序速记

```
FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT
```

### 核心语法速查

| 功能 | 关键字 |
|------|--------|
| 关联查询 | INNER/LEFT/RIGHT/CROSS JOIN |
| 聚合 | GROUP BY + 聚合函数 |
| 分组过滤 | HAVING |
| 窗口函数 | ROW_NUMBER / RANK / LAG / LEAD OVER |
| 子查询 | 标量 / EXISTS / IN / 临时表 |
| 存在更新 | INSERT ... ON DUPLICATE KEY UPDATE |

---

**下一篇：** [MySQL（二）：索引原理与优化]({{< relref "post/mysql-index" >}})
