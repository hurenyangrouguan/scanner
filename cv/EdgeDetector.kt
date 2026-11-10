package com.example.docscanner.cv

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object EdgeDetector {

    /**
     * @param minAreaRatio 候选四边形面积占全图的最小比例，过滤误检
     */
    fun detectCorners(bitmap: Bitmap, minAreaRatio: Double = 0.20): List<PointF>? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val totalArea = src.cols().toDouble() * src.rows()

        val maxDim = 1200
        val scale = maxDim.toDouble() / max(src.cols(), src.rows())
        val resized = if (scale < 1.0) {
            Mat().also { Imgproc.resize(src, it, Size(src.cols() * scale, src.rows() * scale)) }
        } else src

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Mat.ones(3, 3, CvType.CV_8U))

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(),
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )

        var best: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val curve = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(curve, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val areaInResized = Imgproc.contourArea(approx)
                val areaInOriginal = areaInResized / (if (scale < 1.0) scale * scale else 1.0)
                if (areaInOriginal > bestArea && areaInOriginal > totalArea * minAreaRatio) {
                    bestArea = areaInOriginal
                    best = approx
                }
            }
            curve.release()
        }

        val result = best?.toArray()?.map {
            PointF(
                (it.x / (if (scale < 1.0) scale else 1.0)).toFloat(),
                (it.y / (if (scale < 1.0) scale else 1.0)).toFloat()
            )
        }?.let { orderCorners(it) }

        src.release()
        if (resized !== src) resized.release()
        gray.release(); edges.release()
        return result
    }

    fun orderCorners(points: List<PointF>): List<PointF> {
        val bySum = points.sortedBy { it.x + it.y }
        val topLeft = bySum.first()
        val bottomRight = bySum.last()
        val rest = points - topLeft - bottomRight
        val byDiff = rest.sortedBy { it.x - it.y }
        val topRight = byDiff.last()
        val bottomLeft = byDiff.first()
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}
