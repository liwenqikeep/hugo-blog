---
title: "分布式系统设计（二）：分布式理论 CAP/BASE/一致性"
date: 2018-08-25
draft: false
categories: ["分布式"]
tags: ["分布式系统", "CAP", "BASE", "一致性", "PACELC"]
toc: true
---

## 前言

分布式系统的核心挑战在于：**网络不可靠**、**时钟不一致**、**节点可能故障**。CAP 定理和 BASE 理论是指导我们在这些约束下做出设计取舍的基本原则。

<!--more-->

## 一、CAP 定理

### 1.1 三个特性

```
C（Consistency，一致性）：所有节点在同一时刻看到的数据是一样的
A（Availability，可用性）：每个请求都能获得一个（非错误的）响应
P（Partition Tolerance，分区容错性）：系统在节点间网络断开时仍能正常工作

CAP 定理：在网络分区（P）必然发生的前提下，你只能在 C 和 A 之间做选择。
```

### 1.2 为什么不能同时满足

```
正常情况：CP 和 AP 都可以达到
         ┌──────┐
         │ Node1 │←────→│ Node2 │
         └──────┘      └──────┘

网络分区时（P 发生）：
         ┌──────┐   断网   ┌──────┐
         │ Node1 │←─ ─ ─→│ Node2 │
         └──────┘        └──────┘

选择 CP（等待一致）：
  Node1 写入新数据，但无法同步到 Node2
  Node2 拒绝所有读请求（等待恢复）
  → 一致性 OK，可用性降低

选择 AP（继续服务）：
  Node1 和 Node2 各自独立服务
  Node1 写入新数据，Node2 返回旧数据
  → 可用性 OK，一致性降低
```

### 1.3 实际系统的 CAP 选择

| 系统 | 选择 | 说明 |
|------|------|------|
| 关系型数据库 | CA | 不考虑分区时，保证一致和可用 |
| ZooKeeper / etcd | CP | 强一致，网络分区时不可用 |
| Eureka | AP | 可用性优先，允许不一致 |
| Redis Cluster | AP | 可用性优先（异步复制）|
| Kafka | AP | 可用性优先（最终一致）|

> 注意：P（分区容错）是必须选的——分布式系统一定会有网络分区。

---

## 二、BASE 理论

BASE 是 CAP 中 AP 方向的延伸。

```
BA（Basically Available，基本可用）：系统在出现故障时允许丢失部分功能
S（Soft State，软状态）：允许系统处于中间状态
E（Eventually Consistent，最终一致性）：经过一段时间后，数据最终达到一致

BASE 的核心思想：放弃强一致性，追求最终一致性。
```

### CAP vs BASE

```
CAP：Consistency or Availability（两者选一）
BASE：Basically Available + Eventually Consistent

CP 系统（ZooKeeper）：强一致，可用性略低
AP 系统（大部分互联网应用）：高可用，最终一致
```

---

## 三、一致性模型

### 3.1 一致性模型光谱

```
强一致性 ←────────────────────────────→ 弱一致性
    │           │              │
    ▼           ▼              ▼
   线性一致性   顺序一致性    因果一致性    最终一致性
 (Linearizability) (Sequential) (Causal)  (Eventual)
    │                                  │
    │                                  │
 ZooKeeper                          DNS/CDN
 etcd                              大部分 Web 缓存
                                    MySQL 主从（延时）
```

| 模型 | 说明 | 性能代价 |
|------|------|:-------:|
| **线性一致性** | 所有操作好像在一个单核 CPU 上执行 | 极高 |
| **顺序一致性** | 每个节点内部操作有序，全局不一定 | 高 |
| **因果一致性** | 有因果关系的操作有序 | 中 |
| **最终一致性** | 没有新写入后，数据最终会一致 | 低 |

### 3.2 强一致性的代价

```
ZooKeeper 写入过程（强一致）：

Leader 收到写请求
  ├── 发送 Proposal 到所有 Follower
  ├── 等待多数 Follower 确认
  └── 提交 → 返回成功

→ 写延迟取决于最慢的 Follower
→ 如果有节点宕机，需要等待超时
→ 性能上限远低于 AP 系统
```

---

## 四、PACELC 模型

PACELC 是 CAP 的扩展，考虑了**没有分区时的取舍**。

```
PACELC 模型：

If P（分区发生时）
  ├── 选 C（一致）：等待分区恢复
  └── 选 A（可用）：继续服务，不一致

Else（正常时）
  ├── 选 L（低延迟）：复制到部分节点后返回
  └── 选 C（一致）：复制到全部节点后返回
```

```
DynamoDB 的 PACELC 选择：
  P → A（分区时继续服务）
  E → L（正常时低延迟优先）

ZooKeeper 的 PACELC 选择：
  P → C（分区时等待一致）
  E → C（正常时也等待一致）
```

---

## 五、一致性级别在实践中的应用

```java
// 实际系统中的一致性选择

// 场景 1：支付系统（需要强一致性）
// 使用事务 + 数据库主库
@Transactional
public void transfer(Long fromId, Long toId, BigDecimal amount) {
    // 必须在同一个数据库事务中执行
    accountDao.decrease(fromId, amount);
    accountDao.increase(toId, amount);
}

// 场景 2：社交 Feed（最终一致性即可）
// 用户发帖 → 写入 MQ → 异步同步到粉丝 Feed 列表
public void publishPost(Long userId, String content) {
    Post post = postDao.insert(userId, content);
    // 发消息通知粉丝，粉丝可能延迟看到
    mq.send(TOPIC_FEED, new FeedMessage(userId, post.getId()));
    return post;  // 立即返回，不等待粉丝同步
}

// 场景 3：库存扣减（严格一致性）
// 使用 Redis 原子操作 + 数据库最终一致
public boolean decreaseStock(Long skuId, int count) {
    // 缓存层严格扣减（防止超卖）
    Long remain = redisTemplate.opsForValue()
            .decrement("stock:" + skuId, count);
    if (remain < 0) {
        redisTemplate.opsForValue().increment("stock:" + skuId, count);
        return false;  // 库存不足
    }
    // 异步同步到数据库
    mq.send(TOPIC_STOCK, new StockMessage(skuId, count));
    return true;
}
```

---

## 六、总结

### 核心理论速查

| 理论 | 核心观点 | 适用场景 |
|------|---------|---------|
| CAP | P 必须选，C 和 A 二选一 | 所有分布式系统 |
| BASE | 最终一致性 + 基本可用 | 互联网高并发场景 |
| PACELC | 分区选 CA，正常选 LC | 精细化设计取舍 |

### 实际选型建议

```
数据一致性要求极高 → CP 系统（ZooKeeper、etcd）
  如：配置中心、分布式锁、服务发现

可用性要求极高 → AP 系统 + 最终一致
  如：缓存、用户 Feed、计数

支付/库存 → CP（事务）+ AP（缓存）结合
  如：缓存扣减 + 异步对账
```

**相关阅读：**
- [分布式系统设计（一）：设计思想与方法论]({{< relref "post/distributed-system-design-principles" >}})
- [分布式系统设计（三）：一致性算法 Paxos/Raft]({{< relref "post/distributed-system-consensus-algorithm" >}})
