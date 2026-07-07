---
title: "Java WebGIS 实战指南（三）：PostGIS 空间数据库实战"
date: 2021-11-05
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "PostGIS", "空间数据库", "GIS", "Spring Data JPA"]
toc: true
---

## 前言

PostGIS 是 PostgreSQL 的空间数据库扩展，它让关系数据库具备存储和查询空间数据的能力。对于 WebGIS 系统，PostGIS 是最主流的选择。

本文从建表到查询，完整演示 Spring Boot + PostGIS 的实战集成。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [Spring Boot 集成 GeoTools 实战]({{< relref "post/webgis-02" >}})
> - 本文：PostGIS 空间数据库实战
> - [下一篇：GeoTools 空间数据读写实战]({{< relref "post/webgis-04" >}})
> - [JTS 空间计算引擎]({{< relref "post/webgis-05" >}})
> - [OGC WMS/WMTS 服务实战]({{< relref "post/webgis-06" >}})
> - [OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、PostGIS 安装与配置

```sql
-- 创建空间数据库
CREATE DATABASE webgis;
\c webgis;

-- 启用 PostGIS 扩展
CREATE EXTENSION postgis;
CREATE EXTENSION postgis_topology;

-- 验证安装
SELECT PostGIS_Version();
```

## 二、表结构设计

```sql
-- 地理要素表
CREATE TABLE spatial_feature (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    geom GEOMETRY(Geometry, 4326) NOT NULL,  -- WGS84 坐标系
    feature_type VARCHAR(50),
    properties JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW()
);

-- 创建空间索引
CREATE INDEX idx_spatial_feature_geom
    ON spatial_feature USING GIST (geom);
```

## 三、Spring Data JPA 实体

```java
@Entity
@Table(name = "spatial_feature")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpatialFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(columnDefinition = "geometry(Geometry, 4326)")
    private String geom;  // WKT 格式

    @Column(name = "feature_type")
    private String featureType;

    private String properties;  // JSON

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

## 四、空间查询 Repositiory

```java
@Repository
public interface SpatialFeatureRepository extends JpaRepository<SpatialFeature, Long> {

    /**
     * 空间范围查询：查找在指定矩形区域内的要素
     */
    @Query(value = "SELECT * FROM spatial_feature " +
           "WHERE ST_Intersects(geom, ST_MakeEnvelope(:minX, :minY, :maxX, :maxY, 4326))",
           nativeQuery = true)
    List<SpatialFeature> findByBbox(@Param("minX") double minX,
                                     @Param("minY") double minY,
                                     @Param("maxX") double maxX,
                                     @Param("maxY") double maxY);

    /**
     * 距离查询：查找指定点周围 N 米内的要素
     */
    @Query(value = "SELECT *, ST_Distance(geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) AS dist " +
           "FROM spatial_feature " +
           "WHERE ST_DWithin(geom::geography, " +
           "  ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :distance) " +
           "ORDER BY dist",
           nativeQuery = true)
    List<Object[]> findByDistance(@Param("lng") double lng,
                                   @Param("lat") double lat,
                                   @Param("distance") double distance);

    /**
     * 空间包含查询：查找包含指定点的要素
     */
    @Query(value = "SELECT * FROM spatial_feature " +
           "WHERE ST_Contains(geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))",
           nativeQuery = true)
    List<SpatialFeature> findContains(@Param("lng") double lng, @Param("lat") double lat);
}
```

## 五、空间查询服务

```java
@Service
@RequiredArgsConstructor
public class SpatialQueryService {

    private final SpatialFeatureRepository repository;

    /**
     * 矩形范围查询（用于地图瓦片加载）
     */
    public List<SpatialFeature> queryByBbox(double minX, double minY,
                                             double maxX, double maxY) {
        return repository.findByBbox(minX, minY, maxX, maxY);
    }

    /**
     * 周边查询（POI 搜索）
     */
    public List<NearbyResult> queryNearby(double lng, double lat, double distance) {
        List<Object[]> results = repository.findByDistance(lng, lat, distance);
        List<NearbyResult> nearby = new ArrayList<>();
        for (Object[] row : results) {
            SpatialFeature feature = (SpatialFeature) row[0];
            double dist = ((Number) row[1]).doubleValue();
            nearby.add(new NearbyResult(feature, dist));
        }
        return nearby;
    }

    @Data
    @AllArgsConstructor
    public static class NearbyResult {
        private SpatialFeature feature;
        private double distance;  // 米
    }
}
```

## 六、总结

- PostGIS 通过 GIST 索引实现高效的空间查询
- `ST_Intersects`、`ST_DWithin`、`ST_Contains` 是最常用的空间关系函数
- Spring Data JPA 通过 `@Query` + native SQL 可以轻松集成 PostGIS 空间查询
- 空间数据推荐使用 `geometry(Geometry, 4326)` 类型 + WGS84 坐标系存储
