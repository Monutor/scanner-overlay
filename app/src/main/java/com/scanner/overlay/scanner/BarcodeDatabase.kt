package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object BarcodeDatabase {
    private const val TAG = "BarcodeDatabase"
    private const val JSON_FILE = "shelves.json"

    private val SHELF_TYPES = setOf("С", "П", "З")

    private val items = mutableListOf<WarehouseItem>()
    private val exactMap = HashMap<String, WarehouseItem>()

    /** Отдельный монитор только для однократности загрузки, не для данных. */
    private val initLock = Any()

    @Volatile
    private var loaded = false

    /**
     * Однократная загрузка полок.
     *
     * Чтение файлов и разбор JSON выполняются вне монитора данных: читатели
     * (getByBarcode/getAllShelves/getShelfSections) вызываются с главного потока, и
     * удержание `synchronized(this)` на парсинг assets (1.27 МБ) вставало на сотни
     * миллисекунд — фриз интерфейса и риск ANR. Данные публикуются одним коротким
     * присваиванием под монитором, поэтому читатель либо видит пусто, либо готовый список.
     */
    fun init(context: Context) {
        if (loaded) return
        synchronized(initLock) {
            if (loaded) return
            val collected = ArrayList<WarehouseItem>()
            val seen = HashSet<String>()
            collectFromAssets(context, collected, seen)
            synchronized(this) {
                items.clear()
                items.addAll(collected)
                exactMap.clear()
                for (item in collected) {
                    if (!exactMap.containsKey(item.barcode)) exactMap[item.barcode] = item
                }
                loaded = true
            }
        }
    }

    /** То же, но гарантированно вне главного потока и с возвратом после готовности. */
    suspend fun initAsync(context: Context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            init(context.applicationContext)
        }
    }

    private fun collectFromAssets(context: Context, out: MutableList<WarehouseItem>, seen: MutableSet<String>) {
        try {
            val inputStream = context.assets.open(JSON_FILE)
            val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseInto(jsonString, out, seen)
            Log.d(TAG, "Loaded ${out.size} items from shelves.json (assets)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load shelves.json", e)
        }
    }

    private fun parseInto(jsonString: String, out: MutableList<WarehouseItem>, seen: MutableSet<String>) {
        val jsonArray = JSONObject(jsonString).getJSONArray("shelves")
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val barcode = obj.optString("barcode", "")
            if (barcode.isBlank()) continue
            // Первый источник побеждает, как и раньше: assets, затем импорт, затем синхронизация.
            if (!seen.add(barcode)) continue
            out.add(
                WarehouseItem(
                    name = obj.optString("name", ""),
                    barcode = barcode,
                    section = obj.optString("section", ""),
                    type = obj.optString("type", ""),
                    number = obj.optString("number", ""),
                    level = if (obj.isNull("level")) "" else obj.optString("level", "")
                )
            )
        }
    }

    fun getAllShelves(): List<WarehouseItem> = synchronized(this) {
        if (!loaded) emptyList()
        else items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .distinctBy { it.barcode }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun getShelfSections(): List<String> = synchronized(this) {
        if (!loaded) emptyList()
        else items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .map { it.section }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }

    fun getByBarcode(barcode: String): WarehouseItem? = synchronized(this) {
        if (!loaded || barcode.isBlank()) null else exactMap[barcode]
    }

    fun getAllItems(): List<WarehouseItem> = synchronized(this) {
        if (!loaded) emptyList() else items.toList()
    }
}
