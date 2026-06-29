---
title: "Redis（一）：数据结构与使用"
date: 2018-07-24
draft: false
categories: ["Redis"]
tags: ["Redis", "String", "Hash", "List", "Set", "ZSet", "数据结构"]
toc: true
---

## 前言

Redis（Remote Dictionary Server）是一个基于内存的 Key-Value 数据库，以其高性能和丰富的数据结构闻名。理解 Redis 的五大数据类型及其底层编码，是使用好 Redis 的第一步。

本文从基础操作出发，深入到各数据类型的底层实现原理。

<!--more-->

## 一、Redis 整体架构

```
┌──────────┐  命令   ┌──────────────────┐  内存操作   ┌──────────┐
│  Client   │───────▶│   Redis Server    │───────────▶│  内存     │
│           │◀───────│  (单线程事件循环)  │◀───────────│ (数据)   │
└──────────┘  响应   └──────────────────┘            └──────────┘
                              │
                              ├── 持久化（RDB / AOF）
                              ├── 主从复制
                              └── 集群

核心特性：
- 纯内存操作，单线程模型（避免锁竞争）
- 多路 I/O 复用（epoll/kqueue）
- 丰富的数据结构
- 支持持久化和主从复制
```

---

## 二、五大数据类型

### 2.1 String——字符串

```bash
# 基本操作
SET name "Tom"              # 设置
GET name                    # 获取 → "Tom"
SETNX name "Jerry"          # 不存在才设置 → 0（已存在）
GETSET name "Alice"         # 获取旧值并设置新值
MSET k1 v1 k2 v2            # 批量设置
MGET k1 k2                  # 批量获取

# 数值操作
SET counter 100
INCR counter                 # 101
INCRBY counter 5             # 106
DECR counter                 # 105
DECRBY counter 10            # 95

# 过期时间
SETEX code 60 "123456"       # 设置 60 秒过期
SET key value PX 10000       # 10 秒过期（毫秒）
EXPIRE key 30                # 设置过期时间（秒）
TTL key                      # 查看剩余时间

# 应用场景
# 1. 缓存数据
SET user:1 '{"name":"Tom"}' EX 3600
# 2. 计数器（PV、限流）
INCR page_view:2024-01-01
# 3. 分布式锁
SET lock:order:123 "uuid" NX EX 10
```

**底层编码：**

```
int：        整数，如 SET num 100
embstr：     短字符串（≤ 44 字节）
raw：        长字符串（> 44 字节）
```

### 2.2 List——列表

```bash
# 右侧操作
RPUSH list a b c             # 右侧推入 → [a, b, c]
RPOP list                    # 右侧弹出 → c

# 左侧操作
LPUSH list x y               # 左侧推入 → [y, x, a, b]
LPOP list                    # 左侧弹出 → y

# 范围操作
LRANGE list 0 -1             # 获取全部 → [x, a, b]
LLEN list                    # 列表长度 → 3
LINDEX list 1                # 获取索引 1 → a

# 阻塞操作
BLPOP queue 5                # 阻塞弹出（最多等 5 秒）→ 消息队列

# 应用场景
# 1. 消息队列
LPUSH msg_queue task_data
BRPOP msg_queue 0             # 阻塞消费

# 2. 最新列表（如最新评论、最新文章）
LPUSH user:1:articles "title1" "title2"
LTRIM user:1:articles 0 9     # 只保留最新的 10 条
```

**底层编码：**

```
ziplist（压缩列表）：元素少且值小时使用（list-max-ziplist-entries 默认 512）
quicklist：          双向链表 + 每个节点是一个 ziplist（Redis 3.2+ 默认）
```

### 2.3 Hash——哈希

```bash
# 基本操作
HSET user:1 name "Tom" age "25"
HGET user:1 name              # → "Tom"
HGETALL user:1                # → name Tom age 25
HMGET user:1 name age         # → Tom 25
HKEYS user:1                  # → name age
HVALS user:1                  # → Tom 25
HDEL user:1 age               # 删除字段
HEXISTS user:1 name           # 是否存在 → 1

# 数值操作
HINCRBY user:1 login_count 1  # 登录次数 +1

# 应用场景
# 1. 对象存储（代替多个 String key）
HSET order:1001 id 1001 amount 99.9 status "paid"

# 2. 购物车
HSET cart:user1 product1 2     # 商品 + 数量
HINCRBY cart:user1 product1 1  # 增加数量
```

**底层编码：**

```
ziplist（压缩列表）：字段和值都小且数量少时使用
hashtable（哈希表）：超过阈值时使用

# 阈值配置
hash-max-ziplist-entries 512   # 最大条目数
hash-max-ziplist-value 64      # 最大字段值长度
```

### 2.4 Set——集合

```bash
# 基本操作
SADD user:1:tags "java" "spring" "redis"   # 添加
SMEMBERS user:1:tags           # 获取所有 → java spring redis
SISMEMBER user:1:tags "java"   # 是否成员 → 1
SCARD user:1:tags              # 元素数量 → 3
SREM user:1:tags "spring"      # 移除

# 集合操作
SADD set1 a b c d
SADD set2 c d e f

SINTER set1 set2               # 交集 → c d
SUNION set1 set2               # 并集 → a b c d e f
SDIFF set1 set2                # 差集 → a b

SRANDMEMBER set1 2             # 随机取 2 个
SPOP set1                      # 随机弹出 1 个

# 应用场景
# 1. 标签系统 / 兴趣匹配
SADD user:1:tags java redis
SADD user:2:tags redis python
SINTER user:1:tags user:2:tags  # 共同兴趣 → redis

# 2. 抽奖
SADD lottery:event1 user1 user2 user3
SRANDMEMBER lottery:event1 1    # 抽 1 个
SPOP lottery:event1             # 抽奖并移除
```

**底层编码：**

```
intset（整数集合）：所有元素都是整数时使用
hashtable（哈希表）：有非整数元素或数量大时使用
```

### 2.5 ZSet——有序集合

```bash
# 基本操作
ZADD leaderboard 100 "Tom" 200 "Jerry" 300 "Alice"
ZRANGE leaderboard 0 -1 WITHSCORES     # 按分数升序 → Tom Jerry Alice
ZREVRANGE leaderboard 0 -1 WITHSCORES  # 按分数降序 → Alice Jerry Tom
ZSCORE leaderboard "Tom"               # 查看分数 → 100
ZRANK leaderboard "Tom"                # 查看排名 → 0（升序第 1）
ZCARD leaderboard                      # 元素数量 → 3
ZREM leaderboard "Tom"                 # 移除元素

# 范围操作
ZRANGEBYSCORE leaderboard 100 200     # 分数在 100-200 之间的元素
ZCOUNT leaderboard 100 300             # 计数

# 增量
ZINCRBY leaderboard 50 "Tom"           # Tom 分数 +50

# 应用场景
# 1. 排行榜
ZADD daily_rank:0724 100 "user1"
ZADD daily_rank:0724 200 "user2"
ZREVRANGE daily_rank:0724 0 9 WITHSCORES  # 前 10 名

# 2. 延迟队列（用时间戳作为分数）
ZADD delay_queue 1700000000 "task1"
ZADD delay_queue 1700000100 "task2"

# 取到期的任务
ZRANGEBYSCORE delay_queue 0 1700000050  # 取 0 ~ 当前时间戳的任务
```

**底层编码：**

```
ziplist（压缩列表）：元素少且值小时使用
skiplist（跳表）：    超过阈值时使用
zset-max-ziplist-entries 128
zset-max-ziplist-value 64
```

---

## 三、特殊数据结构

### 3.1 Bitmaps——位图

```bash
# 用位操作节省存储空间
SETBIT user:sign:0724 100 1    # 用户 100 签到
GETBIT user:sign:0724 100      # → 1
BITCOUNT user:sign:0724        # 今天签到人数

# 应用：用户签到、在线状态、布隆过滤器
```

### 3.2 HyperLogLog——基数统计

```bash
# 用于统计 UV（不精确去重，误差约 0.81%）
PFADD uv:page1 "user1" "user2" "user1"
PFCOUNT uv:page1            # → 2（去重后）
PFMERGE uv:total uv:page1 uv:page2  # 合并

# 极省内存：12KB 即可统计 2^64 个不同元素
```

### 3.3 GEO——地理位置

```bash
GEOADD cities 116.39 39.91 "北京" 121.47 31.23 "上海"
GEODIST cities "北京" "上海" km   # 距离
GEORADIUS cities 116.39 39.91 100 km  # 附近 100km 的城市
```

---

## 四、Redis 单线程模型

### 4.1 为什么单线程还这么快？

```
1. 纯内存操作：所有数据在内存中，没有磁盘 IO 延迟
2. 多路 I/O 复用：单线程处理多个连接的事件
3. 避免上下文切换：无锁竞争、无线程切换开销
4. 数据结构高效：跳表、压缩列表等设计针对内存优化
```

### 4.2 事件循环模型

```
epoll / kqueue 监听多个 socket 连接
    │
    ▼
事件循环（单线程）：
    │
    ├── accept 事件 → 接受新连接
    ├── read 事件  → 读取客户端命令
    ├── 命令执行    → 内存操作（数据结构操作）
    └── write 事件 → 返回结果给客户端
```

**注意：** Redis 6.0+ 引入了多线程 IO，但命令执行仍然是单线程的。

---

## 五、总结

### 数据类型速查

| 类型 | 底层编码 | 最大元素 | 适用场景 |
|------|---------|---------|---------|
| String | int / embstr / raw | 512MB | 缓存、计数器、分布式锁 |
| List | quicklist（ziplist）| 2^32-1 | 消息队列、最新列表 |
| Hash | ziplist / hashtable | 2^32-1 | 对象存储、购物车 |
| Set | intset / hashtable | 2^32-1 | 标签、抽奖、交集/并集 |
| ZSet | ziplist / skiplist | 2^32-1 | 排行榜、延迟队列 |

### 命令分类

| 通用命令 | 作用 |
|---------|------|
| DEL key | 删除 key |
| EXISTS key | 判断是否存在 |
| EXPIRE key seconds | 设置过期时间 |
| TTL key | 查看剩余时间 |
| TYPE key | 查看类型 |

---

**下一篇：** [Redis（二）：持久化机制]({{< relref "post/redis-persistence" >}})
