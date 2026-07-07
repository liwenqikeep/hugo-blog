---
title: "Java WebGIS 实战指南（十一）：WebGIS 性能优化实战"
date: 2021-11-21
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "性能优化", "空间索引", "缓存", "CDN"]
toc: true
---

## 前言

性能是 WebGIS 系统的生命线。一个地图页面的加载时间从 1 秒变成 5 秒，用户体验的下降不是 4 倍而是 10 倍。本文从数据库、缓存、瓦片、网络四个维度总结 WebGIS 的性能优化方案。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - 本文：WebGIS 性能优化实战
> - [下一篇：前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、性能基线

### 1.1 性能指标

```
WebGIS 性能核心指标：

┌─────────────────────────────────────────────────────────┐
│  指标             | 目标              | 测量方式          │
├─────────────────────────────────────────────────────────┤
│  首屏加载时间      | < 2s             | Lighthouse        │
│  瓦片加载时间      | < 500ms          | 浏览器 Network    │
│  空间查询响应时间   | < 200ms          | 后端日志          │
│  数据导出时间      | < 5s (1万条)      | 压力测试          │
│  并发查询 QPS     | > 1000            | JMeter            │
└─────────────────────────────────────────────────────────┘
```

## 二、数据库优化

### 2.1 空间索引

```sql
-- GIST 空间索引（必须）
CREATE INDEX idx_geom_gist ON spatial_feature USING GIST (geom);

-- 覆盖索引（空间 + 属性联合查询）
CREATE INDEX idx_geom_type ON spatial_feature USING GIST (geom)
    WHERE feature_type = 'ROAD';

-- BRIN 索引（超大数据表，比 GIST 占用空间小）
CREATE INDEX idx_geom_brin ON spatial_feature USING BRIN (geom)
    WITH (pages_per_range = 32);
```

### 2.2 SQL 优化

```sql
-- ❌ 慢：在 WHERE 中使用函数
SELECT * FROM spatial_feature
WHERE ST_Area(ST_Transform(geom, 3857)) > 1000;

-- ✅ 快：预先计算并存储常用字段
SELECT * FROM spatial_feature
WHERE area_mercator > 1000;

-- ❌ 慢：不精确的 BBOX 条件
SELECT * FROM spatial_feature
WHERE geom && ST_MakeEnvelope(116, 39, 117, 40, 4326);

-- ✅ 快：使用 ST_Intersects（利用索引）
SELECT * FROM spatial_feature
WHERE ST_Intersects(geom, ST_MakeEnvelope(116, 39, 117, 40, 4326));
```

## 三、缓存策略

### 3.1 多级缓存

```
┌─────────────────────────────────────────────────────────┐
│     多级缓存架构                                         │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  第一级：浏览器缓存（HTTP Cache-Control）                  │
│    瓦片 Cache-Control: max-age=86400                    │
│                                                         │
│  第二级：CDN 缓存                                        │
│    预生成瓦片推送到 CDN，用户就近访问                     │
│                                                         │
│  第三级：Redis 内存缓存                                    │
│    热点数据 TTL 1 小时                                    │
│                                                         │
│  第四级：本地内存缓存（Caffeine）                           │
│    常用元数据、配置信息                                   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.2 本地缓存

```java
@Component
public class SpatialCacheManager {

    // 热点空间数据缓存（Caffeine）
    private final Cache<String, List<SpatialFeature>> localCache =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(10, TimeUnit.MINUTES)
                    .recordStats()
                    .build();

    // Redis 缓存
    @Autowired
    private RedisTemplate<String, byte[]> redisTemplate;

    public List<SpatialFeature> getFeatures(String cacheKey) {
        // 1. 查本地缓存
        List<SpatialFeature> cached = localCache.getIfPresent(cacheKey);
        if (cached != null) return cached;

        // 2. 查 Redis
        byte[] redisData = redisTemplate.opsForValue().get("spatial:" + cacheKey);
        if (redisData != null) {
            List<SpatialFeature> features = deserialize(redisData);
            localCache.put(cacheKey, features);
            return features;
        }

        return null;
    }
}
```

## 四、瓦片分发

```java
/**
 * 瓦片 CDN 预热
 */
@PostConstruct
public void initTileDistribution() {
    // 预生成瓦片后推送到 CDN
    List<String> tileUrls = preGenerateTileUrls(MIN_ZOOM, MAX_ZOOM);
    cdnClient.prefetch(tileUrls);
}
```

## 五、查询性能监控

```java
@Aspect
@Component
public class SpatialQueryMonitor {

    @Around("@annotation(MonitorQuery)")
    public Object monitorQuery(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;

            if (elapsed > 500) {
                log.warn("慢查询: {}ms, method={}", elapsed,
                        pjp.getSignature().toShortString());
            }

            // 记录指标
            queryMetrics.record(pjp.getSignature().getName(), elapsed);
            return result;
        } catch (Exception e) {
            queryMetrics.recordError(pjp.getSignature().getName());
            throw e;
        }
    }
}
```

## 六、总结

| 优化维度 | 关键策略 | 预期提升 |
|---------|---------|---------|
| 空间索引 | GIST 索引、覆盖索引 | 10-100x |
| 预计算 | 空间关系预存、常用聚合缓存 | 5-10x |
| 多级缓存 | 本地 + Redis + CDN 三级缓存 | 50-100x |
| 瓦片策略 | 预生成、矢量瓦片、渐进式加载 | 10x |
| 查询监控 | 慢查询日志、指标采集 | 持续性优化 |
