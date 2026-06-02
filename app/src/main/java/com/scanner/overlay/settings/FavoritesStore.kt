package com.scanner.overlay.settings

import android.content.SharedPreferences
import com.scanner.overlay.scanner.BarcodeDatabase
import com.scanner.overlay.scanner.WarehouseItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesStore @Inject constructor(
    private val prefs: SharedPreferences
) {
    companion object {
        const val MAX_FAVORITES = 5
        private const val PREF_KEY = "favorite_shelves_order"
        private const val SEPARATOR = "|"
    }

    fun getAll(): List<WarehouseItem> {
        val barcodes = readOrder()
        if (barcodes.isEmpty()) return emptyList()
        return barcodes.mapNotNull { BarcodeDatabase.getByBarcode(it) }
    }

    fun isFavorite(barcode: String): Boolean {
        return barcode in readOrder()
    }

    fun toggle(barcode: String): Boolean {
        val current = readOrder()
        val newList = if (barcode in current) {
            current - barcode
        } else {
            val prepended = listOf(barcode) + current.filter { it != barcode }
            prepended.take(MAX_FAVORITES)
        }
        writeOrder(newList)
        return barcode in newList
    }

    fun remove(barcode: String) {
        val current = readOrder()
        if (barcode !in current) return
        writeOrder(current - barcode)
    }

    private fun readOrder(): List<String> {
        val raw = prefs.getString(PREF_KEY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    private fun writeOrder(list: List<String>) {
        prefs.edit().putString(PREF_KEY, list.joinToString(SEPARATOR)).apply()
    }
}
