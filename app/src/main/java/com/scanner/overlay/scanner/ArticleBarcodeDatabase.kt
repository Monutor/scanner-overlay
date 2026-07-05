package com.scanner.overlay.scanner

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object ArticleBarcodeDatabase {

    private var items: List<ProductItem> = emptyList()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val list = mutableListOf<ProductItem>()
            try {
                val stream = context.assets.open("barcode-products.csv")
                val reader = BufferedReader(InputStreamReader(stream))
                val seen = HashSet<String>(128)
                reader.readLine()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(";")
                    if (parts.size < 14) continue
                    val articleCode = parts[4].trim()
                    val name = parts[5].trim()
                    val barcode = parts[13].trim()
                    if (articleCode.isEmpty() || seen.contains(articleCode)) continue
                    seen.add(articleCode)
                    list.add(ProductItem(articleCode, name, barcode))
                }
                reader.close()
            } catch (_: Exception) {
            }
            items = list
            loaded = true
        }
    }

    fun searchByArticleCode(code: String): ProductItem? {
        return items.firstOrNull { it.articleCode == code }
    }
}
