package com.scanner.overlay.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.scanner.overlay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val sha256: String = ""
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val info: UpdateInfo) : UpdateResult
    data class Error(val message: String) : UpdateResult
}

object AutoUpdateManager {
    private const val UPDATE_JSON_URL =
        "https://github.com/Monutor/scanner-overlay/releases/latest/download/update.json"

    private suspend fun <T> withRetry(
        maxRetries: Int = 3,
        delayMs: Long = 1000L,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    delay(delayMs * (2L shl attempt))
                }
            }
        }
        return Result.failure(lastError ?: Exception("Unknown error"))
    }

    private suspend fun fetchUpdateInfo(): UpdateInfo {
        // Retry only the network fetch; parsing is deterministic, so a malformed
        // update.json must fail immediately instead of being retried 3x.
        val json = withRetry(maxRetries = 3, delayMs = 1000L) { connFetchUpdateJson() }.getOrThrow()
        return parseUpdateInfo(json)
    }

    private suspend fun connFetchUpdateJson(): String = withContext(Dispatchers.IO) {
        val conn = URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode}")
            }

            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun parseUpdateInfo(json: String): UpdateInfo {
        val obj = JSONObject(json)
        return UpdateInfo(
            versionCode = obj.getInt("versionCode"),
            versionName = obj.getString("versionName"),
            downloadUrl = obj.getString("downloadUrl"),
            releaseNotes = obj.optString("releaseNotes", ""),
            sha256 = obj.optString("sha256", "")
        )
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        // No outer withRetry here on purpose: fetchUpdateInfo() already retries the
        // network fetch. A second wrapper turned a single malformed update.json into
        // up to 9 HTTP requests and ~12s of backoff for a deterministic failure.
        try {
            val info = fetchUpdateInfo()
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                UpdateResult.Available(info)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Ошибка проверки")
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = try {
            URL(info.downloadUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        var file: File? = null
        var part: File? = null
        try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            // Download into a .part file and publish it with rename only after the
            // sha256 check passes. Writing straight into app-update.apk left a
            // truncated, unusable APK on disk if the process was killed mid-copy.
            val target = File(cacheDir, "app-update.apk")
            val partFile = File(cacheDir, "app-update.apk.part")
            part = partFile
            if (partFile.exists()) partFile.delete()
            if (target.exists()) target.delete()

            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            conn.inputStream.use { input ->
                partFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (info.sha256.isNotEmpty()) {
                val actual = sha256Of(partFile)
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext Result.failure(Exception("Ошибка целостности: хеш APK не совпал"))
                }
            }

            if (!partFile.renameTo(target)) {
                // renameTo can fail across filesystems; fall back to a plain copy.
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }
            file = target

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            if (!context.packageManager.canRequestPackageInstalls()) {
                // Without this the installer Activity never starts, and the catch below reports
                // "Не найден установщик APK", which points the user in the wrong direction.
                return@withContext Result.failure(
                    Exception("Разрешите установку приложений из этого источника в настройках")
                )
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(Exception("Не найден установщик APK"))
            }
        } catch (e: Exception) {
            file?.delete()
            part?.delete()
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }
}
