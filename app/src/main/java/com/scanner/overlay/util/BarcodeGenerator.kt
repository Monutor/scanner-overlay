package com.scanner.overlay.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.Writer
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.Code128Writer

object BarcodeGenerator {
    /**
     * Renders [code] as EAN-13 (12 or 13 digits) or CODE_128 (any other length).
     *
     * Returns null when the barcode cannot be rendered faithfully, in particular for a
     * 13-digit EAN-13 whose check digit is wrong. ZXing's [EAN13Writer] trusts the 13th
     * digit and never recomputes it, so accepting it would print a perfectly valid barcode
     * that scans as a *different* article - the worst possible failure here, because the
     * staff member would put the wrong item into production.
     */
    fun ean13Bitmap(code: String, pxWidth: Int, pxHeight: Int): Bitmap? {
        // ASCII digits only: Char.isDigit() also accepts Unicode digits, which would end up
        // in the generated barcode and are rejected by ZXing's checkNumeric().
        val digits = code.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null

        val pair: Pair<Writer, String>? = when (digits.length) {
            13 -> if (ean13CheckDigit(digits) == digits[12]) {
                EAN13Writer() to digits
            } else {
                null
            }
            12 -> EAN13Writer() to digits
            else -> Code128Writer() to digits
        }

        val (writer, content) = pair ?: return null
        val format = if (writer is EAN13Writer) BarcodeFormat.EAN_13 else BarcodeFormat.CODE_128
        return try {
            val matrix = writer.encode(content, format, pxWidth.coerceAtLeast(64), pxHeight)
            val w = matrix.width
            val h = matrix.height
            // Bulk fill: ~10x faster than per-pixel setPixel in a nested loop.
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * EAN-13 check digit for a 12-digit payload: digits in odd positions (1st, 3rd, ...) count
     * as-is, digits in even positions are tripled; the total modulo 10 is complemented to 10.
     */
    private fun ean13CheckDigit(payload12: String): Char {
        var sum = 0
        for (i in 0 until 12) {
            val digit = payload12[i] - '0'
            sum += if (i % 2 == 0) digit else digit * 3
        }
        return ('0' + (10 - sum % 10) % 10)
    }
}
