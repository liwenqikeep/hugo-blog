---
title: "Elasticsearch（三）：搜索与聚合"
date: 2018-08-17
draft: false
categories: ["ElasticSearch"]
tags: ["Elasticsearch", "搜索", "Query DSL", "聚合", "全文检索"]
toc: true
---

## 前言

搜索和聚合是 ES 最核心的能力。Query DSL 提供了丰富的查询表达式——从简单的全文搜索到复杂的布尔组合查询。聚合则可以在搜索结果上进行统计分析，**不依赖外部的计算引擎**。

本文从基础的 Query DSL 开始，逐步深入到复合查询和聚合分析。

<!--more-->

## 一、Query DSL 概述

### 1.1 查询的结构

```json
{
  "query": {                          // 查询上下文
    "bool": {
      "must": [ { "match": { ... } } ],
      "filter": [ { "term": { ... } } ]  // 过滤上下文（不计分，可缓存）
    }
  },
  "from": 0,                          // 分页
  "size": 20,
  "sort": [ { "create_time": "desc" } ],  // 排序
  "_source": ["id", "title"],          // 返回字段
  "highlight": {                       // 高亮
    "fields": { "title": {} }
  },
  "aggs": {                           // 聚合
    "group_by_status": { "terms": { "field": "status" } }
  }
}
```

**Query Context vs Filter Context：**

| 上下文 | 计分 | 缓存 | 性能 |
|--------|:----:|:----:|:----:|
| Query | ✅ 计分 | ❌ 不缓存 | 略慢 |
| Filter | ❌ 不计分 | ✅ 可缓存 | 更快 |

---

## 二、全文搜索

### 2.1 match 查询

```bash
# 最常用的全文搜索
GET /articles/_search
{
  "query": {
    "match": {
      "title": "Elasticsearch 搜索引擎"
    }
  }
}
# 分词后：["elasticsearch", "搜索引擎"]
# 只要匹配任意词就返回（OR 逻辑）

# 改为 AND 逻辑
GET /articles/_search
{
  "query": {
    "match": {
      "title": {
        "query": "Elasticsearch 搜索引擎",
        "operator": "and"              // 必须匹配所有词
      }
    }
  }
}

# 短语匹配（精确短语）
GET /articles/_search
{
  "query": {
    "match_phrase": {
      "title": "Elasticsearch 搜索引擎"   // 顺序和位置都要匹配
    }
  }
}
```

### 2.2 multi_match 查询

```bash
# 同时在多个字段中搜索
GET /articles/_search
{
  "query": {
    "multi_match": {
      "query": "elasticsearch 指南",
      "fields": ["title^3", "content^2", "tags"]  // ^3 表示权重提升
    }
  }
}
```

---

## 三、精确查询

### 3.1 term 查询

```bash
# 精确匹配（适合 keyword、数值、日期）
GET /orders/_search
{
  "query": {
    "term": {
      "status": "paid"          // keyword 类型，不分词
    }
  }
}

# 多个值
GET /orders/_search
{
  "query": {
    "terms": {
      "status": ["paid", "shipped", "completed"]
    }
  }
}

# 范围查询
GET /orders/_search
{
  "query": {
    "range": {
      "amount": {
        "gte": 100,
        "lt": 500
      },
      "create_time": {
        "gte": "2024-01-01"
      }
    }
  }
}
```

### 3.2 exists / prefix / wildcard

```bash
# 存在查询
GET /users/_search
{
  "query": {
    "exists": {
      "field": "email"        // email 字段不为 null/空
    }
  }
}

# 前缀查询
GET /users/_search
{
  "query": {
    "prefix": {
      "name": "Tom"           // name 以 Tom 开头的文档
    }
  }
}

# 通配符（低效，尽量避免）
GET /users/_search
{
  "query": {
    "wildcard": {
      "name": "T?m*"
    }
  }
}
```

---

## 四、复合查询——bool

`bool` 查询是最常用的复合查询，可以组合多个子句。

```bash
GET /orders/_search
{
  "query": {
    "bool": {
      "must": [                          // AND：必须匹配（计分）
        { "match": { "user_name": "Tom" } }
      ],
      "filter": [                        // AND：必须匹配（不计分，可缓存）
        { "term": { "status": "paid" } },
        { "range": { "amount": { "gte": 100 } } }
      ],
      "should": [                        // OR：匹配越多得分越高
        { "term": { "tags": "vip" } },
        { "term": { "tags": "new" } }
      ],
      "must_not": [                      // NOT：不能匹配
        { "term": { "is_deleted": true } }
      ]
    }
  }
}

# should 至少匹配一个（默认 0）
# 可以设置 minimum_should_match
GET /articles/_search
{
  "query": {
    "bool": {
      "should": [
        { "match": { "title": "java" } },
        { "match": { "title": "spring" } },
        { "match": { "title": "elasticsearch" } }
      ],
      "minimum_should_match": 2       // 至少匹配 2 个
    }
  }
}
```

---

## 五、聚合分析

### 5.1 Metric 聚合

```bash
# 统计指标
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "total_amount": {
      "sum": { "field": "amount" }          // 求和
    },
    "avg_amount": {
      "avg": { "field": "amount" }          // 平均值
    },
    "max_amount": {
      "max": { "field": "amount" }          // 最大值
    },
    "min_amount": {
      "min": { "field": "amount" }          // 最小值
    },
    "count_distinct": {
      "cardinality": { "field": "user_id" }   // 去重计数
    },
    "stats": {
      "stats": { "field": "amount" }          // 一次返回所有统计
    }
  }
}
```

### 5.2 Bucket 聚合（分组）

```bash
# 按状态分组
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "by_status": {
      "terms": {
        "field": "status",
        "size": 10,
        "order": { "_count": "desc" }
      }
    }
  }
}
# 返回：paid: 1000, pending: 200, cancelled: 50

# 日期直方图（按时间分组）
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "orders_over_time": {
      "date_histogram": {
        "field": "create_time",
        "calendar_interval": "day"           // day/month/year
      }
    }
  }
}

# 范围聚合
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "amount_ranges": {
      "range": {
        "field": "amount",
        "ranges": [
          { "key": "低价", "to": 50 },
          { "key": "中价", "from": 50, "to": 200 },
          { "key": "高价", "from": 200 }
        ]
      }
    }
  }
}
```

### 5.3 多级聚合

```bash
# 先按状态分组，再统计金额
GET /orders/_search
{
  "size": 0,
  "aggs": {
    "by_status": {
      "terms": { "field": "status" },
      "aggs": {                             # 子聚合
        "avg_amount": {
          "avg": { "field": "amount" }
        },
        "by_user": {
          "terms": { "field": "user_id", "size": 5 },
          "aggs": {
            "total": {
              "sum": { "field": "amount" }
            }
          }
        }
      }
    }
  }
}
```

### 5.4 聚合与查询结合

```bash
# 在搜索结果上做聚合
GET /orders/_search
{
  "query": {
    "range": { "amount": { "gte": 100 } }     // 先过滤
  },
  "size": 20,
  "aggs": {                                    // 在过滤结果上聚合
    "by_status": { "terms": { "field": "status" } }
  }
}
```

---

## 六、分页与深度分页

```bash
# 普通分页（from + size）
# 深度分页性能差：from=10000 时，每个分片需要取 10020 条再合并
GET /orders/_search
{
  "from": 0,
  "size": 20
}

# Search After（推荐，适合深度分页）
# 需要排序字段
GET /orders/_search
{
  "size": 20,
  "sort": [ { "order_id": "desc" } ],
  "search_after": [10000]                    // 上一页最后一个排序值
}

# Scroll（适合全量导出，不适合分页展示）
POST /orders/_search?scroll=5m               // 创建 scroll 上下文
{
  "size": 1000
}
# 后续请求
POST /_search/scroll
{
  "scroll": "5m",
  "scroll_id": "DXF1ZXJ5QW5kRmV0Y2gB..."
}
```

---

## 七、总结

### 常见查询速查

| 场景 | 查询类型 | 说明 |
|------|---------|------|
| 全文搜索 | match | 分词匹配 |
| 短语搜索 | match_phrase | 保留词序 |
| 精确匹配 | term | 完全匹配 |
| 范围查询 | range | 数值/日期范围 |
| 复合条件 | bool | must/filter/should/must_not |
| 高亮 | highlight | 搜索结果高亮 |

### 聚合类型速查

| 类型 | 说明 | 示例 |
|------|------|------|
| terms | 分组计数 | 按状态分组 |
| avg/sum/min/max | 统计值 | 计算平均值 |
| date_histogram | 时间维度 | 按天统计 |
| cardinality | 去重计数 | 统计独立用户 |
| range | 范围分段 | 按价格区间 |

---

**上一篇：** [Elasticsearch（二）：索引与映射]({{< relref "post/elasticsearch-mapping" >}})

**下一篇：** [Elasticsearch（四）：集群与高可用]({{< relref "post/elasticsearch-cluster" >}})
