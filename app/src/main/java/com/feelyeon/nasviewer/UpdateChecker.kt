package com.feelyeon.nasviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object UpdateChecker {
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/rolloll/NAScroll/main/update.json"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_LAST_NOTIFIED_CODE = "update_last_notified_code"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkIfNeeded(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("nasviewer_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK_AT, 0L) < CHECK_INTERVAL_MS) return@withContext null
        prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()

        val request = Request.Builder()
            .url(UPDATE_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        val update = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                AppUpdate(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    downloadUrl = json.getString("apkUrl"),
                    releaseNotes = json.optString("releaseNotes", "")
                )
            }
        }.getOrNull() ?: return@withContext null

        val currentCode = currentVersionCode(context)
        if (update == null || update.versionCode <= currentCode) return@withContext null
        if (prefs.getInt(KEY_LAST_NOTIFIED_CODE, 0) >= update.versionCode) return@withContext null
        prefs.edit().putInt(KEY_LAST_NOTIFIED_CODE, update.versionCode).apply()
        update
    }

    fun showUpdateDialog(context: Context, update: AppUpdate) {
        val message = buildString {
            append("새 버전 ${update.versionName}을(를) 사용할 수 있습니다.\n\n")
            if (update.releaseNotes.isNotBlank()) append(update.releaseNotes.trim())
            else append("GitHub에서 최신 APK를 다운로드할 수 있습니다.")
        }
        AlertDialog.Builder(context)
            .setTitle("NAScroll 업데이트")
            .setMessage(message)
            .setNegativeButton("나중에", null)
            .setPositiveButton("다운로드") { _, _ ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
            }
            .show()
    }

    private fun currentVersionCode(context: Context): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }
}
