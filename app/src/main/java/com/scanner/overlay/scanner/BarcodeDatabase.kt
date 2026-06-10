package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object BarcodeDatabase {
    private const val TAG = "BarcodeDatabase"
    private const val CSV_FILE = "barcodes.csv"

    private val SHELF_TYPES = setOf("С", "П", "З")

    private val items = mutableListOf<WarehouseItem>()
    private val exactMap = HashMap<String, WarehouseItem>()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadCsv(context)
            loaded = true
        }
    }

    private fun loadCsv(context: Context) {
        try {
            val inputStream = context.assets.open(CSV_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var line = reader.readLine()
            while (line != null) {
                line = reader.readLine()
                if (line.isNullOrBlank()) continue
                val parts = line.split(",", limit = 6)
                if (parts.size < 5) continue
                val item = WarehouseItem(
                    name = parts[0].trim(),
                    barcode = parts[1].trim(),
                    section = parts[2].trim(),
                    type = parts[3].trim(),
                    number = parts[4].trim(),
                    level = if (parts.size > 5) parts[5].trim() else ""
                )
                items.add(item)
                exactMap[item.barcode] = item
            }
            reader.close()
            Log.d(TAG, "Loaded ${items.size} items from CSV")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load CSV", e)
        }
    }

    fun getAllShelves(): List<WarehouseItem> {
        if (!loaded) return emptyList()
        return items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun getShelfSections(): List<String> {
        if (!loaded) return emptyList()
        return items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .map { it.section }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    fun getByBarcode(barcode: String): WarehouseItem? {
        if (!loaded || barcode.isBlank()) return null
        return exactMap[barcode]
    }
}
