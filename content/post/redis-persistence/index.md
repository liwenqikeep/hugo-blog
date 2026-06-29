---
title: "Redis（二）：持久化机制"
date: 2018-07-26
draft: false
categories: ["Redis"]
tags: ["Redis", "持久化", "RDB", "AOF", "混合持久化", "数据恢复"]
toc: true
---

## 前言

Redis 是内存数据库，数据默认存储在内存中。如果不做持久化，一旦进程退出或服务器宕机，所有数据都会丢失。Redis 提供了两种持久化机制：**RDB（快照）** 和 **AOF（追加文件）**，以及 Redis 4.0+ 引入的**混合持久化**。

理解它们的原理和适用场景，才能做出合理的配置选择。

<!--more-->

## 一、RDB 持久化

### 1.1 原理

RDB 是 Redis 的**快照**持久化方式——在指定的时间间隔内，将内存中的全量数据以二进制格式写入磁盘。

```
RDB 文件是一个经过压缩的二进制文件
文件名：dump.rdb（可配置）

触发时机：
├── save 命令（同步阻塞）
├── bgsave 命令（fork 子进程，异步）
├── 配置的自动触发规则
└── 关闭 Redis 时（shutdown）
```

### 1.2 配置

```conf
# redis.conf

# ★ 自动触发规则：在 N 秒内至少有 M 个 key 变化时执行 bgsave
save 900 1       # 900 秒（15 分钟）内至少 1 次修改
save 300 10      # 300 秒（5 分钟）内至少 10 次修改
save 60 10000    # 60 秒内至少 10000 次修改
# 可以配置多条，满足任一就触发

# RDB 文件配置
dbfilename dump.rdb
dir /var/lib/redis

# 压缩（默认开启，消耗 CPU 节省磁盘空间）
rdbcompression yes

# 校验（默认开启，增加约 10% 性能开销）
rdbchecksum yes
```

### 1.3 bgsave 执行流程

```
客户端发送 bgsave
    │
    ▼
Redis 主进程 fork 子进程
    │
    ├── 主进程继续处理命令（不阻塞）
    │
    └── 子进程将内存数据写入临时 RDB 文件
          │
          ├── 遍历所有数据库和 key
          ├── 写入临时文件
          └── 完成后覆盖原来的 dump.rdb
              │
              ▼
            持久化完成

fork 时的写时复制（Copy-On-Write）：
fork 时子进程共享主进程的内存页
主进程在 fork 后发生写操作时，才复制被修改的内存页
所以 bgsave 期间，主进程仍然可以处理写请求
```

### 1.4 RDB 的优缺点

```conf
# ✅ 优点
# 1. 文件紧凑，适合备份和灾难恢复
# 2. 恢复速度极快（加载二进制文件）
# 3. fork 子进程，对主进程性能影响小

# ❌ 缺点
# 1. 可能丢失最后一次快照后的数据（如下次快照前宕机）
# 2. fork 子进程时，如果数据量大，fork 耗时可能较长
# 3. 大内存机器（> 32G）的 fork 可能造成秒级延迟
```

---

## 二、AOF 持久化

### 2.1 原理

AOF（Append Only File）**记录每次写操作的命令**，以追加的方式写入文件。恢复时重新执行所有命令。

```
AOF 文件内容（纯文本，可读）：

SET key1 value1
SET key2 value2
INCR counter
LPUSH list a b c
...
```

### 2.2 配置

```conf
# redis.conf

# 开启 AOF
appendonly yes
appendfilename "appendonly.aof"

# ★ 刷盘策略（关键性能配置）
# appendfsync always    # 每次写命令都 fsync（最安全，最慢）
appendfsync everysec    # 每秒 fsync 一次（推荐，最多丢 1 秒数据）
# appendfsync no        # 由操作系统决定 fsync 时机（最快，最不安全）

# AOF 重写
auto-aof-rewrite-percentage 100     # AOF 文件增长超过 100% 时触发重写
auto-aof-rewrite-min-size 64mb      # AOF 文件至少 64MB 才触发重写
```

### 2.3 AOF 刷盘策略对比

```
always：       每次写入都 fsync
  └── 安全性最高，最多丢一次写操作
  └── 性能最差，约几千 QPS

everysec（推荐）：
  └── 每秒 fsync 一次
  └── 最多丢 1 秒数据
  └── 性能好，可达到十万 QPS

no：
  └── 由操作系统决定刷盘（默认 30 秒）
  └── 性能最好，但可能丢大量数据
  └── 不推荐
```

### 2.4 AOF 重写

AOF 文件会随着写操作越来越多而膨胀。AOF 重写通过合并命令来压缩文件大小。

```
重写前 AOF 内容：
INCR counter
INCR counter
INCR counter
INCR counter
INCR counter

重写后 AOF 内容：
SET counter 5
```

**AOF 重写过程（类似 bgsave，fork 子进程）：**

```
1. fork 子进程
2. 子进程将当前内存中的数据转为命令，写入新 AOF 文件
3. 在子进程重写期间，主进程将新命令同时写入 AOF 缓冲区和重写缓冲区
4. 子进程完成后，将重写缓冲区的命令追加到新 AOF 文件
5. 用新 AOF 文件覆盖旧 AOF 文件
```

### 2.5 AOF 的优缺点

```conf
# ✅ 优点
# 1. 数据安全性更高（最多丢 1 秒数据）
# 2. AOF 文件可读，可以手动修改（如误删 key 后删掉最后一条命令）
# 3. AOF 文件可写，支持 Redis-CLI 的管道恢复

# ❌ 缺点
# 1. 文件体积通常比 RDB 大
# 2. 恢复速度比 RDB 慢（需要逐条重放命令）
```

---

## 三、RDB vs AOF 对比

| 对比维度 | RDB | AOF |
|---------|-----|-----|
| 存储内容 | 二进制快照（数据本身）| 文本协议（写命令）|
| 文件大小 | 小（压缩后）| 较大 |
| 恢复速度 | 快（直接加载）| 慢（逐条执行）|
| 数据安全性 | 可能丢很多数据 | 最多丢 1s（everysec）|
| 对性能影响 | fork 耗时，COW 内存 | 刷盘频率影响 |
| 适用场景 | 备份、冷备、全量恢复 | 数据安全优先 |

---

## 四、混合持久化（Redis 4.0+）

### 4.1 原理

```
结合 RDB 和 AOF 的优点：
- RDB 快照做全量
- AOF 增量日志做增量

AOF 重写时，不再生成纯 AOF 格式，
而是先生成当前数据的 RDB 快照写入 AOF 文件，
再追加后续的增量命令。
```

**配置：**

```conf
aof-use-rdb-preamble yes  # Redis 5.0+ 默认开启
```

**恢复时：**

```
加载 AOF 文件：
├── 先加载前面的 RDB 部分（极快）
└── 再重放后面的 AOF 增量（补全重写后的变更）
```

### 4.2 推荐配置

```conf
# 生产环境推荐配置

# 同时开启 RDB + AOF（混合持久化）
save 900 1
save 300 10
save 60 10000

appendonly yes
appendfsync everysec
aof-use-rdb-preamble yes

# AOF 重写
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

---

## 五、数据恢复流程

### 5.1 恢复优先级

```
重启时，Redis 检查以下文件：

1. 优先加载 AOF（如果 appendonly yes）
   └── 读取 appendonly.aof → 恢复数据

2. 如果没有 AOF，加载 RDB
   └── 读取 dump.rdb → 恢复数据

3. 都没有 → 空数据库
```

### 5.2 AOF 文件损坏修复

```bash
# AOF 文件可能因宕机损坏，使用 redis-check-aof 修复
redis-check-aof --fix appendonly.aof

# RDB 文件修复
redis-check-rdb dump.rdb
```

### 5.3 数据迁移

```bash
# 场景：在不同 Redis 实例间迁移数据

# 方案 1：使用 AOF 文件
# 1. 源 Redis 执行 BGREWRITEAOF
# 2. 复制 AOF 文件到目标
# 3. 目标 Redis 启动时自动加载

# 方案 2：使用 RDB 文件
# 1. 源 Redis 执行 BGSAVE
# 2. 复制 dump.rdb 到目标
# 3. 重启目标 Redis
```

---

## 六、最佳实践

### 6.1 持久化策略选择

| 场景 | 推荐策略 | 原因 |
|------|---------|------|
| 缓存（可丢失）| 不开启持久化 | 最大性能 |
| 数据安全要求高 | AOF + RDB | 最多丢 1s |
| 大量写入场景 | RDB | AOF 频繁刷盘影响性能 |
| 大实例（> 32G）| AOF（RDB fork 耗时）| fork 可能延迟过长 |

### 6.2 备份策略

```bash
# 1. 定时备份 RDB 文件（crontab）
0 3 * * * cp /data/redis/dump.rdb /backup/redis/dump_$(date +\%Y\%m\%d).rdb

# 2. 同时备份 AOF 文件
0 4 * * * cp /data/redis/appendonly.aof /backup/redis/aof_$(date +\%Y\%m\%d).aof

# 3. 备份文件保留一定天数
find /backup/redis -name "*.rdb" -mtime +30 -delete
find /backup/redis -name "*.aof" -mtime +30 -delete
```

---

## 七、总结

### 持久化选择速查

```
RDB 快照     → 备份/全量恢复/允许丢数据
AOF everysec → 数据安全优先/最多丢1秒
RDB + AOF   → 两者兼顾（推荐）
混合持久化   → Redis 4.0+ 最佳选择
```

### 恢复优先级

```
AOF (混合格式) > RDB > 空数据库
```

---

**上一篇：** [Redis（一）：数据结构与使用]({{< relref "post/redis-data-structures" >}})

**下一篇：** [Redis（三）：集群与高可用]({{< relref "post/redis-cluster" >}})
