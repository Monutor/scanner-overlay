package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ArticleBarcodeDatabase {
    private const val TAG = "ArticleBarcodeDatabase"

    private const val LOCAL_DB_FILE = "barcode-products-db.json"

    /**
     * Неизменяемый снимок базы, публикуемый одним присваиванием в @Volatile-поле.
     *
     * Раньше база держалась в пяти изменяемых коллекциях, а swap() вставал на `synchronized(this)`
     * на всё время clear()+addAll() — десятки тысяч элементов. Поиск с Main-потока
     * (ArticleBarcodeActivity, OverlayActivity) в это время ждал монитор, и фоновая синхронизация
     * с GitHub вставляла UI на сотни миллисекунд. Теперь читатели не блокируются вовсе,
     * а атомарность всех пяти коллекций обеспечивается публикацией снимка целиком.
     */
    private class Snapshot(
        val items: List<ProductItem>,
        val seenArticleCodes: Set<String>,
        val articleIndex: Map<String, ProductItem>,
        val barcodeIndex: Map<String, ProductItem>,
        val barcodeSuffixIndex: Map<String, ProductItem>
    )

    @Volatile
    private var snapshot = Snapshot(emptyList(), emptySet(), emptyMap(), emptyMap(), emptyMap())
    @Volatile
    private var loaded = false
    /** Синхронизирует только записи (init/addItems/reset) — чтения не блокируются. */
    private val writeLock = Any()

    private data class ParsedDb(
        val items: List<ProductItem>,
        val seenArticleCodes: Set<String>,
        val articleIndex: Map<String, ProductItem>,
        val barcodeIndex: Map<String, ProductItem>,
        val barcodeSuffixIndex: Map<String, ProductItem>
    )

    fun init(context: Context) {
        if (loaded) return
        synchronized(writeLock) {
            if (loaded) return
            loadFromCache(context)
            if (!loaded) {
                loadFromAssets(context)
            }
            loaded = true
        }
    }

    fun saveToCache(context: Context) {
        // Запись атомарная (temp + rename): прямая запись обрезает файл на старте, и убийство
        // процесса посреди неё оставляет битый кэш, который loadFromCache() потом молча
        // проглатывает — база товаров пустеет до ручной синхронизации.
        val target = context.filesDir.resolve(LOCAL_DB_FILE)
        val temp = context.filesDir.resolve("$LOCAL_DB_FILE.tmp")
        try {
            val json = toJsonString()
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                // Файловая система может отказать в rename (например, target занят) —
                // тогда пишем напрямую, но temp обязательно убираем.
                target.writeText(json)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveToCache failed", e)
            try { if (temp.exists()) temp.delete() } catch (_: Exception) {}
        }
    }

    private fun loadFromCache(context: Context) {
        try {
            val file = context.filesDir.resolve(LOCAL_DB_FILE)
            if (file.exists()) {
                parseJson(file.readText())
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadFromCache failed", e)
        }
    }

    private fun loadFromAssets(context: Context) {
        try {
            val jsonText = context.assets.open("barcode-products.json").bufferedReader().readText()
            parseJson(jsonText)
            loaded = true
        } catch (e: Exception) {
            Log.e(TAG, "loadFromAssets failed", e)
        }
    }

    private fun parseInternalJson(jsonText: String): ParsedDb {
        val parsedItems = mutableListOf<ProductItem>()
        val parsedSeen = HashSet<String>()
        val parsedArticle = HashMap<String, ProductItem>()
        val parsedBarcode = HashMap<String, ProductItem>()
        val parsedSuffix = HashMap<String, ProductItem>()
        val array = JSONArray(jsonText)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val articleCode = optString(obj, "articleCode")
            if (articleCode.isEmpty()) continue
            parsedSeen.add(articleCode)
            val item = ProductItem(
                articleCode = articleCode,
                name = optString(obj, "name"),
                barcode = optString(obj, "barcode")
            )
            parsedItems.add(item)
            parsedArticle.putIfAbsent(articleCode, item)
            if (item.barcode.isNotEmpty()) parsedBarcode.putIfAbsent(item.barcode, item)
            if (item.barcode.length >= 5) parsedSuffix.putIfAbsent(item.barcode.takeLast(5), item)
        }
        return ParsedDb(parsedItems, parsedSeen, parsedArticle, parsedBarcode, parsedSuffix)
    }

    private fun parseExternalJson(jsonText: String): ParsedDb {
        val parsedItems = mutableListOf<ProductItem>()
        val parsedSeen = HashSet<String>()
        val parsedArticle = HashMap<String, ProductItem>()
        val parsedBarcode = HashMap<String, ProductItem>()
        val parsedSuffix = HashMap<String, ProductItem>()
        val array = JSONArray(jsonText)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val articleCode = optString(obj, "Код товара")
            if (articleCode.isEmpty()) continue
            parsedSeen.add(articleCode)
            val item = ProductItem(
                articleCode = articleCode,
                name = optString(obj, "Наименование"),
                barcode = optString(obj, "ШК товара")
            )
            parsedItems.add(item)
            parsedArticle.putIfAbsent(articleCode, item)
            if (item.barcode.isNotEmpty()) parsedBarcode.putIfAbsent(item.barcode, item)
            if (item.barcode.length >= 5) parsedSuffix.putIfAbsent(item.barcode.takeLast(5), item)
        }
        return ParsedDb(parsedItems, parsedSeen, parsedArticle, parsedBarcode, parsedSuffix)
    }

    private fun swap(parsed: ParsedDb) {
        // Публикуем всё сразу одним присваиванием: читатели видят либо старый, либо новый
        // набор целиком, но никогда — наполовину обновлённый.
        snapshot = Snapshot(
            items = parsed.items.toList(),
            seenArticleCodes = parsed.seenArticleCodes,
            articleIndex = parsed.articleIndex,
            barcodeIndex = parsed.barcodeIndex,
            barcodeSuffixIndex = parsed.barcodeSuffixIndex
        )
        // Пустой разбор — это «данных нет», а не «данные загружены». init() решает по
        // этому флагу, грузить ли assets: при честном [] из кэша флаг становился true,
        // loadFromAssets() не выполнялся, и поиск по штрихкоду/артикулу оставался пустым
        // до ручной синхронизации.
        if (parsed.items.isNotEmpty()) {
            loaded = true
        }
    }

    private fun parseJson(jsonText: String): Boolean {
        return try {
            swap(parseInternalJson(jsonText))
            true
        } catch (e: Exception) {
            Log.e(TAG, "parseJson failed, keeping previous data", e)
            false
        }
    }

    fun containsArticleCode(code: String): Boolean = snapshot.seenArticleCodes.contains(code)

    fun searchByArticleCode(code: String): ProductItem? = snapshot.articleIndex[code]

    fun search(code: String): ProductItem? {
        val cur = snapshot
        val trimmed = code.trim()
        return cur.articleIndex[trimmed]
            ?: cur.barcodeIndex[trimmed]
            ?: if (trimmed.length == 5 && trimmed.all { it.isDigit() }) cur.barcodeSuffixIndex[trimmed] else null
    }

    fun getAllItems(): List<ProductItem> =
        if (!loaded) emptyList() else snapshot.items

    fun toJsonString(): String {
        // Снимок читается без монитора, сериализуется тоже без него.
        val snapshot: List<ProductItem> = snapshot.items
        val itemsList = snapshot.map {
            JSONObject().apply {
                put("articleCode", it.articleCode)
                put("name", it.name)
                put("barcode", it.barcode)
            }.toString()
        }
        return "[${itemsList.joinToString(",")}]"
    }

    fun loadFromExternalJson(jsonText: String): Boolean {
        return try {
            val parsed = parseExternalJson(jsonText)
            // Пустой (или состоящий только из мусора) файл с GitHub не должен стирать
            // локальную базу: иначе одиночный сбой на стороне репозитория убивает поиск,
            // а saveToCache() ещё и фиксирует пустоту на диск. Отказ возвращает false,
            // вызывающий код показывает ошибку и кэш не перезаписывает.
            if (parsed.items.isEmpty()) {
                Log.w(TAG, "loadFromExternalJson: remote db has no usable items, keeping previous data")
                return false
            }
            swap(parsed)
            true
        } catch (e: Exception) {
            Log.e(TAG, "loadFromExternalJson failed, keeping previous data", e)
            false
        }
    }

    fun reset() {
        synchronized(writeLock) {
            snapshot = Snapshot(emptyList(), emptySet(), emptyMap(), emptyMap(), emptyMap())
            loaded = false
        }
    }

    private fun optString(obj: JSONObject, key: String): String {
        return try {
            obj.getString(key).trim()
        } catch (_: Exception) {
            ""
        }
    }
}
