---
title: "Redis（三）：集群与高可用"
date: 2018-07-28
draft: false
categories: ["Redis"]
tags: ["Redis", "主从复制", "Sentinel", "Cluster", "高可用", "分片"]
toc: true
---

## 前言

单机 Redis 受限于单点故障和容量瓶颈。Redis 提供了三种集群方案来解决这些问题：**主从复制**（数据冗余）、**Sentinel**（高可用自动切换）和 **Cluster**（分布式分片）。

本文从主从复制原理出发，逐步深入到 Sentinel 和 Cluster 的架构设计。

<!--more-->

## 一、主从复制

### 1.1 基本架构

```
主节点（Master）：接收写请求，将数据同步到从节点
从节点（Slave）：只读，接收主节点的同步数据

                 ┌─────────┐
                 │ Master   │
                 │ 写入     │
                 └────┬────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
          ▼           ▼           ▼
      ┌──────┐   ┌──────┐   ┌──────┐
      │Slave 1│   │Slave 2│   │Slave 3│
      │ 只读  │   │ 只读  │   │ 只读  │
      └──────┘   └──────┘   └──────┘
```

### 1.2 配置

```conf
# 从节点配置（redis.conf）
replicaof 192.168.1.100 6379   # 指定主节点（Redis 5.0+）
# slaveof 192.168.1.100 6379   # Redis 5.0 之前的写法

# 主节点可以设置密码
requirepass yourpassword
# 从节点配置主节点密码
masterauth yourpassword

# 从节点只读（默认 yes）
replica-read-only yes
```

```bash
# 命令行方式
SLAVEOF 192.168.1.100 6379     # 临时成为从节点
SLAVEOF NO ONE                  # 取消复制，成为独立节点
```

### 1.3 复制原理

```
全量复制（首次或断连后重连）：
    Master                          Slave
      │                               │
      │── PSYNC ? -1 ──────────────▶  │
      │◀─ FULLRESYNC <runid> <offset> │
      │── BGSAVE 生成 RDB ─────▶      │
      │── 发送 RDB 文件 ──────────▶    │
      │                               │── 加载 RDB
      │── 发送缓冲区增量命令 ───────▶  │
      │                               │── 执行命令
      
增量复制（断连后重连，使用复制积压缓冲区）：
      │── PSYNC <runid> <offset> ──▶  │
      │◀─ CONTINUE ──────────────────  │
      │── 发送缺失的增量命令 ───────▶  │
```

**关键配置：**

```conf
# 复制积压缓冲区大小（决定增量复制能补偿多少数据）
# 默认 1MB，建议根据网络断连时间和写入量调整
repl-backlog-size 64mb

# 无延迟复制（主节点无延迟确认）
repl-disable-tcp-nodelay no
```

### 1.4 主从延迟

```bash
# 查看从节点延迟
INFO replication
# role:slave
# master_last_io_seconds_ago: 0     # 与主节点的连接延迟
# master_link_status: up
# slave_repl_offset: 12345678        # 复制偏移量

# 主从延迟判断：对比主库和从库的 offset
# offset 差异大 = 延迟严重
```

---

## 二、Sentinel——哨兵模式

### 2.1 架构

```
Sentinel 是 Redis 的高可用方案：
- 监控主从节点的运行状态
- 主节点故障时自动将某个从节点提升为主节点
- 对客户端提供新主节点的地址

                   ┌────────────────┐
                   │   Sentinel 集群  │
                   │  (3 或 5 个节点) │
                   └────┬────┬──────┘
                        │    │
           ┌────────────┘    └────────────┐
           │                              │
           ▼                              ▼
      ┌──────────┐                ┌──────────┐
      │  Master   │◀───────────── │  Slave    │
      │  主节点    │  异步同步       │  从节点   │
      └──────────┘                └──────────┘
```

### 2.2 配置

```conf
# sentinel.conf

port 26379                                       # Sentinel 端口

# 监控主节点
# sentinel monitor <name> <ip> <port> <quorum>
sentinel monitor mymaster 192.168.1.100 6379 2   # quorum=2，至少 2 个 Sentinel 同意才判下

sentinel down-after-milliseconds mymaster 30000  # 主观下线时间（30 秒无响应）
sentinel failover-timeout mymaster 180000        # 故障转移超时
sentinel parallel-syncs mymaster 1               # 新主节点同时同步的从节点数
```

### 2.3 故障转移流程

```
1. 主观下线（SDOWN）
   Sentinel 发现主节点 ping 响应超时（down-after-milliseconds）

2. 客观下线（ODOWN）
   多个 Sentinel 都认为主节点下线（达到 quorum 数量）

3. 选举 Leader Sentinel
   在 Sentinel 集群中选举一个 Leader 来执行故障转移

4. Leader Sentinel 执行故障转移：
   a. 从从节点中选出一个作为新主节点
      ├── 优先选择 replica-priority 最小的
      ├── 优先选择 offset 最靠前的
      └── 优先选择 runid 最小的
   b. 其他从节点执行 SLAVEOF 新主节点
   c. 原主节点恢复后，变成新主节点的从节点
```

### 2.4 客户端使用

```java
// Java 客户端（Jedis）使用 Sentinel
JedisSentinelPool pool = new JedisSentinelPool(
    "mymaster",                    // 主节点名称
    Set.of("192.168.1.10:26379",   // Sentinel 地址
           "192.168.1.11:26379",
           "192.168.1.12:26379")
);

try (Jedis jedis = pool.getResource()) {
    jedis.set("key", "value");  // 自动指向当前主节点
}
```

### 2.5 Sentinel 部署建议

```
最少 3 个 Sentinel 实例（奇数）
quorum 设为 N/2 + 1（如 3 个节点时 quorum=2）

Sentinel 最好部署在不同机器上
不要和 Redis 实例部署在同一台机器（防止同时宕机）
```

---

## 三、Cluster——集群模式

### 3.1 架构

```
Redis Cluster 是 Redis 3.0+ 引入的分布式方案：
- 数据自动分片到多个节点
- 每个节点负责一部分哈希槽
- 节点间 Gossip 协议通信
- 支持主从架构（每个分片有从节点）

                 ┌─────────────────────────┐
                 │    Redis Cluster          │
                 │                          │
                 │  ┌──────┐  ┌──────┐      │
                 │  │ Node1 │  │ Node2 │      │
                 │  │ 槽0-5000│ │ 槽5001-10000│
                 │  └──┬───┘  └──┬───┘      │
                 │     │         │           │
                 │  ┌──▼───┐  ┌──▼───┐      │
                 │  │Node1-S│  │Node2-S│      │
                 │  └──────┘  └──────┘      │
                 │                          │
                 │  ┌──────┐  ┌──────┐      │
                 │  │ Node3 │  │ Node4 │      │
                 │  │ 槽10001-│ │ 槽15001-   │
                 │  │ 15000 │  │ 16383 │      │
                 │  └──┬───┘  └──────┘      │
                 └─────────────────────────┘
```

### 3.2 哈希槽分布

```
Redis Cluster 有 16384 个哈希槽（0 ~ 16383）

计算 key 属于哪个槽：
slot = CRC16(key) % 16384

每个节点负责一部分槽：
Node1: 0 ~ 5000
Node2: 5001 ~ 10000
Node3: 10001 ~ 16383

新增节点时，从现有节点迁移部分槽
删除节点时，将槽迁移到其他节点
```

### 3.3 配置

```conf
# redis.conf

# 开启集群模式
cluster-enabled yes

# 集群配置文件（自动生成，无需手动创建）
cluster-config-file nodes-6379.conf

# 节点超时时间
cluster-node-timeout 15000

# 从节点数量（每个主节点有几个从节点）
cluster-migration-barrier 1
```

### 3.4 搭建集群

```bash
# 1. 启动 6 个 Redis 实例（3 主 3 从）
redis-server redis-7000.conf
redis-server redis-7001.conf
...
redis-server redis-7005.conf

# 2. 创建集群
redis-cli --cluster create \
    192.168.1.10:7000 192.168.1.10:7001 \
    192.168.1.10:7002 192.168.1.10:7003 \
    192.168.1.10:7004 192.168.1.10:7005 \
    --cluster-replicas 1   # 每个主节点配 1 个从节点

# 3. 查看集群状态
redis-cli -p 7000 cluster info
redis-cli -p 7000 cluster nodes

# 4. 使用集群模式连接
redis-cli -c -p 7000    # -c 开启集群模式（自动重定向）
```

### 3.5 Cluster 中的数据访问

```bash
# 非集群客户端访问另一个节点的 key 会返回 MOVED 错误
# 集群客户端自动处理重定向

# 客户端计算 slot，直接连接正确的节点
# 如果 slot 迁移中，返回 ASK 错误，引导客户端重试

# Hash Tag：强制多个 key 放到同一槽
# 使用 {} 中的部分计算 slot
SET {user:100}:name "Tom"
SET {user:100}:age "25"
# 两个 key 的 {} 内都是 user:100，所以都在同一个 slot
# 支持同 slot 的多 key 操作（MGET、MSET、事务等）
```

### 3.6 集群的局限

```
1. 多 key 操作限制
   - 不同 slot 的 key 不支持 MGET/事务/Lua 脚本（跨槽操作）
   - 使用 Hash Tag 将相关 key 放到同一槽

2. 不支持多数据库（只能使用 db 0）

3. 批量操作受限
   - pipeline 的 key 可能分布在不同节点

4. 事务有限制
   - 事务中的 key 必须在同一节点上
```

---

## 四、三种方案对比

| 维度 | 主从复制 | Sentinel | Cluster |
|------|---------|----------|---------|
| 高可用 | ❌ 手动切换 | ✅ 自动切换 | ✅ 自动切换 |
| 数据分片 | ❌ 不支持 | ❌ 不支持 | ✅ 自动分片 |
| 写能力 | 单节点 | 单节点 | 多节点（分片） |
| 读能力 | 从节点分担 | 从节点分担 | 每个节点 |
| 客户端复杂度 | 低 | 中 | 高 |
| 节点数 | 2+ | 3+ | 6+（3主3从）|
| 典型场景 | 读写分离 | 高可用 | 大数据量 + 高可用 |

---

## 五、总结

### 选型建议

```
单机 Redis + 主从复制：
  ├── 数据量不大（< 10G）
  ├── 可以接受手动故障恢复
  └── 需要读写分离

Redis Sentinel：
  ├── 数据量不大（< 10G）
  ├── 需要自动故障恢复
  └── 不需要数据分片

Redis Cluster：
  ├── 数据量大（> 10G）
  ├── 需要自动故障恢复
  └── 需要横向扩展写能力
```

### 核心配置速查

| 配置 | 用途 |
|------|------|
| `replicaof` | 设置从节点的主节点地址 |
| `sentinel monitor` | 设置 Sentinel 监控的主节点 |
| `cluster-enabled yes` | 开启集群模式 |
| `cluster-node-timeout` | 集群节点超时时间 |

---

**上一篇：** [Redis（二）：持久化机制]({{< relref "post/redis-persistence" >}})

**下一篇：** [Redis（四）：过期策略与淘汰机制]({{< relref "post/redis-expiration-eviction" >}})
