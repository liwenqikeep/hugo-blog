---
title: "Java WebGIS 实战指南（四）：GeoTools 空间数据读写实战"
date: 2021-11-07
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "GeoTools", "Shapefile", "GeoJSON", "空间数据"]
toc: true
---

## 前言

空间数据有 Shapefile、GeoJSON、KML、GML 等多种格式。GeoTools 提供了统一的数据访问 API，使得读写不同格式的数据变得简单。

本文演示 Shapefile 的读写、GeoJSON 的生成与解析、以及 PostGIS 与 GeoTools 的数据互通。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [Spring Boot 集成 GeoTools 实战]({{< relref "post/webgis-02" >}})
> - [PostGIS 空间数据库实战]({{< relref "post/webgis-03" >}})
> - 本文：GeoTools 空间数据读写实战
> - [下一篇：JTS 空间计算引擎]({{< relref "post/webgis-05" >}})
> - [OGC WMS/WMTS 服务实战]({{< relref "post/webgis-06" >}})
> - [OGC WFS 服务实战]({{< relref "post/webgis-07" >}})
> - [空间数据编码与传输优化]({{< relref "post/webgis-08" >}})
> - [空间数据搜索引擎设计]({{< relref "post/webgis-09" >}})
> - [海量空间数据的后端渲染]({{< relref "post/webgis-10" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - [前端地图展示]({{< relref "post/webgis-12" >}})

<!--more-->

## 一、Shapefile 读写

### 1.1 读取 Shapefile

```java
@Service
public class ShapefileService {

    /**
     * 读取 Shapefile 并转换为 GeoJSON
     */
    public String readShapefile(String filePath) throws Exception {
        File file = new File(filePath);
        Map<String, Object> params = new HashMap<>();
        params.put("url", file.toURI().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(params);
        String typeName = dataStore.getTypeNames()[0];

        FeatureSource<SimpleFeatureType, SimpleFeature> source =
                dataStore.getFeatureSource(typeName);
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();

        // 转换为 GeoJSON
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            GeoJSONWriter writer = new GeoJSONWriter(baos);
            writer.writeFeatureCollection(collection);
            writer.close();
            return baos.toString(StandardCharsets.UTF_8);
        }
    }
}
```

### 1.2 写入 Shapefile

```java
/**
 * 将 FeatureCollection 写入 Shapefile
 */
public void writeShapefile(FeatureCollection<SimpleFeatureType, SimpleFeature> collection,
                            String outputPath) throws Exception {
    File outputFile = new File(outputPath);

    Map<String, Object> params = new HashMap<>();
    params.put("url", outputFile.toURI().toURL());

    DataStoreFactorySpi factory = new ShapefileDataStoreFactory();
    ShapefileDataStore newDataStore = (ShapefileDataStore) factory.createNewDataStore(params);

    // 设置 Schema
    SimpleFeatureType schema = collection.getSchema();
    newDataStore.createSchema(schema);

    // 写入数据
    Transaction transaction = new DefaultTransaction("create");
    FeatureStore<SimpleFeatureType, SimpleFeature> featureStore =
            (FeatureStore<SimpleFeatureType, SimpleFeature>) newDataStore.getFeatureSource(schema.getTypeName());
    featureStore.setTransaction(transaction);

    try {
        featureStore.addFeatures(collection);
        transaction.commit();
    } catch (Exception e) {
        transaction.rollback();
        throw e;
    } finally {
        transaction.close();
        newDataStore.dispose();
    }
}
```

## 二、GeoJSON 解析与生成

```java
@Service
public class GeoJsonService {

    /**
     * 将 FeatureCollection 编码为 GeoJSON 字符串
     */
    public String toGeoJson(FeatureCollection<SimpleFeatureType, SimpleFeature> collection)
            throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            GeoJSONWriter writer = new GeoJSONWriter(baos);
            writer.writeFeatureCollection(collection);
            writer.close();
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析 GeoJSON 为 FeatureCollection
     */
    public FeatureCollection<SimpleFeatureType, SimpleFeature> fromGeoJson(String geojson)
            throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(
                geojson.getBytes(StandardCharsets.UTF_8))) {
            GeoJSONReader reader = new GeoJSONReader(bais);
            return reader.getFeatures();
        }
    }

    /**
     * 将单个 Java 对象转换为 GeoJSON Feature
     */
    public <T> String toGeoJsonFeature(T object, Function<T, Geometry> geometryExtractor,
                                        Function<T, Map<String, Object>> propertyExtractor)
            throws IOException {
        SimpleFeatureType type = createFeatureType(object.getClass().getSimpleName());
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(type);

        builder.add(geometryExtractor.apply(object));
        Map<String, Object> props = propertyExtractor.apply(object);
        props.forEach(builder::add);
        SimpleFeature feature = builder.buildFeature(null);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            GeoJSONWriter writer = new GeoJSONWriter(baos);
            writer.writeFeature(feature);
            writer.close();
            return baos.toString(StandardCharsets.UTF_8);
        }
    }
}
```

## 三、FeatureCollection 工具

```java
/**
 * 遍历 FeatureCollection
 */
public void iterateFeatures(FeatureCollection<SimpleFeatureType, SimpleFeature> collection) {
    try (FeatureIterator<SimpleFeature> iterator = collection.features()) {
        while (iterator.hasNext()) {
            SimpleFeature feature = iterator.next();
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            String name = (String) feature.getAttribute("name");

            System.out.printf("要素: %s, 几何类型: %s%n",
                    name, geometry.getGeometryType());
        }
    }
}
```

## 四、总结

- GeoTools 的 `DataStore` 抽象统一了不同格式的空间数据读写
- `ShapefileDataStore` 读写 Shapefile，`GeoJSONWriter/Reader` 处理 GeoJSON
- PostGIS 数据可以通过 `PostgisNGDataStoreFactory` 与 GeoTools 互通
- `FeatureCollection` → `FeatureIterator` 是遍历空间数据的标准模式
