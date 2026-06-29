---
title: "MySQL（八）：配置与运维"
date: 2018-07-22
draft: false
categories: ["数据库"]
tags: ["MySQL", "配置", "运维", "my.cnf", "备份", "监控", "安全"]
toc: true
---

## 前言

前面七篇文章覆盖了 MySQL 的原理和优化。最后一篇聚焦于**落地运维**——生产环境 my.cnf 怎么配、如何备份与恢复、怎么监控、常见故障怎么处理。

好的配置和运维习惯，比临时救火更重要。

<!--more-->

## 一、生产环境 my.cnf 推荐配置

### 1.1 完整配置模板

```ini
[mysqld]
# ─── 基础设置 ───
user = mysql                          # 运行用户
port = 3306                           # 端口
basedir = /usr/local/mysql            # 安装目录
datadir = /data/mysql/data            # 数据目录
socket = /tmp/mysql.sock              # Socket 文件
pid_file = /var/run/mysql/mysql.pid   # PID 文件
character_set_server = utf8mb4        # 字符集
collation_server = utf8mb4_unicode_ci # 排序规则
default_storage_engine = InnoDB       # 默认引擎

# ─── 连接设置 ───
max_connections = 500                 # 最大连接数
max_connect_errors = 1000             # 最大错误连接数
wait_timeout = 600                    # 非交互超时(秒)
interactive_timeout = 1800            # 交互超时(秒)

# ─── InnoDB 引擎 ───
innodb_buffer_pool_size = 4G          # Buffer Pool(物理内存 60-80%)
innodb_buffer_pool_instances = 4      # Buffer Pool 实例数
innodb_log_file_size = 512M           # Redo Log 文件大小
innodb_log_files_in_group = 2         # Redo Log 文件数
innodb_log_buffer_size = 64M          # Log Buffer
innodb_flush_log_at_trx_commit = 1    # 每次提交刷 Redo Log
innodb_flush_method = O_DIRECT        # 跳过 OS 缓存
innodb_file_per_table = ON            # 独立表空间
innodb_io_capacity = 2000             # IO 吞吐能力
innodb_read_io_threads = 4            # 读 IO 线程数
innodb_write_io_threads = 4           # 写 IO 线程数
innodb_lock_wait_timeout = 50         # 锁等待超时(秒)
innodb_open_files = 65535             # 最大打开文件数

# ─── Binlog 设置 ───
server_id = 1                         # 服务器 ID（主从必配）
log_bin = mysql-bin                   # 开启 binlog
binlog_format = ROW                   # binlog 格式
sync_binlog = 1                       # 每次提交刷 binlog
binlog_cache_size = 4M                # binlog 缓存
max_binlog_cache_size = 2G            # binlog 最大缓存
max_binlog_size = 100M                # 单个 binlog 大小
expire_logs_days = 7                  # binlog 保留天数
binlog_do_db = your_db                # 要记录的库（可选）

# ─── 慢查询日志 ───
slow_query_log = ON                   # 开启慢查询
slow_query_log_file = /data/logs/mysql-slow.log
long_query_time = 2                   # 超过 2 秒记录

# ─── 临时表和文件 ───
tmp_table_size = 64M                  # 临时表大小
max_heap_table_size = 64M             # MEMORY 表最大大小
sort_buffer_size = 2M                 # 排序缓冲区（会话级）
join_buffer_size = 2M                 # JOIN 缓冲区（会话级）
tmpdir = /data/mysql/tmp              # 临时文件目录

# ─── 安全 ───
skip_name_resolve                    # 跳过 DNS 解析（加速连接）
local_infile = OFF                    # 禁用 LOAD DATA LOCAL
```

### 1.2 配置参数分类

| 类别 | 参数 | 建议 |
|------|------|------|
| **不可错** | innodb_buffer_pool_size | 物理内存的 60-80% |
| **不可错** | innodb_flush_log_at_trx_commit = 1 | 防止丢数据 |
| **不可错** | sync_binlog = 1 | 防止 binlog 丢失 |
| **重要** | max_connections | 根据并发设置 |
| **重要** | innodb_log_file_size | 足够大，避免频繁刷脏页 |
| **重要** | binlog_format = ROW | 主从一致 |
| **按需** | sort_buffer_size / join_buffer_size | 别太大（会话级，×max_connections=内存爆炸）|

---

## 二、备份策略

### 2.1 逻辑备份（mysqldump）

```bash
# 全量备份
mysqldump -u root -p --single-transaction --routines --triggers \
  --master-data=2 --all-databases > full_backup_$(date +%Y%m%d).sql

# 单库备份
mysqldump -u root -p --single-transaction --master-data=2 mydb > mydb_backup.sql

# 单表备份
mysqldump -u root -p --single-transaction mydb users > users_backup.sql

# --single-transaction：对 InnoDB 表进行一致性快照（不锁表）
# --master-data=2：记录 binlog 位置（恢复时使用）
# --routines：导出存储过程和函数
# --triggers：导出触发器
```

### 2.2 物理备份（XtraBackup）

```bash
# Percona XtraBackup — 物理备份（快，支持增量）

# 全量备份
xtrabackup --backup --target-dir=/backup/full/ --user=root --password=***

# 准备恢复（应用 redo log，使数据一致）
xtrabackup --prepare --target-dir=/backup/full/

# 恢复
xtrabackup --copy-back --target-dir=/backup/full/
```

**mysqldump vs XtraBackup：**

| 对比 | mysqldump | XtraBackup |
|------|-----------|------------|
| 类型 | 逻辑备份 | 物理备份 |
| 速度 | 慢（逐条导出 SQL）| 快（直接复制文件）|
| 文件大小 | 大（带 INSERT 语句）| 小（二进制数据）|
| 恢复速度 | 慢（逐条执行）| 快（直接复制文件）|
| 适用 | 小库（< 10G）| 大库（> 10G）|

### 2.3 恢复流程

```bash
# 从全量备份 + binlog 恢复到指定时间点

# 1. 恢复全量备份
mysql -u root -p < full_backup_20240701.sql

# 2. 查看备份时的 binlog 位置
grep 'CHANGE MASTER TO' full_backup_20240701.sql
# 得到：MASTER_LOG_FILE='mysql-bin.000010', MASTER_LOG_POS=123456;

# 3. 从 binlog 恢复增量数据（到误操作之前的时间点）
mysqlbinlog --start-position=123456 --stop-datetime="2024-07-01 14:00:00" \
  mysql-bin.000010 | mysql -u root -p
```

---

## 三、监控体系

### 3.1 关键监控指标

| 分类 | 指标 | 正常值 | 告警阈值 |
|------|------|--------|---------|
| **连接** | Threads_connected | < max_connections × 80% | > 80% |
| **连接** | Threads_running | < 20 | > 50 |
| **QPS** | Questions/sec | 按业务基线 | > 基线 × 200% |
| **慢查询** | Slow_queries | < 10/min | > 100/min |
| **Buffer Pool** | Innodb_buffer_pool_reads | 0 | > 100/sec |
| **复制** | Seconds_Behind_Master | 0 | > 30 秒 |
| **锁** | Innodb_row_lock_current_waits | 0 | > 10 |
| **磁盘** | 磁盘使用率 | < 80% | > 85% |

### 3.2 常用监控命令

```sql
-- 查看 QPS/TPS
SHOW GLOBAL STATUS LIKE 'Questions';
SHOW GLOBAL STATUS LIKE 'Com_commit';
SHOW GLOBAL STATUS LIKE 'Com_rollback';

-- 查看连接情况
SHOW STATUS LIKE 'Threads_connected';
SHOW STATUS LIKE 'Threads_running';
SHOW PROCESSLIST;

-- InnoDB 行锁
SHOW STATUS LIKE 'Innodb_row_lock_%';

-- Buffer Pool 命中率
SHOW STATUS LIKE 'Innodb_buffer_pool_read%';

-- 主从延迟
SHOW SLAVE STATUS\G;
```

### 3.3 监控工具

| 工具 | 特点 | 推荐 |
|------|------|:----:|
| **Prometheus + mysqld_exporter** | 开源，图表丰富 | ★★★★★ |
| **Grafana** | 可视化仪表盘 | ★★★★★ |
| **Percona Monitoring and Management (PMM)**| 专业 MySQL 监控 | ★★★★☆ |
| **MySQL Enterprise Monitor** | 商业版 | ★★★☆☆ |

---

## 四、安全配置

### 4.1 账户安全

```sql
-- 清理默认账户
DELETE FROM mysql.user WHERE User='';
DELETE FROM mysql.user WHERE User='root' AND Host != 'localhost';
FLUSH PRIVILEGES;

-- 创建专用账户，最小权限原则
CREATE USER 'app'@'192.168.%' IDENTIFIED BY 'strong_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON mydb.* TO 'app'@'192.168.%';

-- 定期修改密码
ALTER USER 'app'@'192.168.%' IDENTIFIED BY 'new_password';

-- 查看用户权限
SHOW GRANTS FOR 'app'@'192.168.%';
```

### 4.2 网络安全

```ini
# my.cnf 安全配置
[mysqld]
skip_networking = OFF                # 是否禁用 TCP 连接
bind_address = 127.0.0.1             # 绑定地址（只能本机访问）
skip_name_resolve                    # 禁用 DNS 解析
local_infile = OFF                   # 禁用 LOAD DATA LOCAL
```

### 4.3 审计日志

```ini
# MySQL 8.0+ 审计插件
[mysqld]
plugin-load-add = audit_log.so
audit_log_policy = ALL               # 记录所有操作
audit_log_format = JSON              # JSON 格式
audit_log_file = /data/logs/mysql-audit.log
```

---

## 五、常见故障处理

### 5.1 磁盘空间满

```bash
# 现象：数据库写入报错
# ERROR 3 (HY000): Error writing file ...

# 处理步骤
# 1. 查看磁盘使用
df -h

# 2. 清理 binlog（如果不需要）
PURGE BINARY LOGS BEFORE NOW() - INTERVAL 1 DAY;

# 3. 清理慢查询日志
echo '' > /data/logs/mysql-slow.log

# 4. 收缩 ibdata（如果有大量 undo）
# 需要重建实例（停机操作）

# 5. 最紧急：扩容磁盘
```

### 5.2 CPU 100%

```sql
-- 现象：数据库响应慢，CPU 100%
-- 排查步骤：

-- 1. 查看当前正在执行的查询
SHOW FULL PROCESSLIST;

-- 2. 找到大量消耗 CPU 的查询
-- 通常是：没有索引的全表扫描、复杂的排序/分组

-- 3. 查看锁等待
SELECT * FROM information_schema.INNODB_TRX\G;

-- 4. 临时处理：KILL 阻塞的会话
KILL 12345;  -- 线程 ID

-- 5. 根本解决：优化 SQL、加索引
```

### 5.3 连接数打满

```sql
-- 现象：ERROR 1040: Too many connections

-- 紧急处理
-- 1. 用超级权限预留连接进入
mysql -u root -p -h 127.0.0.1 --port=3306

-- 2. 临时增大连接数
SET GLOBAL max_connections = 500;

-- 3. 查看哪些连接在 Sleep
SELECT * FROM PROCESSLIST WHERE COMMAND = 'Sleep';

-- 4. 清理空闲连接
-- 减小 wait_timeout
SET GLOBAL wait_timeout = 300;

-- 5. 根本解决
-- 应用层检查连接池配置是否泄漏
```

### 5.4 主从复制中断

```sql
-- 现象：Slave_IO_Running: No 或 Slave_SQL_Running: No

-- 检查
SHOW SLAVE STATUS\G;
-- Last_IO_Error 或 Last_SQL_Error 显示错误原因

-- 常见原因：
-- 1. 网络问题 → 检查主从网络连通
-- 2. binlog 被删除 → CHANGE MASTER TO 重新定位
-- 3. 主键冲突 → 跳过错误事务
-- 4. 表结构不一致 → 在从库重建表

-- 跳过错误（不推荐长期使用）
SET GLOBAL sql_slave_skip_counter = 1;
START SLAVE;
```

---

## 六、日常运维命令速查

### 6.1 常用 SQL

```sql
-- 查看数据库大小
SELECT table_schema, ROUND(SUM(data_length + index_length) / 1024 / 1024, 2) AS 'size(MB)'
FROM information_schema.tables
GROUP BY table_schema;

-- 查看最大表
SELECT table_schema, table_name, ROUND((data_length + index_length) / 1024 / 1024, 2) AS 'size(MB)'
FROM information_schema.tables
ORDER BY size DESC LIMIT 10;

-- 查看 InnoDB 状态
SHOW ENGINE INNODB STATUS\G;

-- 查看正在执行的事务
SELECT * FROM performance_schema.data_locks\G;

-- 查看表碎片
SELECT table_schema, table_name, ROUND(data_free / 1024 / 1024, 2) AS 'free(MB)'
FROM information_schema.tables
WHERE data_free > 1024 * 1024 * 100  -- 碎片 > 100MB
ORDER BY free DESC;

-- 整理碎片
OPTIMIZE TABLE users;
```

### 6.2 常用命令

```bash
# 启动/停止
systemctl start mysqld
systemctl stop mysqld
systemctl restart mysqld

# 登录
mysql -u root -p -h 127.0.0.1

# 导入数据
mysql -u root -p mydb < backup.sql

# 导出数据
mysqldump -u root -p --single-transaction mydb > backup.sql

# 查看 binlog 内容
mysqlbinlog mysql-bin.000001 | less

# 查看错误日志
tail -f /var/log/mysqld.log
```

---

## 七、总结

### 生产环境配置检查清单

| 检查项 | 推荐值 | 原因 |
|--------|--------|------|
| Buffer Pool | 内存 60-80% | 最重要的内存配置 |
| innodb_flush_log_at_trx_commit | 1 | 保障 Crash Safe |
| sync_binlog | 1 | 保障主从一致 |
| binlog_format | ROW | 主从复制精确 |
| max_connections | 500 | 防连接爆满 |
| slow_query_log | ON | 记录慢查询 |
| 备份策略 | 每日全量 + 实时 binlog | 可恢复到任意时间点 |

### 运维建议

1. **备份重于一切** — 每天全量备份，binlog 保留至少 7 天
2. **监控先行** — 部署 Prometheus + Grafana，关注 Buffer Pool 命中率、慢查询、主从延迟
3. **变更评审** — DDL 操作评估影响，避免高峰期执行
4. **容量规划** — 磁盘使用率超过 80% 时规划扩容
5. **定期巡检** — 检查表碎片、索引使用情况、慢查询优化

---

**上一篇：** [MySQL（七）：性能优化与架构]({{< relref "post/mysql-performance" >}})

**系列索引：**
- [MySQL（一）：SQL 基础与语法]({{< relref "post/mysql-sql-basics" >}})
- [MySQL（二）：索引原理与优化]({{< relref "post/mysql-index" >}})
- [MySQL（三）：InnoDB 存储引擎]({{< relref "post/mysql-innodb" >}})
- [MySQL（四）：事务与锁机制]({{< relref "post/mysql-transaction-lock" >}})
- [MySQL（五）：日志系统]({{< relref "post/mysql-log" >}})
- [MySQL（六）：主从复制与高可用]({{< relref "post/mysql-replication" >}})
- [MySQL（七）：性能优化与架构]({{< relref "post/mysql-performance" >}})
- [MySQL（八）：配置与运维]({{< relref "post/mysql-configuration" >}})
