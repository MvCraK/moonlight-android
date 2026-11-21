package com.limelight.utils

import com.limelight.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class UpdateMetadata(
    val versionCode: Int,
    val version: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkName: String,
    val downloadUrls: List<String>,
    val expectedSha256: String,
    val expectedSize: Long
)

internal object UpdateMetadataClient {
    private const val FEED = "https://raw.githubusercontent.com/MvCraK/moonlight-android/updates/vplus.json"
    private const val RELEASES = "https://github.com/MvCraK/moonlight-android/releases"
    private val client = OkHttpClient()

    fun fetch(): UpdateMetadata? {
        val request = Request.Builder().url(FEED).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val manifest = JSONObject(response.body?.string() ?: return null)
            if (manifest.getString("packageName") != BuildConfig.APPLICATION_ID) return null
            val latest = manifest.getJSONObject("latest")
            val code = latest.getInt("versionCode")
            val tag = "vplus-$code"
            val apk = "$tag.apk"
            val url = "$RELEASES/download/$tag/$apk"
            val size = latest.getLong("size")
            val digest = latest.getString("sha256")
            if (code < 1 || size < 1 || url != latest.getString("apkUrl") ||
                !digest.matches(Regex("[0-9a-f]{64}"))) return null
            return UpdateMetadata(code, latest.getString("versionName"), latest.optString("notes"),
                "$RELEASES/tag/$tag", apk, listOf(url), digest, size)
        }
    }
}
