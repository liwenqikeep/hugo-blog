---
title: "Java WebGIS 实战指南（六）：OGC Web 服务实战 WMSWMTS"
date: 2021-11-11
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "OGC", "WMS", "WMTS", "瓦片", "GeoTools"]
toc: true
---

## 前言

OGC（Open Geospatial Consortium）标准是 GIS 互操作性的基石。WMS（Web Map Service）和 WMTS（Web Map Tile Service）是最常用的两种地图服务标准。WMS 按需渲染地图图片，WMTS 预先生成瓦片缓存。

本文将用 GeoTools 实现 WMS 服务，并用 Spring Boot 实现瓦片服务。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [JTS 空间计算引擎]({{< relref "post/webgis-05" >}})
> - 本文：OGC WMS/WMTS 服务实战
> - [下一篇：OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、WMS 服务原理

```
WMS 请求流程：

GetMap?BBOX=minX,minY,maxX,maxY&WIDTH=256&HEIGHT=256&LAYERS=layer&FORMAT=image/png

1. 解析请求参数（BBOX、宽高、图层）
2. 根据 BBOX 查询空间数据
3. 使用 GeoTools 渲染为 PNG 图片
4. 返回图片给前端
```

## 二、WMS 服务实现

```java
@RestController
@RequestMapping("/wms")
@RequiredArgsConstructor
public class WmsController {

    private final SpatialFeatureRepository repository;

    @GetMapping("/map")
    public ResponseEntity<byte[]> getMap(
            @RequestParam double bboxMinX,
            @RequestParam double bboxMinY,
            @RequestParam double bboxMaxX,
            @RequestParam double bboxMaxY,
            @RequestParam(defaultValue = "256") int width,
            @RequestParam(defaultValue = "256") int height,
            @RequestParam(defaultValue = "image/png") String format)
            throws Exception {

        // 1. 查询空间数据
        List<SpatialFeature> features = repository.findByBbox(
                bboxMinX, bboxMinY, bboxMaxX, bboxMaxY);

        // 2. 渲染地图图片
        byte[] imageBytes = renderMap(features, bboxMinX, bboxMinY,
                bboxMaxX, bboxMaxY, width, height);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(imageBytes);
    }

    private byte[] renderMap(List<SpatialFeature> features,
                              double minX, double minY,
                              double maxX, double maxY,
                              int width, int height) throws Exception {
        // 创建地图
        MapContent map = new MapContent();
        map.setTitle("WebGIS Map");

        // 创建样式
        Style style = SLD.createSimpleStyle(
                createFeatureType(),
                Color.RED);

        // 创建图层
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection =
                buildFeatureCollection(features);
        FeatureLayer layer = new FeatureLayer(collection, style);
        map.addLayer(layer);

        // 渲染
        ReferencedEnvelope bounds = new ReferencedEnvelope(
                minX, maxX, minY, maxY,
                DefaultGeographicCRS.WGS84);

        GTRenderer renderer = new GTRenderer(map);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        renderer.setMapExtent(bounds);
        renderer.paint(g2d, new Rectangle(width, height));
        g2d.dispose();

        // 编码为 PNG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
```

## 三、瓦片服务实现

```java
@RestController
@RequestMapping("/tiles")
@RequiredArgsConstructor
public class TileController {

    private final SpatialFeatureRepository repository;

    // Web Mercator 瓦片范围
    private static final double[] ORIGIN = {-20037508.34, 20037508.34};

    /**
     * 矢量瓦片 API（XYZ 规则）
     * /tiles/{z}/{x}/{y}.pbf
     */
    @GetMapping("/{z}/{x}/{y}.pbf")
    public ResponseEntity<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y) throws Exception {

        // 1. 计算瓦片的 BBOX
        double tileSize = 2 * Math.PI * 6378137 / (1 << z);
        double minX = ORIGIN[0] + x * tileSize;
        double maxX = ORIGIN[0] + (x + 1) * tileSize;
        double minY = ORIGIN[1] - (y + 1) * tileSize;
        double maxY = ORIGIN[1] - y * tileSize;

        // 2. 将墨卡托坐标转 WGS84
        double[] minLngLat = mercatorToWgs84(minX, minY);
        double[] maxLngLat = mercatorToWgs84(maxX, maxY);

        // 3. 查询数据
        List<SpatialFeature> features = repository.findByBbox(
                minLngLat[0], minLngLat[1], maxLngLat[0], maxLngLat[1]);

        // 4. 生成 GeoJSON 作为瓦片内容
        String geojson = convertToGeoJson(features);

        // 5. 返回（使用 gzip 压缩）
        byte[] compressed = gzipCompress(geojson.getBytes(StandardCharsets.UTF_8));

        return ResponseEntity.ok()
                .header("Content-Encoding", "gzip")
                .contentType(MediaType.parseMediaType("application/x-protobuf"))
                .body(compressed);
    }
}
```

## 四、瓦片缓存策略

```java
@Component
public class TileCacheService {

    @Autowired
    private RedisTemplate<String, byte[]> redisTemplate;

    private static final long TILE_CACHE_TTL = 3600; // 1 小时过期

    /**
     * 获取缓存的瓦片
     */
    public byte[] getTile(String cacheKey) {
        return redisTemplate.opsForValue().get("tile:" + cacheKey);
    }

    /**
     * 缓存瓦片
     */
    public void cacheTile(String cacheKey, byte[] tileData) {
        redisTemplate.opsForValue().set(
                "tile:" + cacheKey, tileData, TILE_CACHE_TTL, TimeUnit.SECONDS);
    }
}
```

## 五、总结

- WMS 动态渲染，适合数据频繁变化的场景；WMTS/瓦片服务适合静态数据
- GeoTools 的 `GTRenderer` 支持将空间数据渲染为图片
- 瓦片服务使用 XYZ 规范，瓦片 BBOX 在 Web Mercator 坐标系下计算
- Redis 瓦片缓存 + 预生成策略可以大幅提升地图加载性能
