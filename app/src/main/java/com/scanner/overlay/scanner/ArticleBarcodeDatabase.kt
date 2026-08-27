package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object ArticleBarcodeDatabase {
    private const val TAG = "ArticleBarcodeDatabase"

    private const val LOCAL_DB_FILE = "barcode-products-db.json"

    private val items = mutableListOf<ProductItem>()
    private val seenArticleCodes = HashSet<String>()
    private val articleIndex = HashMap<String, ProductItem>()
    private val barcodeIndex = HashMap<String, ProductItem>()
    private val barcodeSuffixIndex = HashMap<String, ProductItem>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromCache(context)
            if (!loaded) {
                loadFromAssets(context)
            }
            loaded = true
        }
    }

    fun saveToCache(context: Context) {
        try {
            context.filesDir.resolve(LOCAL_DB_FILE).writeText(toJsonString())
        } catch (e: Exception) {
            Log.e(TAG, "saveToCache failed", e)
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

    private fun parseJson(jsonText: String) {
        items.clear()
        seenArticleCodes.clear()
        articleIndex.clear()
        barcodeIndex.clear()
        barcodeSuffixIndex.clear()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val articleCode = optString(obj, "articleCode")
                if (articleCode.isEmpty()) continue
                seenArticleCodes.add(articleCode)
                val item = ProductItem(
                    articleCode = articleCode,
                    name = optString(obj, "name"),
                    barcode = optString(obj, "barcode")
                )
                items.add(item)
                articleIndex.putIfAbsent(articleCode, item)
                if (item.barcode.isNotEmpty()) barcodeIndex.putIfAbsent(item.barcode, item)
                if (item.barcode.length >= 5) barcodeSuffixIndex.putIfAbsent(item.barcode.takeLast(5), item)
            }
            loaded = true
        } catch (e: Exception) {
            Log.e(TAG, "parseJson failed", e)
        }
    }

    fun addItems(newItems: List<ProductItem>) {
        for (item in newItems) {
            if (!seenArticleCodes.contains(item.articleCode)) {
                seenArticleCodes.add(item.articleCode)
                articleIndex.putIfAbsent(item.articleCode, item)
                if (item.barcode.isNotEmpty()) barcodeIndex.putIfAbsent(item.barcode, item)
                if (item.barcode.length >= 5) barcodeSuffixIndex.putIfAbsent(item.barcode.takeLast(5), item)
                items.add(item)
            }
        }
    }

    fun containsArticleCode(code: String): Boolean {
        return seenArticleCodes.contains(code)
    }

    fun searchByArticleCode(code: String): ProductItem? {
        return articleIndex[code]
    }

    fun search(code: String): ProductItem? {
        val trimmed = code.trim()
        return articleIndex[trimmed]
            ?: barcodeIndex[trimmed]
            ?: if (trimmed.length == 5 && trimmed.all { it.isDigit() }) barcodeSuffixIndex[trimmed] else null
    }

    fun getAllItems(): List<ProductItem> {
        if (!loaded) return emptyList()
        return items.toList()
    }

    fun toJsonString(): String {
        val itemsList = items.map {
            JSONObject().apply {
                put("articleCode", it.articleCode)
                put("name", it.name)
                put("barcode", it.barcode)
            }.toString()
        }
        return "[${itemsList.joinToString(",")}]"
    }

    fun loadFromJson(jsonText: String) {
        parseJson(jsonText)
    }

    fun loadFromExternalJson(jsonText: String) {
        items.clear()
        seenArticleCodes.clear()
        articleIndex.clear()
        barcodeIndex.clear()
        barcodeSuffixIndex.clear()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val articleCode = optString(obj, "Код товара")
                if (articleCode.isEmpty()) continue
                seenArticleCodes.add(articleCode)
                val item = ProductItem(
                    articleCode = articleCode,
                    name = optString(obj, "Наименование"),
                    barcode = optString(obj, "ШК товара")
                )
                items.add(item)
                articleIndex.putIfAbsent(articleCode, item)
                if (item.barcode.isNotEmpty()) barcodeIndex.putIfAbsent(item.barcode, item)
                if (item.barcode.length >= 5) barcodeSuffixIndex.putIfAbsent(item.barcode.takeLast(5), item)
            }
            loaded = true
        } catch (e: Exception) {
            Log.e(TAG, "loadFromExternalJson failed", e)
        }
    }

    fun reset() {
        items.clear()
        seenArticleCodes.clear()
        articleIndex.clear()
        barcodeIndex.clear()
        barcodeSuffixIndex.clear()
        loaded = false
    }

    private fun optString(obj: JSONObject, key: String): String {
        return try {
            obj.getString(key).trim()
        } catch (_: Exception) {
            ""
        }
    }
}
