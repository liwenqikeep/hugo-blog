package com.webgis.common.util;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.data.FeatureWriter;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.locationtech.jts.geom.*;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.*;
import java.util.*;

public class GeoToolsUtil {

    private static final GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory();

    public static FeatureCollection<SimpleFeatureType, SimpleFeature> readShapefile(String path) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("url", new File(path).toURI().toURL());
        DataStore dataStore = DataStoreFinder.getDataStore(params);
        String typeName = dataStore.getTypeNames()[0];
        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore.getFeatureSource(typeName);
        return source.getFeatures();
    }

    public static Point createPoint(double x, double y) {
        return geometryFactory.createPoint(new Coordinate(x, y));
    }

    public static String featureCollectionToGeoJson(FeatureCollection<?, ?> collection) throws IOException {
        FeatureJSON fjson = new FeatureJSON();
        try (StringWriter writer = new StringWriter()) {
            fjson.writeFeatureCollection(collection, writer);
            return writer.toString();
        }
    }
}
