package com.scanner.overlay.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportReport(
    val count: Int,
    val errors: List<String> = emptyList(),
    val skipped: Int = 0
)

object ShelfImporter {
    private const val TAG = "ShelfImporter"
    private const val IMPORTED_FILE = "shelves_imported.json"

    fun import(context: Context, uri: Uri, fileName: String): ImportReport {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportReport(0, errors = listOf("Не удалось открыть файл"))

        return inputStream.use { stream ->
            val items = when {
                fileName.endsWith(".csv", true) -> parseCsv(stream)
                fileName.endsWith(".xlsx", true) -> parseXlsx(stream)
                else -> return ImportReport(0, errors = listOf("Неподдерживаемый формат. Используйте CSV или XLSX"))
            }

            if (items.isEmpty()) {
                return ImportReport(0, errors = listOf("Файл не содержит данных"))
            }

            saveToFile(context, items)
            ImportReport(count = items.size, skipped = 0)
        }
    }

    private fun parseCsv(inputStream: InputStream): List<WarehouseItem> {
        val reader = inputStream.bufferedReader(Charsets.UTF_8)
        val lines = reader.readLines()
        if (lines.isEmpty()) return emptyList()

        val items = mutableListOf<WarehouseItem>()
        val errors = mutableListOf<String>()

        val header = lines[0].trim().lowercase()
        val hasHeader = header.contains("name") || header.contains("barcode") || header.contains("section")

        val startIdx = if (hasHeader) 1 else 0

        for (i in startIdx until lines.size) {
            val line = lines[i].trim()
            if (line.isBlank()) continue
            val parts = line.split(",", limit = 6)
            if (parts.size < 4) {
                errors.add("Строка ${i + 1}: недостаточно полей")
                continue
            }
            val barcode = parts[1].trim()
            if (barcode.isBlank()) {
                errors.add("Строка ${i + 1}: пустой ШК")
                continue
            }
            items.add(
                WarehouseItem(
                    name = parts[0].trim(),
                    barcode = barcode,
                    section = parts[2].trim(),
                    type = parts[3].trim(),
                    number = if (parts.size > 4) parts[4].trim() else "",
                    level = if (parts.size > 5) parts[5].trim() else ""
                )
            )
        }

        if (errors.isNotEmpty()) {
            Log.w(TAG, "CSV errors:\n${errors.joinToString("\n")}")
        }

        return items
    }

    private fun parseXlsx(inputStream: InputStream): List<WarehouseItem> {
        val sharedStrings = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()

        val zis = ZipInputStream(inputStream)
        var entry = zis.nextEntry
        while (entry != null) {
            when {
                entry.name == "xl/sharedStrings.xml" -> readSharedStrings(zis, sharedStrings)
                entry.name == "xl/worksheets/sheet1.xml" -> readSheet(zis, sharedStrings, rows)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }

        if (rows.isEmpty()) return emptyList()

        val headerCells = rows[0]
        val headerKeys = headerCells.map { it.lowercase().trim() }
        val hasHeader = headerKeys.any { it in listOf("name", "barcode", "section") }

        val colMap = if (hasHeader) {
            mapOf(
                "name" to headerKeys.indexOfFirst { it == "name" }.takeIf { it >= 0 },
                "barcode" to headerKeys.indexOfFirst { it == "barcode" }.takeIf { it >= 0 },
                "section" to headerKeys.indexOfFirst { it == "section" }.takeIf { it >= 0 },
                "type" to headerKeys.indexOfFirst { it == "type" }.takeIf { it >= 0 },
                "number" to headerKeys.indexOfFirst { it == "number" }.takeIf { it >= 0 },
                "level" to headerKeys.indexOfFirst { it == "level" }.takeIf { it >= 0 }
            )
        } else null

        val items = mutableListOf<WarehouseItem>()
        val startIdx = if (hasHeader) 1 else 0

        for (i in startIdx until rows.size) {
            val row = rows[i]
            if (row.size < 4) continue

            val name: String
            val barcode: String
            val section: String
            val type: String
            val number: String
            val level: String

            if (colMap != null) {
                name = colMap["name"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                barcode = colMap["barcode"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                section = colMap["section"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                type = colMap["type"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                number = colMap["number"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
                level = colMap["level"]?.let { row.getOrNull(it)?.trim() ?: "" } ?: ""
            } else {
                name = row.getOrNull(0) ?: ""
                barcode = row.getOrNull(1) ?: ""
                section = row.getOrNull(2) ?: ""
                type = row.getOrNull(3) ?: ""
                number = row.getOrNull(4) ?: ""
                level = row.getOrNull(5) ?: ""
            }

            if (barcode.isBlank()) continue
            items.add(WarehouseItem(name, barcode, section, type, number, level))
        }

        return items
    }

    private fun readSharedStrings(zis: ZipInputStream, strings: MutableList<String>) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(zis, "UTF-8")
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
    }

    private fun readSheet(zis: ZipInputStream, sharedStrings: List<String>, rows: MutableList<List<String>>) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(zis, "UTF-8")

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
                            "row" -> { currentRow = mutableListOf(); inRow = true }
                            "c" -> {
                                cellType = parser.getAttributeValue(null, "t") ?: ""
                                inlineText = false; inCell = true
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
                                    currentRow.add(if (idx != null && idx < sharedStrings.size) sharedStrings[idx] else text)
                                }
                                cellType == "" || cellType == "str" -> currentRow.add(text)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "row" -> { if (currentRow.isNotEmpty()) rows.add(currentRow.toList()); inRow = false }
                            "c" -> inCell = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sheet1.xml", e)
        }
    }

    private fun saveToFile(context: Context, items: List<WarehouseItem>) {
        val jsonArray = JSONArray()
        items.forEach { item ->
            jsonArray.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("barcode", item.barcode)
                    put("section", item.section)
                    put("type", item.type)
                    put("number", item.number)
                    put("level", item.level)
                }
            )
        }

        val json = JSONObject().apply {
            put("shelves", jsonArray)
        }

        context.filesDir.resolve(IMPORTED_FILE).writeText(json.toString(), Charsets.UTF_8)
        Log.d(TAG, "Saved ${items.size} shelves to $IMPORTED_FILE")
    }

    fun hasImportedFile(context: Context): Boolean {
        return context.filesDir.resolve(IMPORTED_FILE).exists()
    }

    fun importedFilePath(context: Context): String {
        return context.filesDir.resolve(IMPORTED_FILE).absolutePath
    }
}
