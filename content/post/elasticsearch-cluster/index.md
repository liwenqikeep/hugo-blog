---
title: "Elasticsearch（四）：集群与高可用"
date: 2018-08-19
draft: false
categories: ["ElasticSearch"]
tags: ["Elasticsearch", "集群", "节点", "发现机制", "故障恢复", "高可用"]
toc: true
---

## 前言

Elasticsearch 天生就是分布式的，一个集群由多个节点组成。理解集群的工作机制——**节点发现**、**Master 选举**、**分片分配**以及**故障恢复**——对于运维和使用 ES 至关重要。

<!--more-->

## 一、集群搭建

### 1.1 基础配置

```yaml
# elasticsearch.yml

# 集群名称（所有节点必须一致）
cluster.name: my-es-cluster

# 节点名称（每个节点唯一）
node.name: node-1

# 节点角色
node.roles: [ master, data, ingest ]

# 网络配置
network.host: 0.0.0.0
http.port: 9200
transport.port: 9300

# 发现配置
discovery.seed_hosts:
  - node1:9300
  - node2:9300
  - node3:9300
cluster.initial_master_nodes: ["node-1", "node-2", "node-3"]

# 路径配置
path.data: /data/elasticsearch/data
path.logs: /data/elasticsearch/logs

# 内存配置
bootstrap.memory_lock: true
```

### 1.2 最小集群拓扑

```
推荐的最小生产集群：3 个专用 Master + 3 个 Data 节点

节点 1（Master）   ─┐
节点 2（Master）   ─┤ 集群管理
节点 3（Master）   ─┘

节点 4（Data）     ─┐
节点 5（Data）     ─┤ 数据存储与查询
节点 6（Data）     ─┘

Master 专用节点配置：
node.roles: [ master ]
node.roles: [ master ]   ← 不存储数据，不处理搜索

Data 节点配置：
node.roles: [ data, ingest, ml ]
```

---

## 二、节点发现与选举

### 2.1 发现机制

```
节点启动时通过 discovery.seed_hosts 发现集群。

发现过程：
1. 节点启动，连接到 seed_hosts 中的节点
2. 获取集群状态（cluster state）
3. 加入集群，同步元数据

传输协议（Transport）：
数据节点之间通过 9300 端口通信
使用 Netty 作为网络框架
```

### 2.2 Master 选举

```
当集群中没有可用 Master 时触发选举。

选举规则（基于 Zen Discovery）：
1. 从候选节点中选举
2. 比较 node.id 的字典序
3. 最小的 node.id 胜出

候选节点：配置了 master 角色的节点

避免脑裂的配置：
discovery.zen.minimum_master_nodes: N/2 + 1
# 7.x 后由 cluster.initial_master_nodes 替代
```

### 2.3 集群状态

```
集群状态（Cluster State）包含：
├── 集群级别的设置
├── 索引的元数据（Mapping、Setting）
├── 分片的路由信息
└── 节点列表

集群状态由 Master 节点维护
每次变更通过发布（publish）同步到所有节点
```

---

## 三、分片分配

### 3.1 分片平衡

```
ES 会自动在节点间平衡分片。

分配策略：
1. 基于磁盘容量（disk-based）
2. 基于分片数量（shard-based）
3. 自定义路由（awareness）

相关配置：
cluster.routing.allocation.balance.shard = 0.45   # 分片权重
cluster.routing.allocation.balance.disk = 0.55     # 磁盘权重
```

### 3.2 分片分配感知

```yaml
# 跨机架/可用区的分片分配

# 配置机架
node.attr.rack_id: rack-1

# 集群配置
cluster.routing.allocation.awareness.attributes: rack_id

# 这样主分片和副本分片会分配到不同的机架
# 防止单个机架故障导致数据全部丢失
```

### 3.3 强制只读

```bash
# 当磁盘使用率超过水位线时，ES 会将索引设为只读

# 水位线配置
cluster.routing.allocation.disk.watermark.low: "85%"     # 达到 85% 不再分配分片
cluster.routing.allocation.disk.watermark.high: "90%"    # 达到 90% 迁移分片
cluster.routing.allocation.disk.watermark.flood_stage: "95%"  # 达到 95% 强制只读

# 解除只读
PUT /my_index/_settings
{
  "index.blocks.read_only_allow_delete": null
}
```

---

## 四、故障恢复

### 4.1 节点故障

```
节点宕机后的恢复流程：

1. Master 检测到节点失联（监控心跳）
2. Master 重新分配该节点上的分片
3. 将丢失的主分片的副本提升为主分片
4. 在其他节点上创建新的副本分片
5. 集群恢复到绿色状态

影响：
- 如果只有副本分片丢失 → 不影响服务
- 如果主分片丢失 → 短暂不可用（提升副本为主分片）
```

### 4.2 集群健康状态

```bash
# 查看集群健康
GET /_cluster/health

{
  "status": "yellow",                  # green/yellow/red
  "timed_out": false,
  "number_of_nodes": 6,
  "number_of_data_nodes": 3,
  "active_primary_shards": 50,
  "active_shards": 100,
  "relocating_shards": 0,
  "initializing_shards": 0,
  "unassigned_shards": 0,
  "delayed_unassigned_shards": 0
}

# green：所有主分片和副本分片都正常
# yellow：主分片正常，但部分副本未分配（常见于单节点集群）
# red：主分片未分配（数据部分不可用）
```

### 4.3 分片修复

```bash
# 查看未分配的分片
GET /_cat/shards?v&h=index,shard,prirep,state,node
# state: UNASSIGNED 表示未分配

# 分配失败的原因
GET /_cluster/allocation/explain
{
  "index": "my_index",
  "shard": 0,
  "primary": true
}

# 手动重新分配
POST /_cluster/reroute
{
  "commands": [
    {
      "allocate_stale_primary": {
        "index": "my_index",
        "shard": 0,
        "node": "data-node-1",
        "accept_data_loss": true       // 接受可能的数据丢失
      }
    }
  ]
}
```

### 4.4 滚动重启

```bash
# ES 节点滚动重启流程：

# 1. 停用分片分配
PUT /_cluster/settings
{
  "transient": {
    "cluster.routing.allocation.enable": "none"
  }
}

# 2. 执行同步 flush
POST /_flush

# 3. 重启节点

# 4. 节点加入集群后，启用分片分配
PUT /_cluster/settings
{
  "transient": {
    "cluster.routing.allocation.enable": "all"
  }
}

# 5. 等待集群恢复绿色
GET /_cluster/health?wait_for_status=green

# 6. 继续重启下一个节点
```

---

## 五、监控与管理

### 5.1 常用 API

```bash
# 节点信息
GET /_cat/nodes?v
GET /_nodes/stats

# 索引信息
GET /_cat/indices?v
GET /_cat/shards?v

# 集群设置
GET /_cluster/settings
PUT /_cluster/settings { ... }

# 任务管理
GET /_cat/tasks
POST /_tasks/<task_id>/_cancel

# 热点线程（诊断慢查询）
GET /_nodes/hot_threads
```

### 5.2 监控指标

| 指标 | 说明 | 告警阈值 |
|------|------|---------|
| 集群状态 | green/yellow/red | 非 green 需关注 |
| 节点 JVM Heap | JVM 堆使用率 | > 85% |
| 搜索 QPS | 每秒查询数 | 按基线 |
| 索引速率 | 每秒索引数 | 按基线 |
| 拒绝数 | search/index 被拒绝数 | > 0 |
| 未分配分片 | 分片未分配数量 | > 0 |

---

## 六、总结

### 集群配置推荐

```yaml
# 生产集群最小配置（6 节点）
# 3 Master + 3 Data

# Master 节点
node.roles: [ master ]

# Data 节点
node.roles: [ data, ingest ]
```

### 集群健康速记

```
green  → 一切正常
yellow → 副本未分配（数据完整，容错降低）
red    → 主分片丢失（数据部分不可用）
```

---

**上一篇：** [Elasticsearch（三）：搜索与聚合]({{< relref "post/elasticsearch-search-aggregation" >}})

**下一篇：** [Elasticsearch（五）：性能优化]({{< relref "post/elasticsearch-performance" >}})
