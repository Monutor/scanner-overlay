package com.scanner.overlay.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ArticleBarcodeDatabase {

    private const val LOCAL_DB_FILE = "barcode-products-db.json"

    private val items = mutableListOf<ProductItem>()
    private val seenArticleCodes = HashSet<String>()
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
        } catch (_: Exception) {}
    }

    private fun loadFromCache(context: Context) {
        try {
            val file = context.filesDir.resolve(LOCAL_DB_FILE)
            if (file.exists()) {
                parseJson(file.readText())
            }
        } catch (_: Exception) {}
    }

    private fun loadFromAssets(context: Context) {
        try {
            val jsonText = context.assets.open("barcode-products.json").bufferedReader().readText()
            parseJson(jsonText)
            loaded = true
        } catch (_: Exception) {}
    }

    private fun parseJson(jsonText: String) {
        items.clear()
        seenArticleCodes.clear()
        try {
            val array = JSONArray(jsonText)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val articleCode = optString(obj, "articleCode")
                if (articleCode.isEmpty()) continue
                seenArticleCodes.add(articleCode)
                items.add(ProductItem(
                    articleCode = articleCode,
                    name = optString(obj, "name"),
                    barcode = optString(obj, "barcode")
                ))
            }
            loaded = true
        } catch (_: Exception) {}
    }

    fun addItems(newItems: List<ProductItem>) {
        for (item in newItems) {
            if (!seenArticleCodes.contains(item.articleCode)) {
                seenArticleCodes.add(item.articleCode)
                items.add(item)
            }
        }
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

    fun reset() {
        items.clear()
        seenArticleCodes.clear()
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
