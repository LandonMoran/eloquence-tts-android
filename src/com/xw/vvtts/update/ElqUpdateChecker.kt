package com.xw.vvttts.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object ElqUpdateChecker {

    private const val TAG = "ElqUpdateChecker"
    private const val REPO_OWNER = "LandonMoran"
    private const val REPO_NAME = "eloquence-tts-android"

    data class GitHubAsset(
        val name: String? = null,
        @Json(name = "browser_download_url") val browserDownloadUrl: String? = null
    )

    data class GitHubRelease(
        @Json(name = "tag_name") val tagName: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        @Json(name = "published_at") val publishedAt: String? = null,
        @Json(name = "html_url") val htmlUrl: String? = null,
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    )

    data class UpdateResult(
        val hasUpdate: Boolean = false,
        val currentVersionCode: Int = 0,
        val latestVersionCode: Int? = null,
        val latestTag: String? = null,
        val releaseNotes: String? = null,
        val downloadUrl: String? = null,
        val htmlUrl: String? = null,
        val error: String? = null
    )

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val releaseListType = Types.newParameterizedType(List::class.java, GitHubRelease::class.java)
    private val releaseAdapter: JsonAdapter<List<GitHubRelease>> = moshi.adapter(releaseListType)

    fun check(context: Context): UpdateResult {
        val localVersionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
        val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=25"
        val conn: HttpURLConnection? = null
        try {
            val c = URL(apiUrl).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "EloquenceTTS-Updater/1.0 (Android)")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("Accept-Encoding", "gzip, deflate")
            val code = c.responseCode
            if (code !in 200..299) {
                return UpdateResult(currentVersionCode = localVersionCode, error = "HTTP $code")
            }
            val json = readBody(c)
            if (json.isEmpty()) {
                return UpdateResult(currentVersionCode = localVersionCode, error = "Empty response")
            }
            val releases = releaseAdapter.fromJson(json) ?: emptyList()
            val stable = releases.filter { !it.prerelease && !it.draft }
            val target = stable.maxByOrNull { it.publishedAt ?: "" }
            if (target == null) {
                return UpdateResult(currentVersionCode = localVersionCode, error = "No stable release found")
            }
            val latestCode = target.tagName?.removePrefix("v")?.toIntOrNull() ?: -1
            val apkUrl = pickAsset(target.assets) ?: target.htmlUrl
            UpdateResult(
                hasUpdate = latestCode > localVersionCode,
                currentVersionCode = localVersionCode,
                latestVersionCode = latestCode.takeIf { it >= 0 },
                latestTag = target.tagName,
                releaseNotes = target.body,
                downloadUrl = apkUrl,
                htmlUrl = target.htmlUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "update check failed", e)
            UpdateResult(currentVersionCode = localVersionCode, error = e.message ?: "check failed")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun pickAsset(assets: List<GitHubAsset>): String? {
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        val candidates = when {
            abi.contains("arm64") -> listOf("vvttts-arm64-v8a.apk", "vvttts-universal.apk")
            abi.contains("armeabi") || abi.contains("arm") -> listOf("vvttts-armeabi-v7a.apk", "vvttts-universal.apk")
            else -> listOf("vvttts-universal.apk")
        }
        for ( want in candidates) {
            val hit = assets.firstOrNull { it.name?.equals(want, ignoreCase = true) == true }
            if (hit != null) return hit.browserDownloadUrl
        }
        return null
    }

    private fun readBody(conn: HttpURLConnection): String {
        val enc = conn.contentEncoding ?: ""
        val stream = when {
            enc.contains("gzip") -> GZIPInputStream(conn.inputStream)
            enc.contains("deflate") -> InflaterInputStream(conn.inputStream)
            else -> conn.inputStream
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}