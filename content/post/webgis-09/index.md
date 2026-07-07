---
title: "Java WebGIS 实战指南（九）：空间数据搜索引擎设计"
date: 2021-11-17
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "空间索引", "RTree", "搜索", "Elasticsearch"]
toc: true
---

## 前言

在 WebGIS 系统中，空间数据搜索的核心需求是：**根据空间范围 + 属性条件快速定位要素**。传统的关系数据库在处理亿级空间数据时性能会大幅下降，这时需要专门的空间搜索引擎。

本文介绍 RTree 空间索引、Geohash 编码索引，以及 Elasticsearch 的地理空间查询能力。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - 本文：空间数据搜索引擎设计
> - [下一篇：海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、空间索引原理对比

| 索引类型 | 原理 | 适用场景 | 查询性能 |
|---------|------|---------|---------|
| RTree | 最小外接矩形(MBR)层次树 | 空间范围查询、最近邻查询 | 极快 |
| Geohash | 经纬度编码为一维字符串 | 区域聚合、邻近查询 | 快 |
| GIST (PostGIS) | PostgreSQL 通用索引树 | 与关系数据库集成 | 快 |
| ES Geo | 倒排索引 + 地理网格 | 全文检索 + 空间查询 | 极快 |

## 二、RTree 空间索引

```java
@Service
public class RTreeIndexService<T extends SpatialData> {

    private RTree<T, Geometry> rtree;

    public RTreeIndexService() {
        this.rtree = RTree.create();
    }

    /**
     * 构建索引
     */
    public void buildIndex(List<T> items) {
        for (T item : items) {
            rtree = rtree.add(item, item.getGeometry());
        }
    }

    /**
     * 范围查询：查找在指定矩形内的所有要素
     */
    public List<T> searchByBbox(Envelope envelope) {
        List<T> results = new ArrayList<>();
        rtree.search(Geos.adapter(envelope))
                .forEach(entry -> results.add(entry.value()));
        return results;
    }

    /**
     * 最近邻查询：查找距离指定点最近的 N 个要素
     */
    public List<T> searchNearest(Point point, int k) {
        List<T> results = new ArrayList<>();
        rtree.nearest(Geos.adapter(point), Integer.MAX_VALUE, k)
                .forEach(entry -> results.add(entry.value()));
        return results;
    }
}
```

## 三、Geohash 编码索引

```java
@Component
public class GeohashIndexService {

    @Autowired
    private SpatialFeatureRepository repository;

    /**
     * 根据 Geohash 前缀查询
     */
    public List<SpatialFeature> searchByGeohash(String geohashPrefix) {
        // 将 Geohash 前缀转为 BBOX
        double[] bbox = Geohash.decodeBbox(geohashPrefix);
        return repository.findByBbox(bbox[0], bbox[1], bbox[2], bbox[3]);
    }

    /**
     * Geohash 邻近搜索：查找某点附近所有 Geohash 格子内的要素
     */
    public List<SpatialFeature> searchNearby(double lng, double lat, int precision) {
        String geohash = Geohash.encode(lng, lat, precision);

        // 获取相邻的 8 个 Geohash
        List<String> neighbors = Geohash.neighbors(geohash);
        neighbors.add(geohash);

        // 合并范围查询
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

        for (String hash : neighbors) {
            double[] bbox = Geohash.decodeBbox(hash);
            minX = Math.min(minX, bbox[0]);
            minY = Math.min(minY, bbox[1]);
            maxX = Math.max(maxX, bbox[2]);
            maxY = Math.max(maxY, bbox[3]);
        }

        return repository.findByBbox(minX, minY, maxX, maxY);
    }
}
```

## 四、Elasticsearch 地理空间查询

```java
@Service
public class EsGeoSearchService {

    @Autowired
    private ElasticsearchRestTemplate esTemplate;

    /**
     * ES 地理范围查询
     */
    public List<SpatialDocument> searchByGeoBbox(double minX, double minY,
                                                   double maxX, double maxY) {
        QueryBuilder query = QueryBuilders.geoBoundingBoxQuery("location")
                .setCorners(minY, minX, maxY, maxX);

        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(query)
                .build();

        SearchHits<SpatialDocument> hits =
                esTemplate.search(searchQuery, SpatialDocument.class);
        return hits.stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }

    /**
     * ES 地理距离查询
     */
    public List<SpatialDocument> searchByDistance(double lng, double lat, double distance) {
        QueryBuilder query = QueryBuilders.geoDistanceQuery("location")
                .point(lat, lng)
                .distance(distance, DistanceUnit.METERS);

        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(query)
                .withSort(SortBuilders.geoDistanceSort("location", lat, lng))
                .build();

        SearchHits<SpatialDocument> hits =
                esTemplate.search(searchQuery, SpatialDocument.class);
        return hits.stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }
}
```

## 五、多级搜索策略

```java
/**
 * 智能搜索路由
 */
@Service
public class SmartSearchService {

    public SearchResult smartSearch(SearchRequest request) {
        // 小范围精确查询 → PostGIS 直接查询
        if (request.getBbox() != null && request.isSmallArea()) {
            return postgisSearch(request);
        }

        // 大范围 + 全文检索 → Elasticsearch
        if (request.getKeyword() != null) {
            return esSearch(request);
        }

        // 大范围空间查询 → RTree 内存索引
        return rtreeSearch(request);
    }
}
```

## 六、总结

- RTree 适合内存索引，适合百万级以内的空间数据
- Geohash 编码适合区域聚合和邻近搜索
- Elasticsearch 的地理空间查询适合海量数据 + 全文检索结合的场景
- 实际项目中采用多级搜索策略：小范围用 PostGIS，大范围用 ES，热点数据用 RTree
