---
title: "Java WebGIS 实战指南（十）：海量空间数据的后端渲染"
date: 2021-11-19
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "海量数据", "矢量瓦片", "热力图", "后端渲染"]
toc: true
---

## 前言

当空间数据量达到百万级甚至亿级时，直接将原始数据返回前端渲染是不可行的。后端渲染是解决海量空间数据展示的核心策略——在后端完成数据的聚合、简化、渲染，前端只负责展示。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - 本文：海量空间数据的后端渲染
> - [下一篇：WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、后端渲染策略全景

```
┌─────────────────────────────────────────────────────────┐
│  海量空间数据渲染策略                                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. 矢量瓦片生成（MVT）                                   │
│     ├── 预生成所有层级的瓦片                              │
│     └── 按需增量更新                                      │
│                                                         │
│  2. 聚合渲染                                              │
│     ├── 空间聚类（DBSCAN）                                │
│     ├── Geohash 聚合                                      │
│     └── 四叉树聚合                                        │
│                                                         │
│  3. 热力图后端计算                                         │
│     ├── Kernel Density Estimation                        │
│     └── 格网化统计                                        │
│                                                         │
│  4. 降采样渲染                                            │
│     ├── 保留重要点                                        │
│     └── 按层级控制精度                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 二、矢量瓦片预生成

```java
@Service
public class TilePreGenerator {

    private static final int MAX_ZOOM = 18;
    private static final int MIN_ZOOM = 0;

    @Autowired
    private SpatialFeatureRepository repository;

    /**
     * 预生成所有瓦片（定时任务，每日增量更新）
     */
    @Scheduled(cron = "0 0 3 * * ?") // 每天凌晨 3 点
    public void preGenerateAllTiles() {
        for (int z = MIN_ZOOM; z <= MAX_ZOOM; z++) {
            int tileCount = (int) Math.pow(2, z);
            // 并行生成瓦片
            IntStream.range(0, tileCount).parallel().forEach(x -> {
                IntStream.range(0, tileCount).parallel().forEach(y -> {
                    try {
                        generateAndCacheTile(z, x, y);
                    } catch (Exception e) {
                        log.error("生成瓦片失败: {}/{}/{}", z, x, y, e);
                    }
                });
            });
        }
    }

    private void generateAndCacheTile(int z, int x, int y) throws Exception {
        byte[] tileData = tileService.generateMvtTile(z, x, y);
        tileCacheService.cacheTile(z + "/" + x + "/" + y, tileData);
    }
}
```

## 三、空间聚合渲染

```java
@RestController
@RequestMapping("/api/aggregation")
@RequiredArgsConstructor
public class AggregationController {

    private final SpatialFeatureRepository repository;

    /**
     * Geohash 聚合：按层级返回聚合计数
     */
    @GetMapping("/geohash")
    public Mono<List<AggregationResult>> geohashAggregation(
            @RequestParam double minX, @RequestParam double minY,
            @RequestParam double maxX, @RequestParam double maxY,
            @RequestParam(defaultValue = "12") int zoom) {

        // 根据缩放级别决定 Geohash 精度
        int precision = Math.max(3, 12 - zoom);

        List<SpatialFeature> features = repository.findByBbox(minX, minY, maxX, maxY);

        // Geohash 聚合
        Map<String, Long> geoMap = AggregationUtil.geohashAggregate(features, precision);

        List<AggregationResult> results = geoMap.entrySet().stream()
                .map(e -> {
                    AggregationResult r = new AggregationResult();
                    r.setGeohash(e.getKey());
                    r.setCount(e.getValue());
                    return r;
                })
                .collect(Collectors.toList());

        return Mono.just(results);
    }

    /**
     * Grid 聚合：将范围划分为网格，统计每个格子的数量
     */
    @GetMapping("/grid")
    public Mono<List<GridCell>> gridAggregation(
            @RequestParam double minX, @RequestParam double minY,
            @RequestParam double maxX, @RequestParam double maxY,
            @RequestParam(defaultValue = "50") int gridSize) {

        List<SpatialFeature> features = repository.findByBbox(minX, minY, maxX, maxY);

        double cellW = (maxX - minX) / gridSize;
        double cellH = (maxY - minY) / gridSize;

        // 初始化网格
        int[][] grid = new int[gridSize][gridSize];

        // 统计
        for (SpatialFeature f : features) {
            double lng = f.getLng();
            double lat = f.getLat();
            int col = (int) ((lng - minX) / cellW);
            int row = (int) ((lat - minY) / cellH);
            if (col >= 0 && col < gridSize && row >= 0 && row < gridSize) {
                grid[col][row]++;
            }
        }

        // 转为结果
        List<GridCell> results = new ArrayList<>();
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (grid[i][j] > 0) {
                    GridCell cell = new GridCell();
                    cell.setMinX(minX + i * cellW);
                    cell.setMinY(minY + j * cellH);
                    cell.setMaxX(cell.getMinX() + cellW);
                    cell.setMaxY(cell.getMinY() + cellH);
                    cell.setCount(grid[i][j]);
                    results.add(cell);
                }
            }
        }

        return Mono.just(results);
    }

    @Data
    public static class AggregationResult {
        private String geohash;
        private long count;
    }

    @Data
    public static class GridCell {
        private double minX, minY, maxX, maxY;
        private int count;
    }
}
```

## 四、后端热力图

```java
@Service
public class HeatmapService {

    /**
     * 后端计算热力图格网数据
     */
    public HeatmapResult computeHeatmap(List<SpatialFeature> features,
                                         double minX, double minY,
                                         double maxX, double maxY,
                                         int gridWidth, int gridHeight,
                                         double radius) {

        int[][] intensity = new int[gridWidth][gridHeight];
        double cellW = (maxX - minX) / gridWidth;
        double cellH = (maxY - minY) / gridHeight;

        // 对于每个要素，在其影响半径内累加权重
        for (SpatialFeature f : features) {
            double cx = (f.getLng() - minX) / cellW;
            double cy = (f.getLat() - minY) / cellH;
            int r = (int) (radius / Math.min(cellW, cellH));

            for (int i = Math.max(0, (int) cx - r);
                 i < Math.min(gridWidth, (int) cx + r); i++) {
                for (int j = Math.max(0, (int) cy - r);
                     j < Math.min(gridHeight, (int) cy + r); j++) {
                    double dist = Math.sqrt(
                            (i - cx) * (i - cx) + (j - cy) * (j - cy));
                    if (dist < r) {
                        intensity[i][j] += (int) ((1 - dist / r) * 255);
                    }
                }
            }
        }

        return new HeatmapResult(gridWidth, gridHeight, intensity,
                minX, minY, maxX, maxY);
    }

    @Data
    @AllArgsConstructor
    public static class HeatmapResult {
        private int width;
        private int height;
        private int[][] intensity;
        private double bboxMinX, bboxMinY, bboxMaxX, bboxMaxY;
    }
}
```

## 五、渐进式加载

```java
/**
 * 渐进式加载：先返回聚合数据，再加载细节
 */
@GetMapping("/progressive")
public Mono<ProgressiveData> progressiveLoad(
        @RequestParam int zoom,
        @RequestParam double minX, @RequestParam double minY,
        @RequestParam double maxX, @RequestParam double maxY) {

    ProgressiveData data = new ProgressiveData();

    if (zoom < 12) {
        // 低层级：只返回 Geohash 聚合数据
        data.setType("aggregation");
        data.setData(aggregationService.geohashAggregate(minX, minY, maxX, maxY, zoom));
    } else if (zoom < 16) {
        // 中层级：返回简化后的要素（只保留主要属性）
        List<SpatialFeature> features = repository.findByBbox(minX, minY, maxX, maxY);
        data.setType("simplified");
        data.setData(simplifyFeatures(features));
    } else {
        // 高层级：返回完整要素
        List<SpatialFeature> features = repository.findByBbox(minX, minY, maxX, maxY);
        data.setType("full");
        data.setData(features);
    }

    return Mono.just(data);
}
```

## 六、总结

- 海量空间数据渲染的核心策略是：**先聚合、后简化、再传输**
- 矢量瓦片（MVT）预生成 + CDN 缓存是最优的大规模数据方案
- Geohash 聚合和 Grid 聚合适合低缩放级别的概览展示
- 热力图后端计算 + 格网化传输，前端只需渲染图片，性能最佳
- 渐进式加载：低层级用聚合，高层级用细节，兼顾性能和体验
