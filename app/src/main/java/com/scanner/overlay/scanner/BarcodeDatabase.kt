package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BarcodeDatabase {
    private const val TAG = "BarcodeDatabase"
    private const val JSON_FILE = "shelves.json"
    private const val IMPORTED_FILE = "shelves_imported.json"
    private const val SYNCED_FILE = "shelves_synced.json"

    private val SHELF_TYPES = setOf("С", "П", "З")

    private val items = mutableListOf<WarehouseItem>()
    private val exactMap = HashMap<String, WarehouseItem>()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromAssets(context)
            val importedFile = context.filesDir.resolve(IMPORTED_FILE)
            if (importedFile.exists()) {
                loadFromFile(importedFile, "imported")
            }
            val syncedFile = context.filesDir.resolve(SYNCED_FILE)
            if (syncedFile.exists()) {
                loadFromFile(syncedFile, "synced")
            }
            loaded = true
        }
    }

    fun reload(context: Context) {
        synchronized(this) {
            items.clear()
            exactMap.clear()
            loaded = false
            init(context)
        }
    }

    private fun loadFromFile(file: File, label: String) {
        try {
            val jsonString = file.readText(Charsets.UTF_8)
            parseAndAdd(jsonString)
            Log.d(TAG, "Loaded ${items.size} items from $label")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from $label", e)
        }
    }

    private fun loadFromAssets(context: Context) {
        try {
            val inputStream = context.assets.open(JSON_FILE)
            val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseAndAdd(jsonString)
            Log.d(TAG, "Loaded ${items.size} items from shelves.json (assets)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shelves.json", e)
        }
    }

    private fun parseAndAdd(jsonString: String) {
        val jsonArray = JSONObject(jsonString).getJSONArray("shelves")
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val barcode = obj.optString("barcode", "")
            if (barcode.isBlank()) continue
            if (exactMap.containsKey(barcode)) continue
            val item = WarehouseItem(
                name = obj.optString("name", ""),
                barcode = barcode,
                section = obj.optString("section", ""),
                type = obj.optString("type", ""),
                number = obj.optString("number", ""),
                level = if (obj.isNull("level")) "" else obj.optString("level", "")
            )
            items.add(item)
            exactMap[item.barcode] = item
        }
    }

    fun getAllShelves(): List<WarehouseItem> {
        if (!loaded) return emptyList()
        return items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .distinctBy { it.barcode }
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

    fun getAllItems(): List<WarehouseItem> {
        if (!loaded) return emptyList()
        return items.toList()
    }

    fun exportToJson(): String {
        val seen = HashSet<String>()
        val arr = JSONArray()
        for (item in items) {
            if (seen.contains(item.barcode)) continue
            seen.add(item.barcode)
            arr.put(JSONObject().apply {
                put("name", item.name)
                put("barcode", item.barcode)
                put("section", item.section)
                put("type", item.type)
                put("number", item.number)
                put("level", item.level)
            })
        }
        return JSONObject().apply { put("shelves", arr) }.toString(2)
    }

    fun importFromRemote(json: String): List<WarehouseItem> {
        val newItems = mutableListOf<WarehouseItem>()
        try {
            val jsonArray = JSONObject(json).getJSONArray("shelves")
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val barcode = obj.optString("barcode", "")
                if (barcode.isBlank()) continue
                if (exactMap.containsKey(barcode)) continue
                val item = WarehouseItem(
                    name = obj.optString("name", ""),
                    barcode = barcode,
                    section = obj.optString("section", ""),
                    type = obj.optString("type", ""),
                    number = obj.optString("number", ""),
                    level = if (obj.isNull("level")) "" else obj.optString("level", "")
                )
                newItems.add(item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importFromRemote failed", e)
        }
        return newItems
    }

    fun mergeRemoteShelves(context: Context, newItems: List<WarehouseItem>) {
        if (newItems.isEmpty()) return
        for (item in newItems) {
            if (exactMap.containsKey(item.barcode)) continue
            items.add(item)
            exactMap[item.barcode] = item
        }
        persistSyncedFile(context)
    }

    private fun persistSyncedFile(context: Context) {
        try {
            val seen = HashSet<String>()
            val arr = JSONArray()
            for (item in items) {
                if (seen.contains(item.barcode)) continue
                seen.add(item.barcode)
                arr.put(JSONObject().apply {
                    put("name", item.name)
                    put("barcode", item.barcode)
                    put("section", item.section)
                    put("type", item.type)
                    put("number", item.number)
                    put("level", item.level)
                })
            }
            val json = JSONObject().apply { put("shelves", arr) }.toString(2)
            context.filesDir.resolve(SYNCED_FILE).writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "persistSyncedFile failed", e)
        }
    }
}
