---
title: "Elasticsearch（二）：索引与映射"
date: 2018-08-15
draft: false
categories: ["ElasticSearch"]
tags: ["Elasticsearch", "Mapping", "索引", "分词", "字段类型"]
toc: true
---

## 前言

在 ES 中，**Mapping（映射）**定义了文档的字段类型和分析规则。合理的 Mapping 设计直接影响搜索的准确性和索引的性能。

本文从 Mapping 的类型和配置出发，深入索引的底层结构——分片、段、以及写入与合并过程。

<!--more-->

## 一、Mapping 映射

### 1.1 动态映射 vs 显式映射

```bash
# 动态映射（ES 自动推断字段类型）
PUT /users/_doc/1
{
  "name": "Tom",
  "age": 25,
  "email": "tom@test.com"
}
# name → text（附带 keyword 子字段）
# age → long
# email → text（附带 keyword 子字段）

# 显式映射（推荐，精确控制类型）
PUT /users
{
  "mappings": {
    "properties": {
      "name": { "type": "text" },
      "age": { "type": "integer" },
      "email": {
        "type": "keyword",
        "index": false
      }
    }
  }
}
```

### 1.2 字段类型

```json
// 字符串类型
"type": "text"        // 分词，全文搜索
"type": "keyword"     // 精确匹配，聚合

// 数值类型
"type": "byte / short / integer / long"
"type": "float / double / half_float / scaled_float"

// 日期类型
"type": "date"
// 支持多种格式
"format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"

// 布尔类型
"type": "boolean"

// 特殊类型
"type": "ip"          // IP 地址
"type": "geo_point"   // 地理坐标
"type": "nested"      // 嵌套对象（独立查询）
"type": "join"        // 父子关系

// 数组
// ES 没有专门的数组类型，任何字段都可以为数组
"tags": ["java", "spring", "elasticsearch"]
// 数组中元素的类型必须一致
```

### 1.3 核心参数

```json
PUT /articles
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",
        "analyzer": "ik_smart",          // 索引分词器
        "search_analyzer": "ik_smart",    // 搜索分词器
        "fields": {                       // 多字段
          "keyword": {
            "type": "keyword",
            "ignore_above": 256           // keyword 超过长度忽略
          }
        }
      },
      "content": {
        "type": "text",
        "index": true,                    // 是否索引
        "store": false,                   // 是否独立存储
        "doc_values": true,               // 聚合排序用
        "norms": false                     // 禁用评分（如果不需要评分可节省空间）
      },
      "status": {
        "type": "keyword",
        "index": true
      },
      "create_time": {
        "type": "date",
        "format": "yyyy-MM-dd HH:mm:ss"
      }
    }
  }
}
```

### 1.4 Mapping 最佳实践

```json
// 1. 关闭不需要的功能
PUT /logs
{
  "mappings": {
    "properties": {
      "message": {
        "type": "text",
        "norms": false,     // 不需要评分
        "index_options": "docs"  // 只索引 doc 位置，不记录词频
      },
      "timestamp": {
        "type": "date"
      },
      "level": {
        "type": "keyword",
        "index": false,    // 不索引（只存不查）
        "doc_values": true
      }
    }
  }
}

// 2. source 禁用（节省存储）
PUT /logs
{
  "mappings": {
    "_source": { "enabled": false }  // 不存原始 JSON
  }
}

// 3. 动态模板（统一规则）
PUT /my_index
{
  "mappings": {
    "dynamic_templates": [
      {
        "strings_as_keyword": {
          "match_mapping_type": "string",
          "mapping": {
            "type": "keyword"
          }
        }
      }
    ]
  }
}
```

---

## 二、分词器

### 2.1 内置分词器

```bash
# standard（默认）：按单词边界切分，转小写
POST /_analyze
{ "analyzer": "standard", "text": "Elasticsearch is a search engine" }
# → [elasticsearch] [is] [a] [search] [engine]

# simple：按非字母切分，转小写
# whitespace：按空格切分
# keyword：不分词，整个字段作为一个词项
# pattern：按正则切分
```

### 2.2 中文分词（IK）

```bash
# 安装 IK 分词器
./bin/elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v7.17.0/elasticsearch-analysis-ik-7.17.0.zip

# ik_smart：最粗粒度切分（适合搜索）
POST /_analyze
{ "analyzer": "ik_smart", "text": "中华人民共和国国歌" }
# → [中华人民共和国] [国歌]

# ik_max_word：最细粒度切分（适合索引）
POST /_analyze
{ "analyzer": "ik_max_word", "text": "中华人民共和国国歌" }
# → [中华人民共和国] [中华] [华人] [人民共和国] [人民] [共和国] [国歌]
```

### 2.3 自定义分词器

```json
PUT /my_index
{
  "settings": {
    "analysis": {
      "analyzer": {
        "my_custom_analyzer": {
          "type": "custom",
          "char_filter": ["html_strip"],      // HTML 标签过滤
          "tokenizer": "standard",
          "filter": [
            "lowercase",                       // 转小写
            "stop",                            // 停用词
            "snowball"                         // 词干提取
          ]
        }
      }
    }
  }
}
```

---

## 三、索引设置

### 3.1 核心配置

```json
PUT /orders
{
  "settings": {
    "number_of_shards": 5,           // 主分片数（创建后不可改）
    "number_of_replicas": 1,         // 副本分片数（可动态改）
    "refresh_interval": "10s",       // refresh 间隔（默认 1s）
    "translog": {
      "durability": "async",         // translog 异步写入（提升写入性能）
      "sync_interval": "5s"
    },
    "index": {
      "merge.scheduler.max_thread_count": 2  // 合并线程数
    }
  }
}

// 动态更新
PUT /orders/_settings
{
  "number_of_replicas": 2,
  "refresh_interval": "30s"
}
```

### 3.2 分片数设计

```
分片数选择原则：

- 主分片数创建后不可修改
- 每个分片建议容量 20-50GB
- 分片数 = 总数据量 / 单分片容量

示例：
预计总数据 500GB
单分片容量 50GB
主分片数 = 500 / 50 = 10

注意：
- 分片太少：单分片过大，影响搜索性能
- 分片太多：占用过多文件句柄，集群管理开销大
- 每个分片是一个 Lucene 实例，消耗内存
```

---

## 四、写入与段合并

### 4.1 写入过程

```
文档写入 ES 的过程：

1. 写入到内存 Buffer（In-Memory Buffer）
2. 同时写入 translog（事务日志，持久化）
3. 每 refresh_interval（默认 1s）触发 refresh：
   → Buffer 中的数据生成新的 Segment
   → Segment 写入磁盘但未 fsync（文件系统缓存）
   → 文档变得可搜索（近实时）
4. 每隔一段时间触发 flush：
   → 将所有 Segment fsync 到磁盘
   → 清空 translog
   → 生成 commit point
```

### 4.2 Segment 与 Refresh

```
Segment 是 Lucene 内部的存储单元。

每个 Segment 包含：倒排索引 + 文档存储

refresh 之前：
  内存 Buffer  →  搜索不可见
  translog     →  持久化

refresh 之后：
  内存 Buffer →  Segment（文件系统缓存）
  搜索可见   →  近实时搜索

增加 refresh_interval 可提升写入性能：
  refresh_interval = -1  →  手动 refresh
  refresh_interval = 30s →  30秒 refresh 一次
```

### 4.3 段合并

```
随着不断的 refresh，会产生大量小 Segment。

段合并（Merge）：
  将多个小 Segment 合并为大 Segment
  减少 Segment 数量 → 提升搜索性能
  在后台线程异步执行

合并策略：
- tiered（默认）：根据大小分层合并
- log_byte_size：按字节大小合并
- log_doc：按文档数合并

合并相关配置：
index.merge.policy.segments_per_tier = 10  // 每层 Segment 数
index.merge.scheduler.max_thread_count = 2  // 合并线程数
```

---

## 五、索引别名

```bash
# 创建别名
PUT /orders/_alias/orders_current

# 通过别名查询
GET /orders_current/_search

# 别名用于零停机索引重建
# 1. 创建新索引
PUT /orders_v2
{ "settings": { ... } }

# 2. 将数据从旧索引迁到新索引
POST /_reindex
{
  "source": { "index": "orders_v1" },
  "dest": { "index": "orders_v2" }
}

# 3. 切换别名
POST /_aliases
{
  "actions": [
    { "remove": { "index": "orders_v1", "alias": "orders_current" }},
    { "add": { "index": "orders_v2", "alias": "orders_current" }}
  ]
}

# 4. 删除旧索引
DELETE /orders_v1
```

---

## 六、总结

### Mapping 设计要点

```json
{
  "mappings": {
    "properties": {
      "title": {
        "type": "text",          // 全文检索
        "fields": {
          "keyword": { "type": "keyword" }  // 精确匹配
        }
      },
      "status": { "type": "keyword" },
      "price": { "type": "double" },
      "create_time": { "type": "date" }
    }
  }
}
```

### 索引配置速查

| 参数 | 默认值 | 说明 |
|------|--------|------|
| number_of_shards | 1 | 主分片数 |
| number_of_replicas | 1 | 副本数 |
| refresh_interval | 1s | refresh 间隔 |
| translog.durability | request | translog 刷盘策略 |

---

**上一篇：** [Elasticsearch（一）：核心概念与架构]({{< relref "post/elasticsearch-architecture" >}})

**下一篇：** [Elasticsearch（三）：搜索与聚合]({{< relref "post/elasticsearch-search-aggregation" >}})
