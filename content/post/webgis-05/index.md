---
title: "Java WebGIS 实战指南（五）：JTS 空间计算引擎"
date: 2021-11-09
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "JTS", "空间计算", "缓冲区分析", "几何运算"]
toc: true
---

## 前言

JTS（Java Topology Suite）是 Java 空间计算的事实标准库。它提供了完整的几何模型和空间运算能力——空间关系判断、缓冲区分析、叠加分析等。本文将深入 JTS 的核心 API 并给出实战示例。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [Spring Boot 集成 GeoTools 实战]({{< relref "post/webgis-02" >}})
> - [PostGIS 空间数据库实战]({{< relref "post/webgis-03" >}})
> - [GeoTools 空间数据读写实战]({{< relref "post/webgis-04" >}})
> - 本文：JTS 空间计算引擎
> - [下一篇：OGC WMS/WMTS 服务实战]({{< relref "post/webgis-06" >}})
> - [OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、JTS 核心模型

JTS 的几何模型遵循 OGC Simple Features 规范：

```
Geometry
├── Point              // 点
├── LineString         // 线
├── Polygon            // 面
├── MultiPoint         // 多点
├── MultiLineString    // 多线
├── MultiPolygon       // 多面
└── GeometryCollection // 几何集合
```

## 二、空间关系判断

```java
@Service
public class SpatialRelationService {

    private final GeometryFactory gf = JTSFactoryFinder.getGeometryFactory();

    /**
     * 判断点是否在多边形内（空间包含）
     */
    public boolean contains(double lng, double lat, Polygon polygon) {
        Point point = gf.createPoint(new Coordinate(lng, lat));
        return polygon.contains(point);
    }

    /**
     * 判断两个多边形是否相交
     */
    public boolean intersects(Geometry geom1, Geometry geom2) {
        return geom1.intersects(geom2);
    }

    /**
     * 计算两个几何之间的距离（单位：度）
     */
    public double distance(Geometry geom1, Geometry geom2) {
        return geom1.distance(geom2);
    }

    /**
     * 空间关系大全
     */
    public SpatialRelations analyzeRelation(Geometry geom1, Geometry geom2) {
        SpatialRelations rel = new SpatialRelations();
        rel.setContains(geom1.contains(geom2));
        rel.setWithin(geom1.within(geom2));
        rel.setIntersects(geom1.intersects(geom2));
        rel.setDisjoint(geom1.disjoint(geom2));
        rel.setTouches(geom1.touches(geom2));
        rel.setCrosses(geom1.crosses(geom2));
        rel.setOverlaps(geom1.overlaps(geom2));
        return rel;
    }

    @Data
    public static class SpatialRelations {
        private boolean contains;
        private boolean within;
        private boolean intersects;
        private boolean disjoint;
        private boolean touches;
        private boolean crosses;
        private boolean overlaps;
    }
}
```

## 三、缓冲区分析

```java
@Service
public class BufferAnalysisService {

    /**
     * 创建缓冲区（WGS84 坐标）
     */
    public Geometry createBuffer(Geometry geom, double bufferDegrees) {
        return geom.buffer(bufferDegrees);
    }

    /**
     * 创建精确距离缓冲区（米）
     * 将几何投影到 Web Mercator，做缓冲区，再转回 WGS84
     */
    public Geometry createBufferInMeters(Geometry geom, double bufferMeters) throws Exception {
        // 获取几何中心点
        Coordinate centroid = geom.getCentroid().getCoordinate();

        // 投影到 Web Mercator
        CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
        CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:3857");
        MathTransform toMercator = CRS.findMathTransform(sourceCRS, targetCRS);

        // 做缓冲区
        Geometry mercatorGeom = JTS.transform(geom, toMercator);
        Geometry buffer = mercatorGeom.buffer(bufferMeters);

        // 转回 WGS84
        MathTransform toWgs84 = CRS.findMathTransform(targetCRS, sourceCRS);
        return JTS.transform(buffer, toWgs84);
    }
}
```

## 四、叠加分析

```java
/**
 * 叠加分析：查找在一个区域内的所有要素
 */
public List<FeatureResult> overlayAnalysis(
        FeatureCollection<SimpleFeatureType, SimpleFeature> features,
        Geometry clipArea) {

    List<FeatureResult> results = new ArrayList<>();

    try (FeatureIterator<SimpleFeature> iterator = features.features()) {
        while (iterator.hasNext()) {
            SimpleFeature feature = iterator.next();
            Geometry geom = (Geometry) feature.getDefaultGeometry();

            // 求交
            Geometry intersection = geom.intersection(clipArea);
            if (!intersection.isEmpty()) {
                results.add(new FeatureResult(feature, intersection));
            }
        }
    }
    return results;
}
```

## 五、空间计算 API

```java
@RestController
@RequestMapping("/api/spatial")
@RequiredArgsConstructor
public class SpatialAnalysisController {

    private final SpatialRelationService relationService;
    private final BufferAnalysisService bufferService;

    /**
     * 创建缓冲区
     */
    @PostMapping("/buffer")
    public Geometry createBuffer(@RequestBody BufferRequest request) {
        Geometry geom = parseWkt(request.getWkt());
        return bufferService.createBufferInMeters(geom, request.getBufferMeters());
    }

    /**
     * 空间包含查询
     */
    @PostMapping("/contains")
    public boolean checkContains(@RequestBody ContainsRequest request) {
        Geometry geom1 = parseWkt(request.getWkt1());
        Geometry geom2 = parseWkt(request.getWkt2());
        return geom1.contains(geom2);
    }
}
```

## 六、总结

- JTS 的 `Geometry` 层次结构完整覆盖了 OGC Simple Features 规范
- `buffer()`、`intersection()`、`contains()` 是最常用的空间分析方法
- 使用 Web Mercator 投影做精确距离的缓冲区分析，再转回 WGS84
- JTS + GeoTools 的组合可以满足大部分 Java WebGIS 的空间计算需求
