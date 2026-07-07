package com.webgis.common.util;

import org.geotools.referencing.CRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

public class CoordinateTransformUtil {

    public static double[] wgs84ToMercator(double lng, double lat) throws Exception {
        CoordinateReferenceSystem sourceCRS = CRS.decode("EPSG:4326");
        CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:3857");
        MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);
        double[] srcPt = {lng, lat};
        double[] dstPt = new double[2];
        transform.transform(srcPt, 0, dstPt, 0, 1);
        return dstPt;
    }

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
