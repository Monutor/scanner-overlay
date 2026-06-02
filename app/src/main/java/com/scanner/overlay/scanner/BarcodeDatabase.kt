package com.scanner.overlay.scanner

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object BarcodeDatabase {
    private const val TAG = "BarcodeDatabase"
    private const val CSV_FILE = "barcodes.csv"
    private const val MAX_FUZZY_DISTANCE = 3

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
            var line = reader.readLine() // skip header
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

    fun lookup(scanned: String): BarcodeLookupResult {
        if (!loaded) return BarcodeLookupResult.NotFound
        if (scanned.isBlank()) return BarcodeLookupResult.NotFound

        // 1. Exact match
        exactMap[scanned]?.let {
            return BarcodeLookupResult.ExactMatch(it)
        }

        // 2. Prefix match
        val prefixMatches = items.filter { it.barcode.startsWith(scanned) }
        if (prefixMatches.isNotEmpty()) {
            return if (prefixMatches.size == 1) {
                BarcodeLookupResult.ExactMatch(prefixMatches[0])
            } else {
                BarcodeLookupResult.PrefixMatch(prefixMatches)
            }
        }

        // 3. Fuzzy match (Levenshtein distance)
        val fuzzyMatch = items
            .filter { kotlin.math.abs(it.barcode.length - scanned.length) <= MAX_FUZZY_DISTANCE }
            .map { it to levenshtein(scanned, it.barcode) }
            .filter { it.second <= MAX_FUZZY_DISTANCE }
            .minByOrNull { it.second }

        if (fuzzyMatch != null) {
            return BarcodeLookupResult.FuzzyMatch(fuzzyMatch.first, fuzzyMatch.second)
        }

        return BarcodeLookupResult.NotFound
    }

    private fun levenshtein(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    fun size(): Int = items.size

    fun getAllShelves(): List<WarehouseItem> {
        if (!loaded) return emptyList()
        return items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    fun searchByName(query: String): List<WarehouseItem> {
        if (!loaded) return emptyList()
        if (query.isBlank()) return getAllShelves()
        val q = query.trim().lowercase()
        return items.asSequence()
            .filter { it.type in SHELF_TYPES }
            .filter { it.name.lowercase().contains(q) }
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
