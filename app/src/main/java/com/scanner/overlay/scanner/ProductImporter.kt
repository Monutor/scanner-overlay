package com.scanner.overlay.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class ProductImportReport(
    val count: Int,
    val errors: List<String> = emptyList()
)

object ProductImporter {
    private const val TAG = "ProductImporter"

    fun import(context: Context, uri: Uri, fileName: String): ProductImportReport {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ProductImportReport(0, errors = listOf("Не удалось открыть файл"))

        return inputStream.use { stream ->
            val buffered = if (stream is java.io.BufferedInputStream) stream else java.io.BufferedInputStream(stream)
            val items: List<ProductItem> = when {
                fileName.endsWith(".csv", true) -> parseCsv(buffered)
                fileName.endsWith(".xlsx", true) -> parseXlsx(buffered)
                else -> {
                    buffered.mark(4)
                    val header = ByteArray(4)
                    val read = buffered.read(header)
                    buffered.reset()
                    if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
                        parseXlsx(buffered)
                    } else {
                        parseCsv(buffered)
                    }
                }
            }

            val existingCount = items.count { ArticleBarcodeDatabase.containsArticleCode(it.articleCode) }
            val newItems = items.filter { !ArticleBarcodeDatabase.containsArticleCode(it.articleCode) }

            if (newItems.isEmpty()) {
                return ProductImportReport(0, errors = listOf("Все артикулы уже есть в базе"))
            }

            ArticleBarcodeDatabase.mergeExtra(newItems)
            ArticleBarcodeDatabase.persistExtra(context, newItems)

            ProductImportReport(
                count = newItems.size,
                errors = if (existingCount > 0) listOf("$existingCount пропущено (уже есть)") else emptyList()
            )
        }
    }

    private fun parseCsv(inputStream: InputStream): List<ProductItem> {
        val text = inputStream.bufferedReader(Charsets.UTF_8).readText()
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val separator = detectSeparator(lines)
        val header = lines[0].trim().lowercase()
        val headerParts = header.split(separator).map { it.trim() }

        val hasHeader = headerParts.any { it in listOf("articlecode", "barcode", "name", "article", "sku") }

        val colMap = if (hasHeader) {
            mapOf(
                "articleCode" to (headerParts.indexOfFirst { it in listOf("articlecode", "article", "sku", "code") }.takeIf { it >= 0 }),
                "name" to (headerParts.indexOfFirst { it in listOf("name", "product", "title", "товар") }.takeIf { it >= 0 }),
                "barcode" to (headerParts.indexOfFirst { it in listOf("barcode", "ean", "upc", "штрихкод", "шк") }.takeIf { it >= 0 })
            )
        } else null

        val items = mutableListOf<ProductItem>()
        val startIdx = if (hasHeader) 1 else 0
        for (i in startIdx until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            val parts = line.split(separator).map { it.trim() }

            val articleCode: String
            val name: String
            val barcode: String

            if (colMap != null) {
                articleCode = colMap["articleCode"]?.let { parts.getOrNull(it)?.trim() ?: "" } ?: ""
                name = colMap["name"]?.let { parts.getOrNull(it)?.trim() ?: "" } ?: ""
                barcode = colMap["barcode"]?.let { parts.getOrNull(it)?.trim() ?: "" } ?: ""
            } else {
                articleCode = parts.getOrNull(0) ?: ""
                name = parts.getOrNull(1) ?: ""
                barcode = parts.getOrNull(2) ?: ""
            }

            if (articleCode.isBlank()) continue
            items.add(ProductItem(articleCode, name, barcode))
        }

        return items
    }

    private fun parseXlsx(inputStream: InputStream): List<ProductItem> {
        val tempFile = File.createTempFile("xlsx_", ".xlsx")
        try {
            tempFile.outputStream().use { inputStream.copyTo(it) }
            return parseXlsxFile(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun parseXlsxFile(file: File): List<ProductItem> {
        val entries = readZipEntries(file)

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val rows = entries["xl/worksheets/sheet1.xml"]?.let { parseSheet(it, sharedStrings) } ?: emptyList()

        if (rows.isEmpty()) return emptyList()

        val headerCells = rows[0]
        val headerKeys = headerCells.map { it.lowercase().trim() }
        val hasHeader = headerKeys.any { it in listOf("articlecode", "barcode", "name", "article", "sku") }

        val colMap = if (hasHeader) {
            mapOf(
                "articleCode" to (headerKeys.indexOfFirst { it in listOf("articlecode", "article", "sku", "code") }.takeIf { it >= 0 }),
                "name" to (headerKeys.indexOfFirst { it in listOf("name", "product", "title", "товар") }.takeIf { it >= 0 }),
                "barcode" to (headerKeys.indexOfFirst { it in listOf("barcode", "ean", "upc", "штрихкод", "шк") }.takeIf { it >= 0 })
            )
        } else null

        val items = mutableListOf<ProductItem>()
        val startIdx = if (hasHeader) 1 else 0

        for (i in startIdx until rows.size) {
            val row = rows[i]
            if (row.size < 2) continue

            val articleCode: String
            val name: String
            val barcode: String

            if (colMap != null) {
                articleCode = colMap["articleCode"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                name = colMap["name"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                barcode = colMap["barcode"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
            } else {
                articleCode = row.getOrNull(0) ?: ""
                name = row.getOrNull(1) ?: ""
                barcode = row.getOrNull(2) ?: ""
            }

            if (articleCode.isBlank()) continue
            items.add(ProductItem(articleCode, name, barcode))
        }

        return items
    }

    private fun readZipEntries(file: File): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.getInputStream(entry).readBytes()
                }
            }
        }
        return entries
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(data), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "t") {
                    strings.add(parser.nextText())
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sharedStrings.xml", e)
        }
        return strings
    }

    private fun parseSheet(data: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(data), "UTF-8")

            var currentRow = mutableListOf<String>()
            var inRow = false
            var inCell = false
            var cellType = ""
            var inlineText = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "row" -> {
                                currentRow = mutableListOf()
                                inRow = true
                            }
                            "c" -> {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                                inlineText = false
                                inCell = true
                            }
                            "is" -> inlineText = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inCell && inRow) {
                            val text = parser.text.trim()
                            when {
                                inlineText -> currentRow.add(text)
                                cellType == "s" -> {
                                    val idx = text.toIntOrNull()
                                    if (idx != null && idx < sharedStrings.size) {
                                        currentRow.add(sharedStrings[idx])
                                    } else {
                                        currentRow.add(text)
                                    }
                                }
                                cellType == "" || cellType == "str" -> {
                                    currentRow.add(text)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "row" -> {
                                if (currentRow.isNotEmpty()) rows.add(currentRow.toList())
                                inRow = false
                            }
                            "c" -> inCell = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sheet1.xml", e)
        }
        return rows
    }

    private fun detectSeparator(lines: List<String>): Char {
        if (lines.isEmpty()) return ','
        val semicolonCount = lines.sumOf { line -> line.count { it == ';' } }
        val commaCount = lines.sumOf { line -> line.count { it == ',' } }
        return if (semicolonCount > commaCount) ';' else ','
    }
}
