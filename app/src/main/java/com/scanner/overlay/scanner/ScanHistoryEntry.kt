package com.scanner.overlay.scanner

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ScanHistoryEntry(
    val barcode: String,
    val timestamp: Long,
    val productName: String? = null
) {
    companion object {
        private const val PREF_KEY = "scan_history"
        private const val MAX_SIZE = 100

        fun load(prefs: SharedPreferences): List<ScanHistoryEntry> {
            val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
            val list = mutableListOf<ScanHistoryEntry>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    try {
                        val obj = arr.getJSONObject(i)
                        list.add(ScanHistoryEntry(
                            barcode = obj.getString("b"),
                            timestamp = obj.getLong("t"),
                            productName = if (obj.has("n")) obj.getString("n") else null
                        ))
                    } catch (_: Exception) {
                        // Пропускаем битую запись, не сносим всю историю целиком.
                    }
                }
            } catch (_: Exception) {
                // Массив распарсился частично — возвращаем то, что удалось прочитать.
            }
            return list.sortedByDescending { it.timestamp }
        }

        fun add(prefs: SharedPreferences, barcode: String, productName: String? = null) {
            // Load and save must share one critical section: a read-modify-write across two
            // calls lets a concurrent writer (another scan, or the clear button) silently drop
            // entries. The lock is per SharedPreferences instance, so unrelated keys are unaffected.
            synchronized(prefs) {
                val entries = load(prefs).toMutableList()
                entries.add(0, ScanHistoryEntry(barcode, System.currentTimeMillis(), productName))
                save(prefs, entries.take(MAX_SIZE))
            }
        }

        fun clear(prefs: SharedPreferences) {
            synchronized(prefs) {
                prefs.edit().remove(PREF_KEY).apply()
            }
        }

        private fun save(prefs: SharedPreferences, entries: List<ScanHistoryEntry>) {
            val arr = JSONArray()
            for (e in entries) {
                val obj = JSONObject()
                obj.put("b", e.barcode)
                obj.put("t", e.timestamp)
                if (e.productName != null) {
                    obj.put("n", e.productName)
                }
                arr.put(obj)
            }
            prefs.edit().putString(PREF_KEY, arr.toString()).apply()
        }
    }
}
