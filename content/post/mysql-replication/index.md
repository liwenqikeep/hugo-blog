---
title: "MySQL（六）：主从复制与高可用"
date: 2018-07-18
draft: false
categories: ["数据库"]
tags: ["MySQL", "主从复制", "binlog", "半同步复制", "GTID", "读写分离", "高可用"]
toc: true
---

## 前言

主从复制是 MySQL 高可用架构的基石。通过将一台主库的数据实时同步到多个从库，可以实现**读写分离**（分担查询压力）、**数据备份**（冗余存储）和**故障切换**（高可用）。

本文从复制原理出发，逐步深入到复制模式、GTID、常见问题和架构方案。

<!--more-->

## 一、主从复制架构

### 1.1 基本架构

```
                主库（Master）
                     │
                     ├── 所有写操作在主库执行
                     │
                     ▼
               Binary Log（binlog）
                     │
        ┌────────────┼────────────┐
        │            │            │
        ▼            ▼            ▼
     从库1         从库2         从库3
    (读请求)      (读请求/备份)  (报表查询)
```

### 1.2 复制三种常见拓扑

```
一主一从                 一主多从                级联复制
┌──────┐               ┌──────┐               ┌──────┐
│ 主库  │               │ 主库  │               │ 主库  │
└──┬───┘               └──┬───┘               └──┬───┘
   │                       │                       │
   ▼                       ├────┬────┬────┐        ▼
┌──────┐               ┌──┴──┐│  │  │  │     ┌──────┐
│ 从库  │               │ 从1 ││  │  │  │     │ 从1  │
└──────┘               └─────┘│  │  │  │     └──┬───┘
                            从2  从3  从4          │
                                                 ▼
                                              ┌──────┐
                                              │ 从2  │
                                              └──────┘
```

---

## 二、复制原理

### 2.1 三线程工作模型

```
主库：                    从库：
┌─────────────┐         ┌──────────────────┐
│ Binlog Dump │         │ I/O Thread       │
│ Thread      │──────▶  │ (接收 binlog)    │
│ (发送binlog)│         │       │          │
└─────────────┘         │       ▼          │
                        │ Relay Log        │
                        │ (中继日志)        │
                        │       │          │
                        │       ▼          │
                        │ SQL Thread       │
                        │ (重放 relay log) │
                        └──────────────────┘
```

**三个线程的职责：**

| 线程 | 位置 | 职责 |
|------|------|------|
| Binlog Dump Thread | 主库 | 读取 binlog 并发送给从库 |
| I/O Thread | 从库 | 接收 binlog 并写入 relay log |
| SQL Thread | 从库 | 读取 relay log 并回放执行 |

### 2.2 复制的完整流程

```
1. 从库执行 CHANGE MASTER TO 配置主库信息
2. 从库执行 START SLAVE
3. 从库 I/O Thread 连接主库
4. 主库 Binlog Dump Thread 发送 binlog
5. 从库 I/O Thread 接收并写入 relay log
6. 从库 SQL Thread 读取 relay log 并回放
7. 从库数据与主库保持一致
```

---

## 三、复制配置

### 3.1 主库配置

```ini
# my.cnf — 主库
[mysqld]
server_id = 1                    # 唯一 ID（不能重复）
log_bin = mysql-bin              # 开启 binlog
binlog_format = ROW              # ROW 格式（推荐）
binlog_do_db = test_db           # 只复制指定库（可选）
expire_logs_days = 7             # binlog 保留天数
max_binlog_size = 100M           # 单个 binlog 大小
```

### 3.2 从库配置

```ini
# my.cnf — 从库
[mysqld]
server_id = 2                    # 唯一 ID（与主库不同）
log_bin = mysql-bin              # 从库也开启 binlog（可选，用于级联）
relay_log = mysql-relay-bin      # 中继日志
read_only = 1                    # 从库只读（防止误写）
relay_log_recovery = 1           # relay log 崩溃恢复
```

### 3.3 建立复制

```sql
-- 主库创建复制用户
CREATE USER 'repl'@'%' IDENTIFIED BY 'password';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;

-- 查看主库状态
SHOW MASTER STATUS\G;
-- 记录 File 和 Position

-- 从库配置
CHANGE MASTER TO
    MASTER_HOST='192.168.1.100',
    MASTER_PORT=3306,
    MASTER_USER='repl',
    MASTER_PASSWORD='password',
    MASTER_LOG_FILE='mysql-bin.000001',
    MASTER_LOG_POS=123456789;

-- 启动复制
START SLAVE;

-- 查看复制状态
SHOW SLAVE STATUS\G;
-- 主要关注：
-- Slave_IO_Running: Yes    ← I/O 线程是否正常
-- Slave_SQL_Running: Yes   ← SQL 线程是否正常
-- Seconds_Behind_Master: 0 ← 从库延迟（秒）
```

---

## 四、复制模式

### 4.1 异步复制（默认）

```
主库提交事务后立即返回，不等待从库确认
┌──────┐           ┌──────┐
│ 主库  │── binlog ─▶ 从库  │
│ 提交  │           │ 接收  │
│ 返回  │           │  稍后  │
└──────┘           └──────┘

优点：主库性能影响最小
缺点：主库宕机时，已提交但未同步到从库的数据会丢失
```

### 4.2 半同步复制

```
主库提交事务后，等待至少一个从库确认收到 binlog
┌──────┐           ┌──────┐
│ 主库  │── binlog ─▶ 从库  │
│ 等待  │◀── ACK ─── │ 接收  │
│ 返回  │           └──────┘
└──────┘

安装：
INSTALL PLUGIN rpl_semi_sync_master SONAME 'semisync_master.so';
INSTALL PLUGIN rpl_semi_sync_slave SONAME 'semisync_slave.so';

配置：
rpl_semi_sync_master_enabled = 1
rpl_semi_sync_slave_enabled = 1
rpl_semi_sync_master_timeout = 1000  -- 超时 1s 后降级为异步

优点：数据安全性更高
缺点：增加事务提交延迟
```

### 4.3 组复制（MySQL Group Replication, MGR）

```
MySQL 5.7+ 引入，基于 Paxos 协议的多主复制
多个节点组成一个复制组，每个节点都可读写

使用场景：要求强一致性的高可用方案
限制：所有表必须有主键
```

---

## 五、GTID 复制

### 5.1 GTID 概念

GTID（Global Transaction Identifier）是 MySQL 5.6+ 引入的全局事务标识符。

```sql
-- GTID 格式：server_uuid:transaction_id
-- 如：3e11fa47-61ca-11eb-8c9a-0050568c3e01:12345

-- 每个事务在提交时生成唯一的 GTID
-- GTID 在 binlog 中记录，全局唯一
```

### 5.2 GTID 复制的优点

```sql
-- 传统复制：需要手动指定 binlog 文件名和位置
CHANGE MASTER TO
    MASTER_LOG_FILE='mysql-bin.000001',
    MASTER_LOG_POS=123456789;

-- GTID 复制：自动追踪同步位置
CHANGE MASTER TO
    MASTER_HOST='192.168.1.100',
    MASTER_PORT=3306,
    MASTER_USER='repl',
    MASTER_PASSWORD='password',
    MASTER_AUTO_POSITION=1;  -- 自动定位

-- 优点：
-- 1. 无需手动找 binlog 位置
-- 2. 主从切换更简单
-- 3. 更容易判断主从一致性
```

### 5.3 启用 GTID

```ini
# my.cnf
gtid_mode = ON
enforce_gtid_consistency = ON
```

---

## 六、读写分离

### 6.1 应用层实现

```java
// 使用 Spring 的 AbstractRoutingDataSource
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {
    
    private final String WRITE = "write";
    private final String READ = "read";
    
    @Override
    protected Object determineCurrentLookupKey() {
        // 从 ThreadLocal 中获取当前请求类型
        String type = DataSourceContextHolder.get();
        return type != null ? type : WRITE;  // 默认写库
    }
}

// 注解标记
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSource {
    String value() default "write";
}

// 通过 AOP 切换数据源
@Aspect
@Component
public class DataSourceAspect {
    @Before("@annotation(ds)")
    public void switchDataSource(JoinPoint point, DataSource ds) {
        DataSourceContextHolder.set(ds.value());
    }
}
```

### 6.2 中间件方案

| 中间件 | 特点 | 适用场景 |
|--------|------|---------|
| **ProxySQL** | 功能强大，支持查询路由 | 生产推荐 |
| **MyCat** | 分库分表 + 读写分离 | 大规模分片 |
| **ShardingSphere** | 应用层集成 | Java 项目 |

---

## 七、主从延迟

### 7.1 延迟原因

```
常见延迟原因：
1. 主库大事务（如一次 DELETE 大量数据）
2. 从库 SQL Thread 单线程重放（MySQL 5.6 前）
3. 从库硬件性能差（CPU、IOPS）
4. 主库写压力过大
5. 大表的 DDL 操作
```

### 7.2 查看延迟

```sql
SHOW SLAVE STATUS\G;
-- Seconds_Behind_Master: 0  ← 延迟秒数

-- 更精确的延迟监控（基于心跳，更准确）
SHOW SLAVE STATUS\G;
-- Slave_IO_Running: Yes
-- Slave_SQL_Running: Yes
-- Retrieved_Gtid_Set: ...
-- Executed_Gtid_Set: ...
```

### 7.3 解决延迟

```sql
-- 方式1：并行复制（MySQL 5.7+）
-- 从库多线程并行回放 relay log
slave_parallel_workers = 4              -- 并行线程数
slave_parallel_type = LOGICAL_CLOCK     -- 基于组提交的并行

-- 方式2：避免大事务
-- 大批量更新拆分为小批量

-- 方式3：合理使用读写分离
-- 对实时性要求高的查询走主库
```

---

## 八、常见复制异常

### 8.1 复制中断

```sql
-- 常见错误及处理

-- 1. 主键冲突
-- 从库尝试插入已存在的记录
-- 解决：跳过该事务
SET GLOBAL sql_slave_skip_counter = 1;
START SLAVE;

-- 2. 从库表不存在
-- 从库缺少表（可能 DDL 没同步）
-- 解决：在从库创建缺失的表

-- 3. 主从数据不一致
-- 使用 pt-table-checksum 检查
-- 使用 pt-table-sync 修复
```

### 8.2 主从切换

```sql
-- 计划内切换
-- 1. 确认从库追赶上主库
SHOW SLAVE STATUS\G;
-- Seconds_Behind_Master = 0

-- 2. 停止主库写入
FLUSH TABLES WITH READ LOCK;

-- 3. 等待从库追上
SHOW SLAVE STATUS\G;

-- 4. 将从库提升为主库
STOP SLAVE;
RESET SLAVE ALL;

-- 5. 切换应用连接
-- 将从库 IP 设为主库
```

---

## 九、总结

### 复制模式对比

| 模式 | 数据安全 | 性能影响 | 适用场景 |
|------|---------|---------|---------|
| 异步复制 | 可能丢数据 | 无 | 性能优先 |
| 半同步复制 | 不丢数据 | 较小延迟 | 生产环境推荐 |
| 组复制（MGR）| 强一致 | 较大延迟 | 金融级一致性 |

### 主从架构最佳实践

| 实践 | 说明 |
|------|------|
| 开启半同步复制 | 数据安全与性能的平衡 |
| 使用 GTID | 简化切换，避免定位 binlog 位置 |
| 设置 read_only=1 | 从库只读，防止误写入 |
| 监控延迟 | Seconds_Behind_Master |
| 并行复制 | slave_parallel_workers >= 4 |
| 大事务拆分 | 避免主从延迟 |

---

**上一篇：** [MySQL（五）：日志系统]({{< relref "post/mysql-log" >}})

**下一篇：** [MySQL（七）：性能优化与架构]({{< relref "post/mysql-performance" >}})
