package com.scanner.overlay.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.Code128Writer

object BarcodeGenerator {
    fun ean13Bitmap(code: String, pxWidth: Int, pxHeight: Int): Bitmap? {
        val digits = code.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val pair: Pair<Writer, String>? = when (digits.length) {
            13 -> EAN13Writer() to digits
            12 -> EAN13Writer() to digits
            else -> Code128Writer() to digits
        }

        val (writer, content) = pair ?: return null
        val format = if (writer is EAN13Writer) BarcodeFormat.EAN_13 else BarcodeFormat.CODE_128
        return try {
            val matrix = writer.encode(content, format, pxWidth.coerceAtLeast(64), pxHeight)
            val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
