package com.scanner.overlay.scanner

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ArticleBarcodeDatabase {

    private const val EXTRA_FILE = "barcode_extra.csv"

    private val items = mutableListOf<ProductItem>()
    private val seenArticleCodes = HashSet<String>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromAssets(context)
            loadFromExtra(context)
            loaded = true
        }
    }

    private fun loadFromAssets(context: Context) {
        try {
            val stream = context.assets.open("barcode-products.csv")
            val reader = BufferedReader(InputStreamReader(stream))
            reader.readLine()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split(";")
                if (parts.size < 14) continue
                val articleCode = parts[4].trim()
                val name = parts[5].trim()
                val barcode = parts[13].trim()
                if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) continue
                seenArticleCodes.add(articleCode)
                items.add(ProductItem(articleCode, name, barcode))
            }
            reader.close()
        } catch (_: Exception) {}
    }

    private fun loadFromExtra(context: Context) {
        try {
            val file = File(context.filesDir, EXTRA_FILE)
            if (!file.exists()) return
            val reader = BufferedReader(InputStreamReader(FileInputStream(file)))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split(";")
                if (parts.size < 14) continue
                val articleCode = parts[4].trim()
                val name = parts[5].trim()
                val barcode = parts[13].trim()
                if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) continue
                seenArticleCodes.add(articleCode)
                items.add(ProductItem(articleCode, name, barcode))
            }
            reader.close()
        } catch (_: Exception) {}
    }

    fun mergeExtra(newItems: List<ProductItem>) {
        items.addAll(newItems)
        for (item in newItems) {
            seenArticleCodes.add(item.articleCode)
        }
    }

    fun persistExtra(context: Context, newItems: List<ProductItem>) {
        try {
            val file = File(context.filesDir, EXTRA_FILE)
            val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true)))
            for (item in newItems) {
                writer.write(";;;;${item.articleCode};${item.name};;;;;;;;${item.barcode};;;;;;;")
                writer.newLine()
            }
            writer.close()
        } catch (_: Exception) {}
    }

    fun containsArticleCode(code: String): Boolean {
        return seenArticleCodes.contains(code)
    }

    fun searchByArticleCode(code: String): ProductItem? {
        return items.firstOrNull { it.articleCode == code }
    }
}
