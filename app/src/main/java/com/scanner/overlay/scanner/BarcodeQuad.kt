package com.scanner.overlay.scanner

import android.graphics.Point

/**
 * Геометрия найденного штрихкода в координатах кадра.
 *
 * [points] — четыре точки по часовой стрелке начиная с левой верхней, как их отдаёт
 * ML Kit (`Barcode.getCornerPoints()`): из-за перспективной дисторсии это не
 * прямоугольник, поэтому храним именно углы, а не `boundingBox`.
 *
 * [frameWidth] / [frameHeight] — размеры кадра **после поворота** (upright), а не
 * `imageProxy.width/height`. ML Kit считает координаты в повёрнутом пространстве,
 * поэтому экранная геометрия обязана считаться от тех же размеров, иначе контур уезжает.
 */
data class BarcodeQuad(
    val points: List<Point>,
    val frameWidth: Int,
    val frameHeight: Int
)