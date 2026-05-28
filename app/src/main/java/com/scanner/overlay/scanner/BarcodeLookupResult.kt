package com.scanner.overlay.scanner

data class WarehouseItem(
    val name: String,
    val barcode: String,
    val section: String,
    val type: String,
    val number: String,
    val level: String
)

sealed interface BarcodeLookupResult {
    data class ExactMatch(val item: WarehouseItem) : BarcodeLookupResult
    data class PrefixMatch(val items: List<WarehouseItem>) : BarcodeLookupResult
    data class FuzzyMatch(val item: WarehouseItem, val distance: Int) : BarcodeLookupResult
    data object NotFound : BarcodeLookupResult
}
