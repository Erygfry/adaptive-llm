package com.example.adaptivellm.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
)

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "Erygfry"
    private const val REPO = "adaptive-llm"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Check GitHub Releases for a newer version. Репозиторий публичный → auth
     * не требуется. GitHub лимит для unauthenticated запросов — 60/час с IP,
     * для update check (раз на запуск приложения) — более чем достаточно.
     *
     * Returns [ReleaseInfo] if an update is available, null otherwise.
     */
    suspend fun check(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API responded ${response.code} ${response.message} " +
                           "(URL: $API_URL). Releases check skipped.")
                response.close()
                return@withContext null
            }

            val json = JSONObject(response.body!!.string())
            val tagName = json.getString("tag_name")
            val remoteVersion = tagName.removePrefix("v")
            val currentVersion = getCurrentVersion(context)
            Log.i(TAG, "Latest release: $remoteVersion, current: $currentVersion")

            if (!isNewer(remoteVersion, currentVersion)) {
                Log.i(TAG, "No update needed ($currentVersion >= $remoteVersion)")
                return@withContext null
            }

            val body = json.optString("body", "")
            val assets = json.getJSONArray("assets")

            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (apkUrl.isEmpty()) {
                Log.w(TAG, "Release $remoteVersion has no .apk asset attached — skipping update")
                return@withContext null
            }

            Log.i(TAG, "Update available: $remoteVersion at $apkUrl")
            ReleaseInfo(
                versionName = remoteVersion,
                releaseNotes = body,
                apkUrl = apkUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}", e)
            null
        }
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0"
        }
    }

    /**
     * Compare semantic versions: "1.2.3" > "1.2.0"
     * Supports 1, 2, or 3 segments.
     */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }
}
