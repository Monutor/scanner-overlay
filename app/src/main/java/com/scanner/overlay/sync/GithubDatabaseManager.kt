package com.scanner.overlay.sync

import android.util.Base64
import android.util.Log
import com.scanner.overlay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GithubDatabaseManager {
    private const val TAG = "GithubDatabaseManager"
    private const val OWNER = "Monutor"
    private const val REPO = "scanner-overlay"
    private const val BRANCH = "master"
    private const val BASE_PATH = "app/src/main/assets"

    private const val PRODUCTS_OWNER = "Monutor"
    private const val PRODUCTS_REPO = "DataBaseProducts"
    private const val PRODUCTS_BRANCH = "main"

    private val TOKEN = BuildConfig.GITHUB_TOKEN

    private const val API_BASE = "https://api.github.com"
    private const val RAW_BASE = "https://raw.githubusercontent.com"

    data class DbVersionInfo(
        val versionCode: Int,
        val shelvesHash: String,
        val productsHash: String,
        val timestamp: Long
    )

    data class ProductsDbVersionInfo(
        val versionCode: Int,
        val hash: String,
        val timestamp: Long
    )

    private fun apiUrl(path: String) = "$API_BASE/repos/$OWNER/$REPO/contents/$BASE_PATH/$path"
    private fun rawUrl(path: String) = "$RAW_BASE/$OWNER/$REPO/$BRANCH/$BASE_PATH/$path"

    private fun productsApiUrl(path: String) = "$API_BASE/repos/$PRODUCTS_OWNER/$PRODUCTS_REPO/contents/$path"
    private fun productsRawUrl(path: String) = "$RAW_BASE/$PRODUCTS_OWNER/$PRODUCTS_REPO/$PRODUCTS_BRANCH/$path"

    suspend fun getDbVersion(): DbVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(rawUrl("db_version.json"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "getDbVersion HTTP $code")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            DbVersionInfo(
                versionCode = obj.optInt("versionCode", 0),
                shelvesHash = obj.optString("shelvesHash", ""),
                productsHash = obj.optString("productsHash", ""),
                timestamp = obj.optLong("timestamp", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "getDbVersion failed", e)
            null
        }
    }

    suspend fun getProductsDbVersion(): ProductsDbVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(productsRawUrl("db_version.json"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "getProductsDbVersion HTTP $code")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            ProductsDbVersionInfo(
                versionCode = obj.optInt("versionCode", 0),
                hash = obj.optString("hash", ""),
                timestamp = obj.optLong("timestamp", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "getProductsDbVersion failed", e)
            null
        }
    }

    suspend fun publishFile(path: String, content: String, message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val existingSha = getFileSha(path)
            val payload = JSONObject().apply {
                put("message", message)
                put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", BRANCH)
                existingSha?.let { put("sha", it) }
            }
            val url = URL(apiUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val writer = conn.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(payload.toString())
            writer.flush()
            writer.close()
            val code = conn.responseCode
            if (code !in 200..201) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "publishFile $path HTTP $code: $error")
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "publishFile $path failed", e)
            false
        }
    }

    suspend fun publishProductFile(path: String, content: String, message: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val existingSha = getProductFileSha(path)
            val payload = JSONObject().apply {
                put("message", message)
                put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", PRODUCTS_BRANCH)
                existingSha?.let { put("sha", it) }
            }
            val url = URL(productsApiUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            val writer = conn.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(payload.toString())
            writer.flush()
            writer.close()
            val code = conn.responseCode
            if (code !in 200..201) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "publishProductFile $path HTTP $code: $error")
                return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "publishProductFile $path failed", e)
            false
        }
    }

    suspend fun downloadFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(rawUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadFile $path HTTP $code")
                return@withContext null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile $path failed", e)
            null
        }
    }

    suspend fun downloadProductFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(productsRawUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadProductFile $path HTTP $code")
                return@withContext null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadProductFile $path failed", e)
            null
        }
    }

    suspend fun downloadExternalDbJson(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(productsRawUrl("db.json"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadExternalDbJson HTTP $code")
                return@withContext null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadExternalDbJson failed", e)
            null
        }
    }

    private suspend fun getFileSha(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(apiUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code != 200) {
                Log.w(TAG, "getFileSha $path HTTP $code")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optString("sha").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "getFileSha $path failed", e)
            null
        }
    }

    private suspend fun getProductFileSha(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(productsApiUrl(path))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code != 200) {
                Log.w(TAG, "getProductFileSha $path HTTP $code")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optString("sha").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "getProductFileSha $path failed", e)
            null
        }
    }
}