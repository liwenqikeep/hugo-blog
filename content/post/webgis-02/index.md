---
title: "Java WebGIS 实战指南（二）：Spring Boot 集成 GeoTools"
date: 2021-11-03
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "GeoTools", "Spring Boot", "Maven"]
toc: true
---

## 前言

GeoTools 是一个开源的 Java GIS 工具包，提供了对空间数据读写、坐标转换、WMS/WFS 服务的完整支持。本文将实战 Spring Boot 集成 GeoTools，构建 WebGIS 后端的基础骨架。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - 本文：Spring Boot 集成 GeoTools
> - [下一篇：PostGIS 空间数据库实战]({{< relref "post/webgis-03" >}})
> - [GeoTools 空间数据读写实战]({{< relref "post/webgis-04" >}})
> - [JTS 空间计算引擎]({{< relref "post/webgis-05" >}})
> - [OGC WMS/WMTS 服务实战]({{< relref "post/webgis-06" >}})
> - [OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、项目骨架搭建

```
webgis-system/
├── pom.xml
├── webgis-common/             # 公共模块
├── webgis-core/               # 核心业务
├── webgis-data/               # 数据层
├── webgis-service/            # GIS 服务层（WMS/WFS）
└── webgis-boot/               # Spring Boot 启动
```

## 二、GeoTools 核心依赖

```xml
<repositories>
    <repository>
        <id>osgeo</id>
        <name>OSGeo Release Repository</name>
        <url>https://repo.osgeo.org/repository/release/</url>
    </repository>
</repositories>

<dependencies>
    <!-- GeoTools 核心 -->
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-main</artifactId>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-data</artifactId>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-shapefile</artifactId>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-geojson</artifactId>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-epsg-hsql</artifactId>
    </dependency>
</dependencies>
```

## 三、GeoTools 基础工具类

```java
package com.webgis.common.util;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.locationtech.jts.geom.*;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.util.HashMap;
import java.util.Map;

/**
 * GeoTools 工具类
 */
public class GeoToolsUtil {

    private static final GeometryFactory geometryFactory =
            JTSFactoryFinder.getGeometryFactory();

    /**
     * 读取 Shapefile
     */
    public static FeatureCollection<SimpleFeatureType, SimpleFeature> readShapefile(String path)
            throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("url", new java.io.File(path).toURI().toURL());
        DataStore dataStore = DataStoreFinder.getDataStore(params);
        String typeName = dataStore.getTypeNames()[0];
        FeatureSource<SimpleFeatureType, SimpleFeature> source =
                dataStore.getFeatureSource(typeName);
        return source.getFeatures();
    }

    /**
     * 创建点几何
     */
    public static Point createPoint(double x, double y) {
        Coordinate coord = new Coordinate(x, y);
        return geometryFactory.createPoint(coord);
    }

    /**
     * 创建线几何
     */
    public static LineString createLineString(Coordinate[] coords) {
        return geometryFactory.createLineString(coords);
    }

    /**
     * 创建面几何
     */
    public static Polygon createPolygon(Coordinate[] coords) {
        return geometryFactory.createPolygon(coords);
    }
}
```

## 四、坐标转换

```java
package com.webgis.common.util;

import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

/**
 * 坐标转换工具
 */
public class CoordinateTransformUtil {

    /**
     * WGS84 经纬度 → Web Mercator 平面坐标
     */
    public static double[] wgs84ToMercator(double lng, double lat) throws Exception {
        CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
        CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:3857");
        MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);

        double[] srcPt = {lng, lat};
        double[] dstPt = new double[2];
        transform.transform(srcPt, 0, dstPt, 0, 1);
        return dstPt;
    }

    /**
     * Web Mercator → WGS84
     */
    public static double[] mercatorToWgs84(double x, double y) throws Exception {
        CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:3857");
        CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:4326");
        MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);

        double[] srcPt = {x, y};
        double[] dstPt = new double[2];
        transform.transform(srcPt, 0, dstPt, 0, 1);
        return dstPt;
    }
}
```

## 五、总结

- GeoTools 提供了完整的 GIS 工具链：数据读写、坐标转换、WMS/WFS
- Spring Boot 集成 GeoTools 的关键是配置 OSGeo Maven 仓库
- 坐标转换是 WebGIS 的基础能力，GeoTools 的 CRS 模块提供了丰富的坐标系支持
