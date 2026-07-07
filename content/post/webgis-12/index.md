---
title: "Java WebGIS 实战指南（十二）：前端地图展示"
date: 2021-11-23
draft: false
categories: ["WebGIS"]
tags: ["WebGIS", "OpenLayers", "Leaflet", "前端展示"]
toc: true
---

## 前言

虽然本系列聚焦 Java 后端，但 WebGIS 最终的服务对象是前端地图展示。本文简要介绍如何使用 OpenLayers 和 Leaflet 加载我们前面构建的后端 GIS 服务（WMS、WFS、矢量瓦片），帮助读者串联前后端。

> **📚 系列文章导航**
> - [概述与技术栈选型]({{< relref "post/webgis-01" >}})
> - [WebGIS 性能优化实战]({{< relref "post/webgis-11" >}})
> - 本文：前端地图展示
> - [返回系列首篇]({{< relref "post/webgis-01" >}})

<!--more-->

## 一、OpenLayers 加载 WMS

```html
<!DOCTYPE html>
<html>
<head>
    <title>WebGIS 地图展示</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/ol@v8.2.0/ol.css">
    <script src="https://cdn.jsdelivr.net/npm/ol@v8.2.0/dist/ol.min.js"></script>
</head>
<body>
<div id="map" style="width: 100%; height: 100vh;"></div>
<script>
    // 基础底图（OSM）
    const osm = new ol.layer.Tile({
        source: new ol.source.OSM()
    });

    // WMS 图层（加载我们的后端 WMS 服务）
    const wmsLayer = new ol.layer.Tile({
        source: new ol.source.TileWMS({
            url: 'http://localhost:8080/wms/map',
            params: {
                'LAYERS': 'spatial_feature',
                'TILED': true
            },
            serverType: 'geoserver'
        })
    });

    const map = new ol.Map({
        target: 'map',
        layers: [osm, wmsLayer],
        view: new ol.View({
            center: ol.proj.fromLonLat([116.4, 39.9]),
            zoom: 12
        })
    });
</script>
</body>
</html>
```

## 二、Leaflet 加载 WFS GeoJSON

```html
<script>
    const map = L.map('map').setView([39.9, 116.4], 12);

    // 底图
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19
    }).addTo(map);

    // WFS GeoJSON 图层
    const wfsLayer = new L.GeoJSON.AJAX(
        'http://localhost:8080/wfs?request=GetFeature&outputFormat=application/json', {
            style: function(feature) {
                return {
                    color: '#ff0000',
                    weight: 2,
                    opacity: 0.8
                };
            },
            onEachFeature: function(feature, layer) {
                layer.bindPopup(feature.properties.name || '未命名');
            }
        }
    ).addTo(map);
</script>
```

## 三、矢量瓦片加载

```javascript
// OpenLayers 加载 MVT 矢量瓦片
const mvtLayer = new ol.layer.VectorTile({
    source: new ol.source.VectorTile({
        format: new ol.format.MVT(),
        url: 'http://localhost:8080/tiles/{z}/{x}/{y}.pbf'
    }),
    style: function(feature) {
        return new ol.style.Style({
            stroke: new ol.style.Stroke({
                color: '#3399CC',
                width: 1.25
            }),
            fill: new ol.style.Fill({
                color: 'rgba(51,153,204,0.2)'
            })
        });
    }
});
```

## 四、空间查询交互

```javascript
// 点击地图查询要素（WFS GetFeature）
map.on('click', async function(evt) {
    const lonlat = ol.proj.toLonLat(evt.coordinate);
    const [lng, lat] = lonlat;

    // 调用 WFS 查询
    const url = `http://localhost:8080/wfs?request=GetFeature`
              + `&bbox=${lng-0.01},${lat-0.01},${lng+0.01},${lat+0.01}`
              + `&outputFormat=application/json`;

    const response = await fetch(url);
    const geojson = await response.json();

    if (geojson.features.length > 0) {
        const name = geojson.features[0].properties.name;
        alert('查找到: ' + name);
    }
});

// 周边查询
async function queryNearby(lng, lat, distance) {
    const url = `http://localhost:8080/api/spatial/nearby`
              + `?lng=${lng}&lat=${lat}&distance=${distance}`;
    const response = await fetch(url);
    const data = await response.json();
    // 在地图上标记结果
    L.geoJSON(data).addTo(map);
}
```

## 五、前端技术选型对比

| 框架 | 适用场景 | 优缺点 |
|------|---------|--------|
| **OpenLayers** | 企业级 WebGIS、大量 WMS/WFS/WMTS 标准服务 | 功能最全，但 API 较复杂 |
| **Leaflet** | 轻量级地图、POI 展示 | 简单易用，插件生态丰富 |
| **Mapbox GL** | 矢量瓦片、3D 地图 | 性能好，但商用有限制 |
| **Cesium** | 三维地球、时空数据 | 3D 能力最强 |

## 六、总结

- 前端通过 WMS/WMTS 加载动态渲染的地图图片
- 前端通过 WFS（GeoJSON）加载可编辑的矢量数据
- MVT 矢量瓦片是海量数据展示的最优方案
- 前后端交互的核心是空间查询 API：点击查属性、范围查要素、距离查周边
- 整个 WebGIS 系列从技术栈选型 → 后端服务 → 前端展示，已形成一个完整的闭环
