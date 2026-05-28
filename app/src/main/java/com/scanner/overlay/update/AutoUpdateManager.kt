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

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
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
        val conn = URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${conn.responseCode}")
            }

            val json = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(json)

            return UpdateInfo(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                downloadUrl = obj.getString("downloadUrl"),
                releaseNotes = obj.optString("releaseNotes", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        val result = withRetry(maxRetries = 3, delayMs = 1000L) {
            fetchUpdateInfo()
        }
        result.fold(
            onSuccess = { info ->
                if (info.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateResult.Available(info)
                } else {
                    UpdateResult.UpToDate
                }
            },
            onFailure = { UpdateResult.Error(it.message ?: "Ошибка проверки") }
        )
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = try {
            URL(info.downloadUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        var file: File? = null
        try {
            file = File(context.externalCacheDir, "app-update.apk")
            if (file.exists()) file.delete()

            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }

            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

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
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }
}
