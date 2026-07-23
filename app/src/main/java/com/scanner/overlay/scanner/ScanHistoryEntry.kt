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
            try {
                val arr = JSONArray(json)
                val list = mutableListOf<ScanHistoryEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(ScanHistoryEntry(
                        barcode = obj.getString("b"),
                        timestamp = obj.getLong("t"),
                        productName = if (obj.has("n")) obj.getString("n") else null
                    ))
                }
                return list.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                return emptyList()
            }
        }

        fun add(prefs: SharedPreferences, barcode: String, productName: String? = null) {
            val entries = load(prefs).toMutableList()
            entries.add(0, ScanHistoryEntry(barcode, System.currentTimeMillis(), productName))
            save(prefs, entries.take(MAX_SIZE))
        }

        fun clear(prefs: SharedPreferences) {
            prefs.edit().remove(PREF_KEY).apply()
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
