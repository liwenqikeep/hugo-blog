---
title: "RabbitMQ（五）：集群与高可用"
date: 2019-03-10
draft: false
categories: ["消息队列"]
tags: ["RabbitMQ", "集群", "镜像队列", "Quorum队列", "高可用"]
toc: true
---

## 前言

RabbitMQ 集群通过多个节点协同工作，提供**高可用**和**水平扩展**能力。集群模式包括**普通集群**（元数据复制）、**镜像队列**（消息复制）和 **Quorum 队列**（Raft 共识）。

<!--more-->

## 一、集群架构

### 1.1 普通集群

```
普通集群（默认）：
  只同步元数据（交换机、绑定关系、队列元数据）
  消息数据只存储在声明队列的节点上

  Node A                    Node B
  ┌────────────────┐       ┌────────────────┐
  │ Queue A (主)    │       │ Queue A (指针)  │
  │ Queue B (指针)  │       │ Queue B (主)    │
  │ Exchange       │       │ Exchange       │
  │ Binding        │       │ Binding        │
  └────────────────┘       └────────────────┘
         │                        │
         └────────┬───────────────┘
                  │
             ┌────▼─────┐
             │  Erlang   │
             │  内部通信  │
             └──────────┘

特点：
- 队列数据只在一个节点上（声明该队列的节点）
- 其他节点持有指向该队列的指针
- 访问非本节点队列时，内部转发
- 队列所在节点宕机 → 该队列数据丢失
```

### 1.2 镜像队列（Mirrored Queue）

```
镜像队列将数据复制到集群中的多个节点。

  Node A                    Node B
  ┌────────────────┐       ┌────────────────┐
  │ Queue A (Master)│──────▶│ Queue A (Mirror)│
  │ 消息 1,2,3     │       │ 消息 1,2,3     │
  │ Exchange       │       │ Exchange       │
  └────────────────┘       └────────────────┘
         │                        │
         ▼                        ▼
       Producer                Consumer
      （写 Master）           （可读任意副本）

Master 宕机 → 自动提升一个 Mirror 为 Master
```

---

## 二、集群搭建

### 2.1 基础配置

```bash
# 节点1（node1）
# /etc/rabbitmq/rabbitmq.conf
cluster_formation.peer_discovery_backend = rabbit_peer_discovery_classic_config
cluster_formation.classic_config.nodes.1 = rabbit@node1
cluster_formation.classic_config.nodes.2 = rabbit@node2
cluster_formation.classic_config.nodes.3 = rabbit@node3

# Erlang Cookie 必须一致
# /var/lib/rabbitmq/.erlang.cookie
# 将相同的 cookie 文件复制到所有节点

# 启动并加入集群
rabbitmq-server -detached
rabbitmqctl stop_app
rabbitmqctl reset
rabbitmqctl join_cluster rabbit@node1
rabbitmqctl start_app

# 查看集群状态
rabbitmqctl cluster_status
```

### 2.2 配置镜像队列

```bash
# 方式一：通过策略（推荐，动态生效）
# 将所有队列设置为镜像队列（ha-all：复制到所有节点）
rabbitmqctl set_policy ha-all "^" '{"ha-mode":"all"}' --apply-to queues

# 在指定节点上镜像
rabbitmqctl set_policy ha-two "^important\." '{"ha-mode":"exactly","ha-params":2}'

# 按节点组镜像
rabbitmqctl set_policy ha-nodes "^critical\." \
    '{"ha-mode":"nodes","ha-params":["rabbit@node1","rabbit@node2"]}'
```

**ha-mode 三种模式：**

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| all | 所有节点都有镜像 | 小集群（≤ 5 节点）|
| exactly | 指定数量的副本 | 大集群 |
| nodes | 指定节点上有镜像 | 指定高性能节点 |

---

## 三、Quorum 队列

### 3.1 说明

```
Quorum 队列是 RabbitMQ 3.8+ 引入的基于 Raft 共识的队列类型。
相比镜像队列，Quorum 队列提供更强的数据一致性。

核心特点：
- 基于 Raft 共识算法
- 强制数据复制到多数节点后才确认
- 自动处理节点故障和 leader 选举
- 不支持排他队列和临时队列
```

### 3.2 使用 Quorum 队列

```bash
# 通过策略将所有队列改为 Quorum 类型
rabbitmqctl set_policy quorum "^" \
    '{"queue-type":"quorum","delivery-limit":10}' \
    --apply-to queues
```

```java
@Configuration
public class QuorumConfig {
    
    @Bean
    public Queue quorumQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-queue-type", "quorum");           // Quorum 队列
        args.put("x-quorum-initial-group-size", 3);  // 初始节点数
        args.put("x-delivery-limit", 10);             // 最大投递次数
        return QueueBuilder.durable("queue.quorum")
                .withArguments(args)
                .build();
    }
}
```

### 3.3 镜像队列 vs Quorum 队列

| 对比 | 镜像队列 | Quorum 队列 |
|------|---------|-------------|
| 引入版本 | 2.6+ | 3.8+ |
| 一致性算法 | GM（Gossip）| Raft |
| 数据一致性 | 最终一致 | 强一致 |
| 性能 | 高 | 略低 |
| 适用版本 | 3.7 及以下 | 3.8+ 推荐 |

---

## 四、集群管理

### 4.1 常用命令

```bash
# 查看集群状态
rabbitmqctl cluster_status

# 查看队列分布
rabbitmqctl list_queues name node pid messages

# 添加节点
rabbitmqctl join_cluster rabbit@existing_node

# 离开集群（踢出节点）
rabbitmqctl forget_cluster_node rabbit@node3

# 节点下线维护
rabbitmqctl stop_app
# 维护完成后
rabbitmqctl start_app

# 更改节点类型（disc/ram）
rabbitmqctl change_cluster_node_type disc
```

### 4.2 集群监控

```bash
# 查看集群状态
rabbitmqctl cluster_status

# 查看节点运行状态
rabbitmqctl status

# 查看队列状态
rabbitmqctl list_queues name messages consumers state

# 查看连接
rabbitmqctl list_connections

# 查看通道
rabbitmqctl list_channels
```

**集群健康的关键指标：**

| 指标 | 说明 | 告警条件 |
|------|------|---------|
| 节点状态 | 运行/停止 | 节点离线 |
| 队列状态 | running/idle | 队列不可用 |
| 镜像同步状态 | sync/unsync | 同步延迟 |
| 内存使用 | 节点内存 | > 70% |
| 磁盘空间 | 节点磁盘 | > 80% |
| 文件描述符 | 系统文件句柄 | > 80% |

---

## 五、集群规划

### 5.1 节点类型

```bash
# RAM 节点：元数据存储在内存中，性能好
# Disk 节点：元数据存储在磁盘上，可靠性高
# 至少两个 Disk 节点，集群中 Disk 节点数 > N/2

# 推荐配置：3 个节点的集群
# - node1（Disk）
# - node2（Disk）
# - node3（RAM 或 Disk）
```

### 5.2 最小生产集群

```
3 节点集群（推荐）：
  node1 (Disk) ────  ──── node2 (Disk)
       │                       │
       └──────────┬────────────┘
                  │
             node3 (Disk)
             
Client 通过 Load Balancer 连接到集群
队列设置为 Quorum 或镜像队列
```

### 5.3 多机房的注意事项

```
跨机房集群的挑战：
1. 网络延迟：节点间心跳超时
2. 网络分区：可能导致脑裂

解决方案：
1. 使用 Federation Plugin（联邦队列）
2. 使用 Shovel Plugin（跨集群消息同步）
3. 每个机房独立集群，通过插件互通
```

---

## 六、总结

### 集群模式选择

| 模式 | 数据复制 | 一致性 | 推荐版本 |
|------|---------|--------|---------|
| 普通集群 | 无（仅元数据）| 最终一致 | 不推荐生产 |
| 镜像队列 | 全量复制 | 最终一致 | 3.7 及以下 |
| Quorum 队列 | Raft 多数复制 | 强一致 | 3.8+ 推荐 |

### 集群部署建议

```
1. 最少 3 个节点（推荐奇数）
2. 所有节点使用 Disk 类型
3. 使用 Quorum 队列（3.8+）或镜像队列（3.7-）
4. Erlang Cookie 保持一致
5. 前端加上负载均衡器（HAProxy / Nginx）
6. 监控节点状态和队列同步状态
```

---

**上一篇：** [RabbitMQ（四）：高级特性]({{< relref "post/rabbitmq-advanced-features" >}})

**系列索引：**
- [RabbitMQ（一）：核心概念与架构]({{< relref "post/rabbitmq-architecture" >}})
- [RabbitMQ（二）：五种消息模型]({{< relref "post/rabbitmq-message-models" >}})
- [RabbitMQ（三）：可靠性保证]({{< relref "post/rabbitmq-reliability" >}})
- [RabbitMQ（四）：高级特性]({{< relref "post/rabbitmq-advanced-features" >}})
- [RabbitMQ（五）：集群与高可用]({{< relref "post/rabbitmq-cluster" >}})
