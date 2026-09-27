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

    private fun hasToken(): Boolean = TOKEN.isNotBlank()

    /** Sends the PAT only when one is baked into the build; otherwise stays anonymous. */
    private fun setAuthHeader(conn: HttpURLConnection) {
        if (hasToken()) conn.setRequestProperty("Authorization", "Bearer $TOKEN")
    }

    /**
     * Opens a connection, runs [block], then always [HttpURLConnection.disconnect] —
     * even on early exit (`return@withConnection`) or exception. Prevents socket/fd leaks.
     */
    private suspend fun <T> withConnection(url: URL, block: suspend (HttpURLConnection) -> T): T {
        val conn = url.openConnection() as HttpURLConnection
        try {
            return block(conn)
        } finally {
            conn.disconnect()
        }
    }

    private const val API_BASE = "https://api.github.com"
    private const val RAW_BASE = "https://raw.githubusercontent.com"

    /** Refuse payloads larger than this: JSONArray keeps 2-3x the raw size in heap. */
    // db.json is ~19 MB today. The download is decoded as a String and then parsed
    // into a JSONObject, so peak heap is roughly 3-4x the payload: a much larger cap
    // only buys an OutOfMemoryError instead of a clean "too large" error.
    private const val MAX_DB_BYTES = 32L * 1024 * 1024

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
            return@withContext withConnection(URL(rawUrl("db_version.json"))) { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "getDbVersion HTTP $code")
                return@withConnection null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            DbVersionInfo(
                versionCode = obj.optInt("versionCode", 0),
                shelvesHash = obj.optString("shelvesHash", ""),
                productsHash = obj.optString("productsHash", ""),
                timestamp = obj.optLong("timestamp", 0L)
            )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDbVersion failed", e)
            null
        }
    }

    suspend fun getProductsDbVersion(): ProductsDbVersionInfo? = withContext(Dispatchers.IO) {
        try {
            return@withContext withConnection(URL(productsRawUrl("db_version.json"))) { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "getProductsDbVersion HTTP $code")
                return@withConnection null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            ProductsDbVersionInfo(
                versionCode = obj.optInt("versionCode", 0),
                hash = obj.optString("hash", ""),
                timestamp = obj.optLong("timestamp", 0L)
            )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getProductsDbVersion failed", e)
            null
        }
    }

    suspend fun publishFile(path: String, content: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasToken()) {
            Log.e(TAG, "publishFile $path: no token, refusing anonymous write")
            return@withContext false
        }
        try {
            putWithShaRetry(apiUrl(path), BRANCH, path, content, message) { getFileSha(it) }
        } catch (e: Exception) {
            Log.e(TAG, "publishFile $path failed", e)
            false
        }
    }

    suspend fun publishProductFile(path: String, content: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasToken()) {
            Log.e(TAG, "publishProductFile $path: no token, refusing anonymous write")
            return@withContext false
        }
        try {
            putWithShaRetry(productsApiUrl(path), PRODUCTS_BRANCH, path, content, message) { getProductFileSha(it) }
        } catch (e: Exception) {
            Log.e(TAG, "publishProductFile $path failed", e)
            false
        }
    }

    /**
     * PUT с одним повтором на TOCTOU-конфликт (409/422): sha перечитывается, запрос повторяется.
     *
     * Вынесено отдельной функцией, а не оставлено `while (true)` внутри `try` в publish*:
     * у `while (true)` тип Unit, поэтому `try`-выражение типизировалось как Any
     * и не соответствовало объявленному Boolean.
     *
     * @param shaProvider сообщает текущий sha файла (Found/NotFound/Unavailable, см. [ShaLookup]).
     */
    private suspend fun putWithShaRetry(
        url: String,
        branch: String,
        path: String,
        content: String,
        message: String,
        shaProvider: suspend (String) -> ShaLookup
    ): Boolean {
        val first = shaProvider(path)
        if (first is ShaLookup.Unavailable) {
            Log.e(TAG, "PUT $path aborted: current sha unknown, refusing to write blind")
            return false
        }
        var currentSha = (first as? ShaLookup.Found)?.sha
        var attempt = 0
        while (true) {
            // Payload собирается заново: при конфликте sha обновляется и PUT повторяется.
            val attemptPayload = JSONObject().apply {
                put("message", message)
                put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("branch", branch)
                currentSha?.let { put("sha", it) }
            }
            val (code, error) = withConnection(URL(url)) { conn ->
                conn.requestMethod = "PUT"
                setAuthHeader(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(attemptPayload.toString())
                    writer.flush()
                }
                val responseCode = conn.responseCode
                if (responseCode !in 200..201) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Pair(responseCode, errorBody)
                } else {
                    Pair(responseCode, "")
                }
            }
            if (code in 200..201) return true
            if ((code == 409 || code == 422) && attempt == 0) {
                // TOCTOU: кто-то опубликовался параллельно — обновляем sha и повторяем один раз.
                attempt++
                Log.w(TAG, "PUT $path conflict HTTP $code, refreshing sha and retrying: $error")
                when (val refreshed = shaProvider(path)) {
                    is ShaLookup.Found -> currentSha = refreshed.sha
                    // Файл исчез — повторяем как создание (без sha).
                    ShaLookup.NotFound -> currentSha = null
                    // Sha так и не узнали: второй PUT всё равно получит 422, жжём не ретрай,
                    // а возвращаемся сразу с честным логом.
                    ShaLookup.Unavailable -> {
                        Log.e(TAG, "PUT $path aborted: sha still unknown after conflict HTTP $code")
                        return false
                    }
                }
                continue
            }
            Log.e(TAG, "PUT $path HTTP $code: $error")
            return false
        }
    }

    suspend fun downloadFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            return@withContext withConnection(URL(rawUrl(path))) { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadFile $path HTTP $code")
                return@withConnection null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile $path failed", e)
            null
        }
    }

    suspend fun downloadProductFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            return@withContext withConnection(URL(productsRawUrl(path))) { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadProductFile $path HTTP $code")
                return@withConnection null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadProductFile $path failed", e)
            null
        }
    }

    suspend fun downloadExternalDbJson(): String? = withContext(Dispatchers.IO) {
        try {
            return@withContext withConnection(URL(productsRawUrl("db.json"))) { conn ->
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "downloadExternalDbJson HTTP $code")
                return@withConnection null
            }
            if (conn.contentLengthLong > MAX_DB_BYTES) {
                Log.e(TAG, "downloadExternalDbJson: content-length ${conn.contentLengthLong} exceeds $MAX_DB_BYTES")
                return@withConnection null
            }
            // Bounded read: content-length can be absent (chunked), so enforce the cap while streaming.
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            conn.inputStream.use { stream ->
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DB_BYTES) {
                        Log.e(TAG, "downloadExternalDbJson: payload exceeds $MAX_DB_BYTES, aborting")
                        return@withConnection null
                    }
                    out.write(buf, 0, n)
                }
            }
            out.toString(Charsets.UTF_8.name())
            }
        } catch (e: OutOfMemoryError) {
            // Exception does not cover Error, so a huge db.json used to kill the
            // process instead of surfacing a failed download.
            Log.e(TAG, "downloadExternalDbJson ran out of memory", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "downloadExternalDbJson failed", e)
            null
        }
    }

    /**
     * Результат чтения sha файла через Contents API.
     *
     * «Файла нет» (404) и «не удалось узнать» (rate limit, 5xx, таймаут) — разные вещи:
     * в первом случае PUT без sha корректно создаёт файл, во втором GitHub ответит 422
     * (sha обязателен для существующего файла), и единственный повтор уйдёт впустую.
     */
    private sealed interface ShaLookup {
        data class Found(val sha: String) : ShaLookup
        /** Файла ещё нет — публиковать нужно без поля sha. */
        data object NotFound : ShaLookup
        /** Состояние неизвестно: публиковать нельзя, иначе потеряем файл или сожжём ретрай. */
        data object Unavailable : ShaLookup
    }

    private suspend fun getFileSha(path: String): ShaLookup = withContext(Dispatchers.IO) {
        try {
            withConnection(URL(apiUrl(path))) { conn ->
                conn.requestMethod = "GET"
                setAuthHeader(conn)
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val code = conn.responseCode
                if (code == 404) return@withConnection ShaLookup.NotFound
                if (code != 200) {
                    Log.w(TAG, "getFileSha $path HTTP $code")
                    return@withConnection ShaLookup.Unavailable
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val sha = JSONObject(text).optString("sha")
                if (sha.isEmpty()) ShaLookup.Unavailable else ShaLookup.Found(sha)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getFileSha $path failed", e)
            ShaLookup.Unavailable
        }
    }

    private suspend fun getProductFileSha(path: String): ShaLookup = withContext(Dispatchers.IO) {
        try {
            withConnection(URL(productsApiUrl(path))) { conn ->
                conn.requestMethod = "GET"
                setAuthHeader(conn)
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val code = conn.responseCode
                if (code == 404) return@withConnection ShaLookup.NotFound
                if (code != 200) {
                    Log.w(TAG, "getProductFileSha $path HTTP $code")
                    return@withConnection ShaLookup.Unavailable
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val sha = JSONObject(text).optString("sha")
                if (sha.isEmpty()) ShaLookup.Unavailable else ShaLookup.Found(sha)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getProductFileSha $path failed", e)
            ShaLookup.Unavailable
        }
    }

    suspend fun getExternalDbSha(): String? = withContext(Dispatchers.IO) {
        try {
            return@withContext withConnection(URL(productsApiUrl("db.json"))) { conn ->
            conn.requestMethod = "GET"
            setAuthHeader(conn)
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val code = conn.responseCode
            if (code == 404) return@withConnection null
            if (code != 200) {
                Log.w(TAG, "getExternalDbSha HTTP $code")
                return@withConnection null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optString("sha").takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getExternalDbSha failed", e)
            null
        }
    }
}