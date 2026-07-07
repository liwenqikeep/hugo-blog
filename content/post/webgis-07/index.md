---
title: "Java WebGIS 实战指南（七）：OGC Web 服务实战 WFS"
date: 2021-11-13
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "OGC", "WFS", "空间数据查询", "GeoTools"]
toc: true
---

## 前言

WFS（Web Feature Service）是 OGC 标准中用于空间数据查询和编辑的服务。与 WMS 返回图片不同，WFS 返回的是空间数据本身（通常编码为 GML 或 GeoJSON），开发者可以在前端对要素进行增删改查。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [OGC WMS/WMTS 服务实战]({{< relref "post/webgis-06" >}})
> - 本文：OGC WFS 服务实战
> - [下一篇：空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、WFS 核心操作

WFS 定义了以下标准操作：

| 操作 | 说明 | HTTP 方法 |
|------|------|----------|
| GetCapabilities | 获取服务能力文档 | GET |
| DescribeFeatureType | 获取要素类型结构 | GET |
| GetFeature | 查询要素 | GET/POST |
| Transaction | 增删改要素 | POST |

## 二、WFS GetFeature 实现

```java
@RestController
@RequestMapping("/wfs")
@RequiredArgsConstructor
public class WfsController {

    private final SpatialFeatureRepository repository;

    /**
     * WFS GetFeature：按 BBOX 查询
     * GET /wfs?request=GetFeature&bbox=minX,minY,maxX,maxY
     */
    @GetMapping
    public ResponseEntity<String> getFeature(
            @RequestParam String request,
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) String typeName,
            @RequestParam(required = false, defaultValue = "application/json")
                String outputFormat) throws Exception {

        if (!"GetFeature".equalsIgnoreCase(request)) {
            return ResponseEntity.badRequest().body("Unsupported request");
        }

        List<SpatialFeature> features;

        if (bbox != null) {
            String[] parts = bbox.split(",");
            double minX = Double.parseDouble(parts[0]);
            double minY = Double.parseDouble(parts[1]);
            double maxX = Double.parseDouble(parts[2]);
            double maxY = Double.parseDouble(parts[3]);

            features = repository.findByBbox(minX, minY, maxX, maxY);
        } else {
            features = repository.findAll();
        }

        // 转 GeoJSON
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection =
                buildFeatureCollection(features);
        String geojson = toGeoJson(collection);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(geojson);
    }
}
```

## 三、WFS Transaction（新增要素）

```java
/**
 * WFS Transaction：新增要素
 * POST /wfs/feature
 */
@PostMapping("/feature")
public ResponseEntity<SpatialFeature> createFeature(
        @RequestBody FeatureCreateRequest request) {

    // 构建空间数据
    SpatialFeature feature = new SpatialFeature();
    feature.setName(request.getName());
    feature.setFeatureType(request.getFeatureType());
    feature.setGeom(convertToWkt(request));
    feature.setProperties(request.getProperties() != null ?
            request.getProperties().toString() : "{}");

    SpatialFeature saved = repository.save(feature);
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
}

/**
 * WFS Transaction：更新要素
 */
@PutMapping("/feature/{id}")
public ResponseEntity<SpatialFeature> updateFeature(
        @PathVariable Long id,
        @RequestBody FeatureUpdateRequest request) {

    SpatialFeature feature = repository.findById(id)
            .orElseThrow(() -> new RuntimeException("要素不存在"));

    if (request.getName() != null) feature.setName(request.getName());
    if (request.getWkt() != null) feature.setGeom(request.getWkt());

    repository.save(feature);
    return ResponseEntity.ok(feature);
}

/**
 * WFS Transaction：删除要素
 */
@DeleteMapping("/feature/{id}")
public ResponseEntity<Void> deleteFeature(@PathVariable Long id) {
    repository.deleteById(id);
    return ResponseEntity.noContent().build();
}
```

## 四、WFS 过滤器

```java
/**
 * GetFeature with CQL 过滤器
 * GET /wfs/query?filter=name LIKE '%医院%'
 */
@GetMapping("/query")
public ResponseEntity<String> queryWithFilter(
        @RequestParam(required = false) String filter,
        @RequestParam(required = false) String bbox) throws Exception {

    List<SpatialFeature> features;

    if (filter != null) {
        // 使用 GeoTools 的 CQL 解析器
        Filter cqlFilter = CQL.toFilter(filter);

        // 应用过滤器
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection =
                loadAllFeatures();
        FeatureCollection<SimpleFeatureType, SimpleFeature> filtered =
                collection.subCollection(cqlFilter);
        features = convertToList(filtered);
    } else {
        features = repository.findAll();
    }

    return ResponseEntity.ok(toGeoJson(features));
}
```

## 五、总结

- WFS 提供标准的空间数据查询和编辑接口，与前端框架无缝集成
- GetFeature 支持 BBOX、属性过滤、排序等参数
- Transaction 操作（增删改）使地理数据编辑标准化
- CQL 过滤器提供了类似 SQL WHERE 的空间数据查询语言
