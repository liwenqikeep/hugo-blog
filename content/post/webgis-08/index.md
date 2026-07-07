---
title: "Java WebGIS 实战指南（八）：空间数据编码与传输优化"
date: 2021-11-15
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "空间数据", "编码", "MVT", "GeoJSON", "性能优化"]
toc: true
---

## 前言

空间数据通常体量较大——一个城市的 POI 数据可能就有数万条。如果直接返回原始的 GeoJSON，会让前端加载缓慢、用户体验下降。本文介绍空间数据的编码与传输优化方案，包括压缩、矢量瓦片、增量传输等。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - 本文：空间数据编码与传输优化
> - [下一篇：空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、GeoJSON 压缩

```java
@Service
public class GeoJsonOptimizer {

    /**
     * 精简 GeoJSON：缩减属性字段
     */
    public String optimizeGeoJson(String geojson, List<String> keepFields) {
        Map<String, Object> root = JsonUtils.parseMap(geojson);
        List<Map<String, Object>> features = (List<Map<String, Object>>) root.get("features");

        for (Map<String, Object> feature : features) {
            Map<String, Object> properties = (Map<String, Object>) feature.get("properties");
            properties.keySet().retainAll(keepFields);
        }

        return JsonUtils.toJson(root);
    }

    /**
     * 简化几何精度：减少坐标点位数
     */
    public String simplifyGeoJson(String geojson, int decimalPlaces) {
        Map<String, Object> root = JsonUtils.parseMap(geojson);
        List<Map<String, Object>> features = (List<Map<String, Object>>) root.get("features");

        for (Map<String, Object> feature : features) {
            roundCoordinates(feature, decimalPlaces);
        }

        return JsonUtils.toJson(root);
    }
}
```

## 二、矢量瓦片（MVT）

```java
@Service
public class MvtTileService {

    @Autowired
    private SpatialFeatureRepository repository;

    /**
     * 生成 MVT 矢量瓦片
     */
    public byte[] generateMvtTile(int z, int x, int y) throws Exception {
        // 1. 计算瓦片 BBOX（Web Mercator）
        double[] bbox = tileToBbox(z, x, y);

        // 2. 查询范围内的要素
        double[] minWgs = mercatorToWgs84(bbox[0], bbox[1]);
        double[] maxWgs = mercatorToWgs84(bbox[2], bbox[3]);
        List<SpatialFeature> features = repository.findByBbox(
                minWgs[0], minWgs[1], maxWgs[0], maxWgs[1]);

        // 3. 使用 GeoTools 的 MVT 编码器（gt-mvt 模块）
        // 或手动编码为 MVT 二进制格式
        return encodeFeaturesToMvt(features, z, x, y);
    }

    private byte[] encodeFeaturesToMvt(List<SpatialFeature> features,
                                        int z, int x, int y) {
        // 实际项目中：使用 Mapbox Vector Tile 编码库
        // 简化演示：直接返回 GZip 压缩的 GeoJSON
        String geojson = convertToGeoJson(features);
        return gzipCompress(geojson.getBytes(StandardCharsets.UTF_8));
    }
}
```

## 三、增量传输

```java
/**
 * 增量数据同步 - 基于时间戳
 */
@GetMapping("/sync")
public ResponseEntity<String> syncData(
        @RequestParam(required = false) String lastSyncTime) {

    List<SpatialFeature> changedFeatures;

    if (lastSyncTime != null) {
        LocalDateTime since = LocalDateTime.parse(lastSyncTime);
        changedFeatures = repository.findByUpdatedAtAfter(since);
    } else {
        changedFeatures = repository.findAll();
    }

    return ResponseEntity.ok(toGeoJson(changedFeatures));
}
```

## 四、Geohash 聚合

```java
/**
 * Geohash 聚合：分层聚合空间要素
 */
@GetMapping("/aggregate/{zoom}")
public ResponseEntity<Map<String, Long>> aggregateByGeohash(
        @PathVariable int zoom,
        @RequestParam double minX, @RequestParam double minY,
        @RequestParam double maxX, @RequestParam double maxY) {

    List<SpatialFeature> features = repository.findByBbox(minX, minY, maxX, maxY);

    // Geohash 精度：zoom < 10 → 4位, zoom < 14 → 5位, ≥ 14 → 6位
    int geohashLen = zoom < 10 ? 4 : (zoom < 14 ? 5 : 6);

    Map<String, Long> aggregation = new HashMap<>();
    for (SpatialFeature feature : features) {
        String geohash = Geohash.encode(feature.getLng(), feature.getLat(), geohashLen);
        aggregation.merge(geohash, 1L, Long::sum);
    }

    return ResponseEntity.ok(aggregation);
}
```

## 五、总结

- **GeoJSON 精简**：只保留前端展示必需字段，减少传输量
- **矢量瓦片**：按金字塔层级分块传输，是海量空间数据的最佳方案
- **增量传输**：基于时间戳只传变化数据，减少重复传输
- **Geohash 聚合**：低缩放级别用聚合数据替代原始数据，大幅减小数据量
- **GZip 压缩**：所有空间数据传输都应启用 GZip 压缩
