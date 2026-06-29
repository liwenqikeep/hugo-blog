---
title: "Elasticsearch（五）：性能优化"
date: 2018-08-21
draft: false
categories: ["ElasticSearch"]
tags: ["Elasticsearch", "性能优化", "索引优化", "查询优化", "调优"]
toc: true
---

## 前言

ES 的性能优化是一个系统性工程，涉及**索引写入**、**搜索查询**、**内存配置**、**磁盘 IO** 等多个层面。本文从前面的原理出发，总结 ES 性能优化的最佳实践。

<!--more-->

## 一、索引写入优化

### 1.1 批量写入

```java
// 使用 Bulk API 批量写入（推荐每次 1-5MB）
BulkRequest bulkRequest = new BulkRequest();
for (int i = 0; i < 1000; i++) {
    bulkRequest.add(new IndexRequest("orders")
            .id(String.valueOf(i))
            .source("field", "value"));
}
BulkResponse response = client.bulk(bulkRequest, RequestOptions.DEFAULT);
```

```json
// REST API
POST /_bulk
{"index":{"_index":"orders"}}
{"order_id":1,"amount":100}
{"index":{"_index":"orders"}}
{"order_id":2,"amount":200}
```

### 1.2 调整 refresh 间隔

```json
// 批量导入时，加大 refresh 间隔
PUT /orders/_settings
{
  "refresh_interval": "30s"       // 默认 1s，导入时可设为 -1 或 30s
}

// 导入完成后恢复
PUT /orders/_settings
{
  "refresh_interval": "1s"
}
```

### 1.3 调整 translog

```json
// 批量导入时，异步刷 translog
PUT /orders/_settings
{
  "index": {
    "translog": {
      "durability": "async",      // 异步写入
      "sync_interval": "5s"       // 每 5 秒同步一次
    }
  }
}
```

### 1.4 禁用副本

```json
// 批量导入前，设置副本数为 0
PUT /orders/_settings
{
  "number_of_replicas": 0       // 导入时无副本，导入完成后恢复
}

// 导入完成后恢复副本
PUT /orders/_settings
{
  "number_of_replicas": 1
}
```

---

## 二、搜索性能优化

### 2.1 使用 filter 代替 query

```json
// ❌ 不需要评分的场景用了 query
{
  "query": {
    "term": { "status": "paid" }
  }
}

// ✅ 使用 filter（不计分，可缓存）
{
  "query": {
    "bool": {
      "filter": [
        { "term": { "status": "paid" } }
      ]
    }
  }
}
```

### 2.2 禁用不需要的字段评分

```json
// 不需要评分的字段，设置 norms: false
PUT /articles
{
  "mappings": {
    "properties": {
      "content": {
        "type": "text",
        "norms": false           // 禁评分，节省内存
      },
      "status": {
        "type": "keyword",
        "norms": false
      }
    }
  }
}
```

### 2.3 使用 keyword 替代 text

```json
// 精确匹配的字段使用 keyword 类型
{
  "mappings": {
    "properties": {
      "order_id": {
        "type": "keyword"       // 精确匹配，不分词
      }
    }
  }
}
```

### 2.4 避免深度分页

```bash
# ❌ 深度分页（from 太大）
GET /orders/_search
{
  "from": 10000,
  "size": 20
}

# ✅ Search After（推荐）
GET /orders/_search
{
  "size": 20,
  "sort": [{ "order_id": "desc" }],
  "search_after": [10000]
}
```

---

## 三、Mapping 优化

### 3.1 禁用不需要的功能

```json
PUT /logs
{
  "mappings": {
    "_source": {
      "enabled": false            // 不需要 source 可禁用
    },
    "properties": {
      "message": {
        "type": "text",
        "norms": false,
        "index_options": "docs",   // 只记录 doc，不记录词频/位置
        "doc_values": false        // 不需要聚合/排序可禁用
      },
      "timestamp": {
        "type": "date",
        "doc_values": true,
        "index": false             // 只存储，不索引
      }
    }
  }
}
```

### 3.2 合理选择字段类型

```json
// 数值类型选择：byte < short < integer < long
"age":     { "type": "byte" }      // 1 字节
"count":   { "type": "integer" }   // 4 字节
"price":   { "type": "scaled_float", "scaling_factor": 100 }  // 存储整数节省空间

// 字符串：精确匹配用 keyword
"status":  { "type": "keyword" }
```

---

## 四、JVM 与内存优化

### 4.1 堆内存配置

```yaml
# elasticsearch.yml

# 堆内存大小（不超过物理内存的 50%，不超过 32GB）
# config/jvm.options
-Xms16g
-Xmx16g

# 推荐堆内存大小
# 64GB 物理内存：分配 31GB（不超过 32GB 对象指针压缩阈值）
# 32GB 物理内存：分配 16GB
# 16GB 物理内存：分配 8-10GB
```

### 4.2 文件缓存

```
ES 大量依赖操作系统的文件系统缓存（PageCache）。

建议：
- 预留物理内存的 50% 给 OS 做 PageCache
- ES 的堆内存不要超过物理内存的 50%
- 在 Linux 上使用 swappiness=1

配置：
# /etc/sysctl.conf
vm.swappiness = 1
vm.max_map_count = 262144
```

### 4.3 线程池配置

```yaml
# 通用线程池（无需手动调整）
thread_pool:
  search:
    size: 80              # 搜索线程数：CPU 核数 × 3
    queue_size: 1000      # 队列大小
  write:
    size: 40              # 写入线程数：CPU 核数 × 2
    queue_size: 10000
```

---

## 五、磁盘与存储优化

### 5.1 使用 SSD

```
ES 对磁盘 IO 要求高，建议使用 SSD。
磁盘性能直接影响索引和搜索的延迟。

SATA HDD  → 适合冷数据归档
SATA SSD  → 普通场景
NVMe SSD  → 高性能场景
```

### 5.2 强制合并

```json
// 对于不再写入的索引，执行 force merge 减少 segment 数
POST /my_index/_forcemerge?max_num_segments=1

// 合并为 1 个 segment，搜索最快
// 合并期间 IO 消耗较大，建议低峰期执行
```

### 5.3 使用 rollover 管理索引

```json
// 使用 ILM（索引生命周期管理）自动管理索引
PUT /_ilm/policy/logs_policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50GB",
            "max_age": "1d"
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "forcemerge": { "max_num_segments": 1 },
          "shrink": { "number_of_shards": 1 }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "freeze": {}
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

---

## 六、常见性能问题

### 6.1 慢查询

```json
// 开启慢查询日志
PUT /my_index/_settings
{
  "index.search.slowlog.threshold.query.warn": "10s",
  "index.search.slowlog.threshold.query.info": "5s",
  "index.search.slowlog.threshold.query.debug": "2s",
  "index.search.slowlog.threshold.query.trace": "500ms"
}
```

### 6.2 拒绝率过高

```json
// 搜索拒绝率高 → 提线程池队列或加节点
GET /_cat/thread_pool?v

// 查看拒绝
GET /_nodes/stats/thread_pool
```

### 6.3 GC 频繁

```json
// JVM GC 频繁 → 堆太小或字段太多
GET /_nodes/stats/jvm

// 检查堆使用率
GET /_cat/nodes?v&h=name,heapCurrent,heapPercent
```

---

## 七、总结

### 优化清单

| 类别 | 优化项 | 效果 |
|------|--------|:----:|
| 写入 | 批量写入 | ★★★★★ |
| 写入 | 加大 refresh 间隔 | ★★★★☆ |
| 写入 | 禁用副本 | ★★★★☆ |
| 写入 | 异步 translog | ★★★☆☆ |
| 搜索 | filter 代替 query | ★★★★★ |
| 搜索 | norms: false | ★★★☆☆ |
| 搜索 | 避免深度分页 | ★★★★★ |
| 存储 | 禁用 doc_values | ★★★☆☆ |
| 存储 | force merge | ★★★★☆ |
| 存储 | ILM 管理 | ★★★★★ |
| 内存 | 合理分配堆 | ★★★★★ |
| 内存 | 预留 PageCache | ★★★★★ |

### 一句话原则

> **写入优化 = 减少刷盘频率 + 批量操作，搜索优化 = 用 filter + 缩小搜索范围 + 合理 Mapping。**

---

**上一篇：** [Elasticsearch（四）：集群与高可用]({{< relref "post/elasticsearch-cluster" >}})

**系列索引：**
- [Elasticsearch（一）：核心概念与架构]({{< relref "post/elasticsearch-architecture" >}})
- [Elasticsearch（二）：索引与映射]({{< relref "post/elasticsearch-mapping" >}})
- [Elasticsearch（三）：搜索与聚合]({{< relref "post/elasticsearch-search-aggregation" >}})
- [Elasticsearch（四）：集群与高可用]({{< relref "post/elasticsearch-cluster" >}})
- [Elasticsearch（五）：性能优化]({{< relref "post/elasticsearch-performance" >}})
