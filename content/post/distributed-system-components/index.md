---
title: "分布式系统设计（四）：分布式组件"
date: 2018-08-29
draft: false
categories: ["分布式"]
tags: ["分布式系统", "分布式ID", "分布式锁", "服务发现", "分布式配置"]
toc: true
---

## 前言

在分布式系统中，有一些通用的基础组件需要自行设计或选型：**分布式 ID**、**分布式锁**、**服务发现**、**分布式配置**。这些组件看似简单，但涉及一致性和高可用的权衡。

<!--more-->

## 一、分布式 ID

### 1.1 需求

```
全局唯一        — 不能重复
趋势递增       — MySQL B+ 树插入性能好
高可用低延迟   — 生成 ID 不能成为瓶颈
```

### 1.2 常见方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| UUID | 本地生成，性能好 | 字符串，不是递增 |
| 数据库自增 | 简单，唯一 | 有单点瓶颈 |
| Redis INCR | 性能好 | Redis 持久化问题 |
| 雪花算法（Snowflake）| 趋势递增，本地生成 | 依赖时钟 |
| 美团 Leaf | 号段模式 | 依赖 DB |

### 1.3 雪花算法

```java
public class SnowflakeIdGenerator {
    
    // 位数分配：1bit(符号) + 41bit(时间戳) + 10bit(机器ID) + 12bit(序列号)
    // 总共 64 位，正好一个 long
    
    private final long workerId;        // 机器ID（0-1023）
    private final long datacenterId;    // 数据中心ID（0-31）
    private long sequence = 0L;         // 序列号
    private long lastTimestamp = -1L;
    
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        
        if (timestamp < lastTimestamp) {
            // 时钟回拨问题处理：等待或抛异常
            throw new RuntimeException("Clock moved backwards");
        }
        
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 4095;  // 同一毫秒内序列号递增
            if (sequence == 0) {
                // 当前毫秒序列号用完，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        
        lastTimestamp = timestamp;
        
        return ((timestamp - 1288834974657L) << 22)   // 时间戳部分
             | (datacenterId << 17)                     // 数据中心
             | (workerId << 12)                         // 机器ID
             | sequence;                                // 序列号
    }
}
```

---

## 二、分布式锁

### 2.1 基于 Redis 的分布式锁

```java
// 加锁
public boolean tryLock(String key, String value, long expireSeconds) {
    return redisTemplate.opsForValue()
            .setIfAbsent(key, value, expireSeconds, TimeUnit.SECONDS);
}

// 解锁（使用 Lua 保证原子性）
public boolean unlock(String key, String value) {
    String script = "if redis.call('get', KEYS[1]) == ARGV[1] " +
                    "then return redis.call('del', KEYS[1]) " +
                    "else return 0 end";
    return redisTemplate.execute(
            new DefaultRedisScript<>(script, Long.class),
            List.of(key), value) > 0;
}
```

### 2.2 基于 ZooKeeper 的分布式锁

```java
// ZooKeeper 的临时顺序节点实现锁
// 优点：无超时问题，客户端断开后自动释放
public class ZkDistributedLock {
    
    private final ZooKeeper zk;
    private final String lockPath;
    private String currentPath;  // 当前节点路径
    
    public boolean tryLock() {
        // 创建临时顺序节点
        currentPath = zk.create(lockPath + "/lock-", 
                new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL);
        
        // 获取所有子节点
        List<String> children = zk.getChildren(lockPath, false);
        Collections.sort(children);
        
        // 如果自己的节点是最小的，获得锁
        String currentNode = currentPath.substring(currentPath.lastIndexOf("/") + 1);
        if (currentNode.equals(children.get(0))) {
            return true;
        }
        
        // 监听前一个节点
        String prevNode = children.get(children.indexOf(currentNode) - 1);
        CountDownLatch latch = new CountDownLatch(1);
        zk.exists(lockPath + "/" + prevNode, event -> latch.countDown());
        latch.await();  // 等待前一个节点释放
        
        return true;
    }
    
    public void unlock() {
        zk.delete(currentPath, -1);
    }
}
```

### 2.3 Redis vs ZooKeeper 锁

| 对比 | Redis | ZooKeeper |
|------|-------|-----------|
| 性能 | 极高 | 高 |
| 自动释放 | 需要设置超时 | 客户端断开自动释放 |
| 可靠性 | AP 系统，主从切换可能丢锁 | CP 系统，强一致 |
| 实现复杂度 | 简单 | 复杂 |

---

## 三、服务发现

### 3.1 客户端发现 vs 服务端发现

```
客户端发现（Client-Side Discovery）：
  客户端直接从注册中心获取服务地址列表
  代表：Eureka、Consul、ZooKeeper
  优点：架构简单，无需额外代理
  缺点：客户端集成发现逻辑

服务端发现（Server-Side Discovery）：
  客户端请求负载均衡器，由负载均衡器转发
  代表：Kubernetes Service + kube-proxy
  优点：客户端无需感知服务地址
  缺点：需要额外的代理组件
```

### 3.2 注册中心对比

| 特性 | Eureka | ZooKeeper | Consul | Nacos |
|------|--------|-----------|--------|-------|
| CAP 选择 | AP | CP | CP | AP/CP |
| 健康检查 | 心跳 | 心跳 | TCP/HTTP/gRPC | 心跳 |
| 一致性 | 最终一致 | 强一致 | 强一致 | 可选 |
| 自我保护 | ✅ | ❌ | ❌ | ✅ |
| 云原生 | Netflix | Apache | HashiCorp | 阿里 |

---

## 四、分布式配置

### 4.1 配置中心的功能

```
1. 配置集中管理
   所有环境的配置在一个地方管理

2. 配置动态刷新
   修改配置后，应用无需重启即可生效

3. 版本管理
   配置变更可追溯、可回滚

4. 权限控制
   不同环境、不同应用的配置隔离
```

### 4.2 常用配置中心

| 配置中心 | 存储后端 | 动态刷新 | 推荐场景 |
|---------|---------|:-------:|---------|
| Spring Cloud Config | Git | 需配合 Bus | Spring 生态 |
| Apollo（携程）| MySQL | ✅ | 企业级配置管理 |
| Nacos（阿里）| MySQL + 本地 | ✅ | 云原生 + 配置 |
| etcd | Raft | ✅ | Kubernetes |  

### 4.3 Apollo 配置模型

```
应用配置的结构：
AppId → Namespace → Key-Value

Namespace：
- application（默认）：应用配置
- datasource：数据源配置
- 自定义：用户自定义分类

配置刷新原理：
1. 应用启动时从 Config Service 拉取配置
2. 建立长轮询（Long Polling）连接
3. 配置变更时推送通知
4. 应用收到通知后拉取最新配置
5. 通过 Spring 的 Environment 刷新机制生效
```

---

## 五、总结

### 组件选型速查

| 组件 | 推荐方案 | 场景 |
|------|---------|------|
| 分布式 ID | 雪花算法 / Leaf | 订单 ID、用户 ID |
| 分布式锁 | Redis / ZooKeeper | 秒杀、定时任务 |
| 服务发现 | Nacos / Consul | 微服务注册发现 |
| 配置中心 | Apollo / Nacos | 配置动态管理 |

**相关阅读：**
- [分布式系统设计（三）：一致性算法 Paxos/Raft]({{< relref "post/distributed-system-consensus-algorithm" >}})
- [分布式系统设计（五）：高可用与流量治理]({{< relref "post/distributed-system-high-availability" >}})
