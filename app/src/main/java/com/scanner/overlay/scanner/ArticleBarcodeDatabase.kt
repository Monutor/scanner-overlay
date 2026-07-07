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
                addFromCsvLine(line!!)
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
                addFromCsvLine(line!!)
            }
            reader.close()
        } catch (_: Exception) {}
    }

    private fun addFromCsvLine(line: String) {
        val parts = line.split(";")
        var articleCode: String
        var name: String
        var barcode: String
        if (parts.size >= 14) {
            articleCode = parts[4].trim()
            name = parts[5].trim()
            barcode = parts[13].trim()
        } else if (parts.size >= 3) {
            articleCode = parts[0].trim()
            name = parts[1].trim()
            barcode = parts[2].trim()
        } else {
            return
        }
        if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) return
        seenArticleCodes.add(articleCode)
        items.add(ProductItem(articleCode, name, barcode))
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

    fun persistExtra(context: Context) {
        try {
            val file = File(context.filesDir, EXTRA_FILE)
            val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, false)))
            for (item in items) {
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

    fun getAllItems(): List<ProductItem> {
        if (!loaded) return emptyList()
        return items.toList()
    }

    fun exportToCsv(): String {
        val sb = StringBuilder()
        sb.appendLine("articleCode;name;barcode")
        for (item in items) {
            sb.appendLine("${item.articleCode};${item.name};${item.barcode}")
        }
        return sb.toString()
    }

    fun importFromRemote(csvText: String): List<ProductItem> {
        val newItems = mutableListOf<ProductItem>()
        try {
            val lines = csvText.lines()
            var startIndex = 0
            if (lines.isNotEmpty() && lines[0].startsWith("articleCode")) {
                startIndex = 1
            }
            for (i in startIndex until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val parts = line.split(";")
                var articleCode: String
                var name: String
                var barcode: String
                if (parts.size >= 14) {
                    articleCode = parts[4].trim()
                    name = parts[5].trim()
                    barcode = parts[13].trim()
                } else if (parts.size >= 3) {
                    articleCode = parts[0].trim()
                    name = parts[1].trim()
                    barcode = parts[2].trim()
                } else {
                    continue
                }
                if (articleCode.isEmpty() || seenArticleCodes.contains(articleCode)) continue
                newItems.add(ProductItem(articleCode, name, barcode))
            }
        } catch (_: Exception) {}
        return newItems
    }

    fun mergeRemoteProducts(context: Context, newItems: List<ProductItem>) {
        if (newItems.isEmpty()) return
        mergeExtra(newItems)
        persistExtra(context, newItems)
    }
}
