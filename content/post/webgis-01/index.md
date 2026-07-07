---
title: "Java WebGIS 实战指南（一）：概述与技术栈选型"
date: 2021-11-01
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "GeoTools", "JTS", "PostGIS", "技术选型"]
toc: true
---

## 前言

WebGIS（网络地理信息系统）是将地理空间数据通过 Web 技术进行发布、展示和分析的系统。在 Java 生态中，围绕空间数据的处理、存储和发布，有一套成熟且强大的技术栈。

本文从架构师视角，梳理 Java WebGIS 的整体技术图谱，帮助开发者快速了解各技术组件的定位和选型依据。

> **📚 系列文章导航**
> - 本文：概述与技术栈选型
> - [下一篇：Spring Boot 集成 GeoTools 实战]({{< relref "post/webgis-02" >}})
> - [PostGIS 空间数据库实战]({{< relref "post/webgis-03" >}})
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

## 一、WebGIS 系统架构概览

一个典型的 WebGIS 系统包含以下层次：

```
┌──────────────────────────────────────────────────────────────┐
│  客户端层        OpenLayers / Leaflet / Cesium / Mapbox GL   │
├──────────────────────────────────────────────────────────────┤
│  GIS 服务层      WMS / WMTS / WFS / WCS / 瓦片服务          │
├──────────────────────────────────────────────────────────────┤
│  空间数据引擎层   空间索引 / 空间查询 / 坐标转换 / 缓冲区分析  │
├──────────────────────────────────────────────────────────────┤
│  数据存储层       PostGIS / MySQL Spatial / Shapefile / GeoJSON
└──────────────────────────────────────────────────────────────┘
```

## 二、Java GIS 技术选型

### 2.1 核心库对比

| 技术 | 定位 | 适用场景 |
|------|------|---------|
| **GeoTools** | 全套 Java GIS 工具包 | 读写 Shapefile/GeoJSON、WMS/WFS 服务、坐标系转换 |
| **JTS** | 空间计算引擎 | 几何关系判断、缓冲区分析、空间运算 |
| **PostGIS** | 空间数据库扩展 | 海量空间数据存储与查询 |
| **GeoServer** | 开源 GIS 服务器 | 快速发布 OGC 标准服务 |

### 2.2 本系列技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot 3.x | 应用框架 | 集成与部署 |
| GeoTools 30+ | GIS 工具包 | 空间数据读写、WMS/WFS |
| JTS 1.19 | 空间计算 | 几何运算、空间关系 |
| PostGIS 3.x | 空间数据库 | 空间数据持久化 |
| OpenLayers | 前端地图库 | 地图展示 |

## 三、Maven 依赖配置

```xml
<!-- GeoTools 核心依赖 -->
<repositories>
    <repository>
        <id>osgeo</id>
        <name>OSGeo Release Repository</name>
        <url>https://repo.osgeo.org/repository/release/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-main</artifactId>
        <version>30.2</version>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-shapefile</artifactId>
        <version>30.2</version>
    </dependency>
    <dependency>
        <groupId>org.geotools</groupId>
        <artifactId>gt-geojson</artifactId>
        <version>30.2</version>
    </dependency>
    <!-- JTS -->
    <dependency>
        <groupId>org.locationtech.jts</groupId>
        <artifactId>jts-core</artifactId>
        <version>1.19.0</version>
    </dependency>
</dependencies>
```

## 四、总结

- Java WebGIS 的技术栈已非常成熟，GeoTools + JTS + PostGIS 是核心组合
- OGC 标准（WMS/WMFS/WFS）是互操作性的基础
- 从下一篇开始，我们将从 Spring Boot 集成 GeoTools 开始，逐步构建完整的 WebGIS 实战项目
