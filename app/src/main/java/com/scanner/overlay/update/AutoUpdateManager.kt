package com.scanner.overlay.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.scanner.overlay.BuildConfig
import kotlinx.coroutines.Dispatchers
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

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(UPDATE_JSON_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateResult.Error("HTTP $responseCode")
            }

            val json = conn.inputStream.bufferedReader().readText()
            val obj = JSONObject(json)

            val info = UpdateInfo(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                downloadUrl = obj.getString("downloadUrl"),
                releaseNotes = obj.optString("releaseNotes", "")
            )

            return@withContext if (info.versionCode > BuildConfig.VERSION_CODE) {
                UpdateResult.Available(info)
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: Exception) {
            return@withContext UpdateResult.Error(e.message ?: "Ошибка проверки")
        }
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.externalCacheDir, "app-update.apk")
            if (file.exists()) file.delete()

            val url = URL(info.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
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

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Не найден установщик APK"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
