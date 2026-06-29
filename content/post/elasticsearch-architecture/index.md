---
title: "Elasticsearch（一）：核心概念与架构"
date: 2018-08-13
draft: false
categories: ["ElasticSearch"]
tags: ["Elasticsearch", "倒排索引", "集群", "节点", "REST API"]
toc: true
---

## 前言

Elasticsearch（简称 ES）是一个基于 Lucene 的分布式搜索和分析引擎。它通过 RESTful API 隐藏了 Lucene 的复杂性，提供了**近实时搜索**、**分布式集群**、**水平扩展**等能力。

本文从最基础的概念出发，覆盖 ES 的架构、倒排索引原理和核心数据模型。

<!--more-->

## 一、ES 的核心概念

### 1.1 与传统数据库的类比

| Elasticsearch | 关系型数据库 |
|---------------|-------------|
| Index（索引）| Database（数据库）|
| Type（类型，7.x 废弃）| Table（表）|
| Document（文档）| Row（行）|
| Field（字段）| Column（列）|
| Mapping（映射）| Schema（表结构）|
| Query DSL | SQL |

### 1.2 基本概念

| 概念 | 说明 |
|------|------|
| **Index** | 文档的集合，类似数据库 |
| **Document** | JSON 格式的数据行，ES 中最小的数据单元 |
| **Field** | 文档中的字段，每个字段有类型（text/keyword/integer 等）|
| **Mapping** | 字段类型的定义（类似 Schema）|
| **Node** | ES 实例，一个节点就是一个 Java 进程 |
| **Cluster** | 多个节点的集合 |
| **Shard** | 分片，索引的物理拆分单元 |
| **Replica** | 副本分片，用于高可用 |
| **Segment** | 分段，Lucene 内部存储单元 |

---

## 二、倒排索引

ES 的快速搜索能力来源于**倒排索引**，这是 Lucene 的核心数据结构。

### 2.1 正排索引 vs 倒排索引

```
正排索引（文档 → 关键词）：
┌────────┬────────────────────────┐
│ 文档 ID │ 内容                    │
├────────┼────────────────────────┤
│ 1      │ Elasticsearch 是搜索引擎 │
│ 2      │ Java 是一种编程语言      │
│ 3      │ ES 使用倒排索引           │
└────────┴────────────────────────┘

倒排索引（关键词 → 文档）：
┌──────────┬──────────────┐
│ 关键词    │ 文档 ID 列表  │
├──────────┼──────────────┤
│ elasticsearch │ [1]         │
│ 搜索引擎  │ [1]          │
│ java     │ [2]          │
│ 编程语言  │ [2]          │
│ 倒排索引  │ [3]          │
└──────────┴──────────────┘

搜索"搜索引擎"时：
→ 在倒排索引中查找"搜索引擎"
→ 立即找到文档 1
→ O(1) 时间复杂度
```

### 2.2 倒排索引的构成

```
倒排索引由三部分组成：

1. Term Dictionary（词项字典）
   所有关键词的排序列表，用于二分查找
   保存在内存（FST 结构，有限状态转换器）

2. Posting List（倒排列表）
   包含该关键词的文档 ID 列表
   使用差值编码 + 变长编码压缩

3. Term Index（词项索引）
   Term Dictionary 的前缀索引
   用于快速定位 Term Dictionary 中的位置
```

### 2.3 分词过程

```
文档写入 ES 时的分词过程：

"Elasticsearch is a search engine"
    │
    ▼
Character Filters（字符过滤）
  └── 去除 HTML 标签、转换特殊字符
    │
    ▼
Tokenizer（分词器）
  └── 按空格/标点切分为 tokens
    │
    ▼
Token Filters（词元过滤）
  ├── 转小写 → "elasticsearch"
  ├── 停用词过滤 → 去除 "a"
  └── 词干提取 → 无变化
    │
    ▼
倒排索引写入
```

---

## 三、集群架构

### 3.1 节点类型

```
ES 集群中的节点角色：

Master Node（主节点）：
  ├── 负责集群元数据管理
  ├── 索引创建/删除
  ├── 节点上下线处理
  └── 不存储数据（推荐专用主节点）

Data Node（数据节点）：
  ├── 存储分片数据
  ├── 执行 CRUD 和搜索操作
  └── 消耗大量 CPU、内存、磁盘

Coordinating Node（协调节点）：
  ├── 接收客户端请求
  ├── 分发请求到数据节点
  └── 合并结果返回客户端

Ingest Node（预处理节点）：
  └── 文档写入前的管道预处理
```

### 3.2 分片与副本

```
每个 Index 被拆分为多个 Shard（分片）。
每个 Shard 是一个完整的 Lucene 实例。

Index: orders（5 个主分片，2 个副本分片）

┌─────────────────────────────────────────────┐
│  orders                                      │
│                                              │
│  Shard 0 (Primary)    Shard 0 (Replica)      │
│  Shard 1 (Primary)    Shard 1 (Replica)      │
│  Shard 2 (Primary)    Shard 2 (Replica)      │
│  Shard 3 (Primary)    Shard 3 (Replica)      │
│  Shard 4 (Primary)    Shard 4 (Replica)      │
└─────────────────────────────────────────────┘

主分片数：索引创建时指定，不可修改
副本分片数：可动态调整

分片数 = 数据量 ÷ 单分片容量（建议 20-50GB/分片）
```

### 3.3 分片路由

```
文档写入/查询时，确定分片位置的算法：

shard = hash(routing) % number_of_primary_shards

默认 routing = 文档 _id
自定义 routing = 指定值（如同一用户的文档路由到同一分片）

路由规则：
1. 计算 hash(routing)
2. 对主分片数取模
3. 确认主分片位置
```

---

## 四、REST API 基础

### 4.1 索引操作

```bash
# 创建索引
PUT /orders
{
  "settings": {
    "number_of_shards": 5,
    "number_of_replicas": 1
  }
}

# 查看索引
GET /orders

# 查看所有索引
GET /_cat/indices?v

# 删除索引
DELETE /orders

# 关闭/打开索引
POST /orders/_close
POST /orders/_open
```

### 4.2 文档操作

```bash
# 创建文档（指定 ID）
PUT /orders/_doc/1
{
  "order_id": 1001,
  "user_name": "Tom",
  "amount": 99.9,
  "status": "paid",
  "create_time": "2024-01-01"
}

# 创建文档（自动生成 ID）
POST /orders/_doc
{
  "order_id": 1002,
  "user_name": "Jerry"
}

# 查询文档
GET /orders/_doc/1

# 更新文档
POST /orders/_doc/1/_update
{
  "doc": {
    "status": "shipped"
  }
}

# 删除文档
DELETE /orders/_doc/1

# 批量操作
POST /_bulk
{"index":{"_index":"orders","_id":3}}
{"order_id":1003,"amount":200}
{"index":{"_index":"orders","_id":4}}
{"order_id":1004,"amount":300}
```

### 4.3 搜索基础

```bash
# 简单搜索
GET /orders/_search?q=user_name:Tom

# Query DSL（查询表达式）
GET /orders/_search
{
  "query": {
    "match": {
      "user_name": "Tom"
    }
  }
}

# 复合查询
GET /orders/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "status": "paid" } }
      ],
      "filter": [
        { "range": { "amount": { "gte": 100 } } }
      ]
    }
  }
}
```

---

## 五、ES 的写流程

```
写入一条文档时：

1. 客户端请求到达协调节点
2. 协调节点计算 shard = hash(_id) % primary_shards
3. 请求转发到主分片所在的节点
4. 主分片写入 Lucene 并生成 translog
5. 并行转发到副本分片
6. 副本分片确认写入完成
7. 主分片向协调节点返回成功
8. 协调节点向客户端返回成功

写入延迟主要由：
- refresh_interval（默认 1秒）控制可见性
- translog fsync 策略控制持久性
```

---

## 六、总结

### ES 核心概念速查

| 概念 | 一句话 |
|------|--------|
| Index | 类似数据库 |
| Document | 类似行，JSON 格式 |
| Shard | 索引的物理分片 |
| Replica | 分片的副本，高可用 |
| Segment | Lucene 内部存储段 |
| Inverted Index | 倒排索引，实现快速搜索 |

### 与关系型数据库的对比

| 场景 | 关系型数据库 | Elasticsearch |
|------|-------------|---------------|
| 事务支持 | ACID | 最终一致性 |
| 关联查询 | JOIN | Nested/父子文档 |
| 搜索性能 | 模糊搜索慢 | 全文搜索极快 |
| 聚合分析 | GROUP BY 慢 | 聚合极快 |
| 数据量 | 百万级 | 十亿级+ |

---

**下一篇：** [Elasticsearch（二）：索引与映射]({{< relref "post/elasticsearch-mapping" >}})
