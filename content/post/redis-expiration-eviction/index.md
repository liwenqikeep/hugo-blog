---
title: "Redis（四）：过期策略与淘汰机制"
date: 2018-07-30
draft: false
categories: ["Redis"]
tags: ["Redis", "过期策略", "淘汰机制", "LRU", "LFU", "内存管理"]
toc: true
---

## 前言

Redis 是内存数据库，内存是有限的。当内存被写满时，Redis 需要决定如何处理新的写入请求。同时，对于设置了过期时间的 key，Redis 也需要一种高效的机制来清理过期数据。

本文覆盖 Redis 的**过期策略**（如何删除过期 key）和**淘汰机制**（内存满时如何腾出空间），以及它们对缓存设计的影响。

<!--more-->

## 一、过期策略

### 1.1 设置过期时间

```bash
# 设置过期时间
SET key value EX 3600         # 60 分钟后过期
SETEX key 3600 value          # 等价于 SET + EXPIRE
EXPIRE key 3600               # 对已有 key 设置过期
EXPIREAT key 1700000000       # 指定时间戳过期

# 查看过期时间（TTL）
TTL key                       # 剩余秒数（-1 永久，-2 已过期删除）
PTTL key                      # 剩余毫秒数

# 移除过期时间
PERSIST key                   # 移除过期时间，变为永久

# 其他
SET key value NX EX 10        # 不存在才设置，10 秒过期（分布式锁常用）
```

### 1.2 三种过期删除策略

```
策略 1：定时删除（主动删除）
  ├── 设置 key 过期时，立即启动定时器删除
  ├── 内存友好（及时释放）
  └── CPU 不友好（大量定时器占用 CPU）

策略 2：惰性删除（被动删除）
  ├── 访问 key 时才检查是否过期，过期则删除
  ├── CPU 友好（只检查访问的 key）
  └── 内存不友好（过期 key 一直存在，占用内存）

策略 3：定期删除（折中）
  ├── 每隔一段时间，随机抽取一批过期的 key 删除
  ├── CPU 和内存的折中
  └── Redis 实际使用的策略之一
```

### 1.3 Redis 的组合策略

Redis 实际使用 **惰性删除 + 定期删除** 的组合：

```
惰性删除：
  当客户端 GET / EXISTS 等命令访问 key 时
  → 检查 key 是否过期
  → 过期则删除，返回 nil/0

定期删除：
  每 100ms 执行一次（可配置）
  → 从设置了过期时间的 key 集合中随机抽取 20 个
  → 删除其中过期的 key
  → 如果过期比例 > 25%，重复抽取
  → 单次耗时不超过 25ms
```

```conf
# 定期删除相关配置
hz 10                     # 每秒执行定期任务的频率（默认 10）
                          # 包括过期 key 清理、主动 defrag 等
                          # 调高可加速过期清理，但增加 CPU 开销
```

### 1.4 过期 key 的删除时机

```
过期 key 在以下时机被删除：
1. 被访问时（惰性删除）
2. 定期扫描时（定期删除）
3. 内存达到 maxmemory 时（淘汰机制触发前也会尝试清理过期 key）
4. 手动执行 SCAN 命令时（SCAN 会跳过过期 key）
```

---

## 二、内存淘汰机制

### 2.1 maxmemory 配置

```conf
# 设置最大内存
maxmemory 4gb                   # 4GB
# maxmemory 0                    # 0 = 不做限制（可能耗尽系统内存）

# 设置淘汰策略
maxmemory-policy allkeys-lru    # 推荐
```

**如何估算 maxmemory：**

```
maxmemory 的设置依据：
1. 数据量 × 1.5（估算峰值）
2. 预留系统内存（操作系统 + 其他进程）
3. 考虑持久化时的内存开销（bgsave fork 需要 COW）

公式：maxmemory = 物理内存 × 0.6 ~ 0.8
```

### 2.2 八种淘汰策略

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| **noeviction** | 不淘汰，写满报错 | 不丢数据 |
| **allkeys-lru** | 所有 key 中最久未使用的淘汰（推荐）| 大部分缓存场景 |
| **allkeys-lfu** | 所有 key 中使用频率最低的淘汰 | 访问频率差异大的场景 |
| **allkeys-random** | 随机淘汰 | 访问模式随机 |
| **volatile-lru** | 设置过期时间的 key 中 LRU 淘汰 | 有缓存的混合场景 |
| **volatile-lfu** | 设置过期时间的 key 中 LFU 淘汰 | —— |
| **volatile-random**| 设置过期时间的 key 中随机淘汰 | —— |
| **volatile-ttl** | 设置过期时间的 key 中 TTL 最小的淘汰 | —— |

**推荐策略：**

```
缓存场景：allkeys-lru
  → 最常用，不管有没有设置过期时间，淘汰最近最少使用的

不丢数据场景：noeviction
  → 写满时报错，业务层处理（如告警 OOM）
  → 使用消息队列、缓存降级等方案
```

### 2.3 LRU vs LFU

```conf
# LRU（Least Recently Used）：最近最少使用
# 淘汰最久没有被访问的 key
# 问题：一次批量访问可能让冷数据"翻新"

# LFU（Least Frequently Used）：最不经常使用
# 淘汰访问频率最低的 key
# 解决了 LRU 的"一次性热点"问题

# LFU 配置（Redis 4.0+）
lfu-log-factor 10         # 频率增长的对数因子
lfu-decay-time 1          # 频率衰减时间（分钟）
```

**LRU vs LFU 对比：**

```
场景：热点数据偶尔被批量访问，然后长期不访问

LRU：批量访问后，这些 key 排在最前面 → 不会很快被淘汰
LFU：访问频率低 → 很快被淘汰

结论：LFU 更适合区分"真正热"和"偶尔热"的场景
```

### 2.4 Redis 的近似 LRU

Redis 没有使用标准的 LRU（维护一个全量的 LRU 链表的成本太高），而是使用**近似 LRU**：

```conf
# Redis 近似 LRU 的工作原理：

1. 每个 key 记录最后一次访问的 LRU 时间戳（24bit）

2. 淘汰时，随机抽取 N 个 key（默认 5 个）
   maxmemory-samples 5

3. 从 N 个 key 中淘汰最久未使用的那个

4. 增大 sample 数使淘汰更精确（但增加 CPU）
   maxmemory-samples 10   # 更接近精确 LRU，略多 CPU
```

**近似 LRU vs 精确 LRU：**

```
精确 LRU：维护全局链表，O(1) 淘汰，但内存开销大
近似 LRU（sample=5）：约 90% 接近精确 LRU
近似 LRU（sample=10）：约 99% 接近精确 LRU
```

### 2.5 淘汰触发流程

```
写入命令（SET / LPUSH / SADD 等）
    │
    ▼
Redis 检查当前内存
    │
    ├── used_memory < maxmemory
    │     └── 正常写入
    │
    └── used_memory >= maxmemory
          │
          ├── maxmemory-policy = noeviction
          │     └── 返回 OOM 错误
          │
          └── 其他策略
                │
                ├── 1. 尝试过期 key 回收（定期删除）
                ├── 2. 按策略淘汰 key
                ├── 3. 释放内存
                ├── 4. 如果释放不够，重复 2-3
                └── 5. 直到内存降到 maxmemory 以下
```

---

## 三、缓存穿透 / 击穿 / 雪崩

这三个问题常与过期策略和淘汰机制一起讨论。

### 3.1 缓存穿透

```
问题：查询一个不存在的数据
  → 缓存没有
  → 数据库也没有
  → 每次请求都到数据库，缓存失去保护作用

解决方案：
1. 缓存空值（短期缓存 null，如 60 秒）
2. 布隆过滤器（Bloom Filter）拦截不存在的数据

# 布隆过滤器
BF.RESERVE bloom 0.01 1000000    # 创建布隆过滤器（误差率 1%，预计 100 万元素）
BF.ADD bloom "user:1001"          # 添加元素
BF.EXISTS bloom "user:1001"       # 判断是否存在 → 1
BF.EXISTS bloom "user:9999"       # → 0（肯定不存在）
```

### 3.2 缓存击穿

```
问题：一个热点 key 正好过期
  → 大量并发请求同时打到数据库
  → 数据库瞬间高负载

解决方案：
1. 互斥锁（分布式锁，只让一个请求查数据库）
2. 热点数据永不过期（逻辑过期）
3. 提前更新（后台定时刷新热点缓存）

# 互斥锁方案（使用 SETNX 实现分布式锁）
SET lock:hotkey uuid NX EX 10
if success:
    data = queryDB()
    SET hotkey data EX 3600
    DEL lock:hotkey
else:
    sleep(50ms)
    retry  # 其他线程等待
```

### 3.3 缓存雪崩

```
问题：大量 key 在同一时间过期，或 Redis 宕机
  → 大量请求穿透到数据库
  → 数据库瞬间崩溃

解决方案：
1. 过期时间加随机值：EXPIRE + random(0, 300)
2. 多级缓存（本地缓存 + Redis 缓存）
3. 限流降级（限制数据库访问并发）
4. Redis 高可用（主从 + Sentinel + 持久化）

# 过期时间随机化
SET user:1001 data EX 3600
EXPIRE user:1001 3600 + random(0, 300)
```

---

## 四、配置建议

```conf
# 内存配置
maxmemory 4gb
maxmemory-policy allkeys-lru
maxmemory-samples 10

# 主动 defrag（内存碎片整理，Redis 4.0+）
activedefrag yes
active-defrag-threshold-lower 10    # 碎片率 > 10% 开始整理
active-defrag-threshold-upper 100   # 碎片率 > 100% 最大努力整理
active-defrag-cycle-min 5            # 最小 CPU 占用百分比
active-defrag-cycle-max 75           # 最大 CPU 占用百分比
```

---

## 五、总结

### 过期与淘汰速查

| 机制 | 策略 | 触发时机 |
|------|------|---------|
| 过期删除 | 惰性删除 + 定期删除 | 访问 key / 定时扫描 |
| 内存淘汰 | allkeys-lru（推荐）| 内存超过 maxmemory |

### 缓存问题与对策

| 问题 | 原因 | 对策 |
|------|------|------|
| 缓存穿透 | 查询不存在的数据 | 布隆过滤器 / 缓存空值 |
| 缓存击穿 | 热点 key 过期 | 互斥锁 / 逻辑过期 |
| 缓存雪崩 | 大量 key 同时过期 | 过期时间加随机值 |

---

**上一篇：** [Redis（三）：集群与高可用]({{< relref "post/redis-cluster" >}})

**下一篇：** [Redis（五）：缓存设计与实战]({{< relref "post/redis-cache-practice" >}})
