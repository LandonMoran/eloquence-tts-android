package com.xw.vvtts.update

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

object ElqUpdateChecker {

    private const val TAG = "ElqUpdateChecker"
    private const val REPO_OWNER = "LandonMoran"
    private const val REPO_NAME = "eloquence-tts-android"

    private class GitHubAsset(
        val name: String?,
        val browserDownloadUrl: String?
    )

    private class GitHubRelease(
        val tagName: String?,
        val prerelease: Boolean,
        val draft: Boolean,
        val publishedAt: String?,
        val htmlUrl: String?,
        val body: String?,
        val assets: List<GitHubAsset>
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

    /**
     * Checks GitHub releases synchronously and compares the latest stable release tag with the installed version.
     *
     * Returns release metadata with a nullable compatible APK URL, or an error result when the check fails.
     * Call from a background thread because this performs network I/O.
     */
    fun check(context: Context): UpdateResult {
        val pkg = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val localVersionCode = pkg?.longVersionCode?.toInt() ?: 0
        // Compare the release tag against the app's semantic versionName (e.g. "1.0.1")
        // so both sides go through the same parser on one comparable scale; fall back to the
        // raw Android versionCode int when the versionName doesn't parse.



        val localReleaseCode = parseVersionCode(pkg?.versionName) ?: localVersionCode
        val apiUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=25"
        var conn: HttpURLConnection? = null
        try {
            val c = URL(apiUrl).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "EloquenceTTS-Updater/1.0 (Android)")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("Accept-Encoding", "gzip, deflate")
            val code = c.responseCode
            if (code !in 200..299) {
                c.disconnect()
                return UpdateResult(currentVersionCode = localVersionCode, error = "HTTP $code")
            }
            val json = readBody(c)
            c.disconnect()
            if (json.isEmpty()) {
                return UpdateResult(currentVersionCode = localVersionCode, error = "Empty response")
            }
            val releases = parseReleases(json)
            val stable = releases.filter { !it.prerelease && !it.draft }
            val target = stable.maxByOrNull { it.publishedAt ?: "" }
            if (target == null) {
                return UpdateResult(currentVersionCode = localVersionCode, error = "No stable release found")
            }
            val latestCode = parseVersionCode(target.tagName) ?: -1
            val apkUrl = pickAsset(target.assets)
            return UpdateResult(
                hasUpdate = latestCode > localReleaseCode,
                currentVersionCode = localVersionCode,
                latestVersionCode = latestCode.takeIf { it >= 0 },
                latestTag = target.tagName,
                releaseNotes = target.body,
                downloadUrl = apkUrl,
                htmlUrl = target.htmlUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "update check failed", e)
            return UpdateResult(currentVersionCode = localVersionCode, error = e.message ?: "check failed")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * Parses a release tag (or installed versionName( into a comparable numeric code.
     *
     * Supports the repo's documented semantic-version tag format (`v1.0`, `v1.1.0`,
     * `v2.10.3`) and plain numeric versionCode tags (`2000000002`). Rejects ambiguous
     * values instead of extracting arbitrary digit runs: the previous regex could not read
     * `v1.0` at all and mis-parsed names like `r37` as version 37.
     */
    private fun parseVersionCode(tag: String?): Int? {
        val s = tag?.trim() ?: return null
        s.toIntOrNull()?.let { return it }
        // Semantic version tag: optional 'v' prefix + MAJOR.MINOR[.PATCH]; at least one dot
        // required so bare values like `v1` stay rejected as ambiguous..

        val m = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?$""").matchEntire(s) ?: return null
        val major = m.groupValues[1].toLong()
        val minor = m.groupValues[2].toLong()
        val patch = m.groupValues[3].ifEmpty { "0" }.toLong()
        // Base-1000 encodes each component so numeric ordering matches semantic ordering; any
        // component at or above 1000 would carry into the next slot, so reject those..

        if (major >= 1000 || minor >= 1000 || patch >= 1000) return null
        return (major * 1000 * 1000 + minor * 1000 + patch).toInt()
    }

    private fun parseReleases(json: String): List<GitHubRelease> {
        val out = mutableListOf<GitHubRelease>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val assets = mutableListOf<GitHubAsset>()
            if (o.has("assets") && !o.isNull("assets")) {
                val aa = o.getJSONArray("assets")
                for (j in 0 until aa.length()) {
                    val a = aa.getJSONObject(j)
                    assets.add(
                        GitHubAsset(
                            name = a.optString("name").ifEmpty { null },
                            browserDownloadUrl = a.optString("browser_download_url").ifEmpty { null }
                        )
                    )
                }
            }
            out.add(
                GitHubRelease(
                    tagName = o.optString("tag_name").ifEmpty { null },
                    prerelease = o.optBoolean("prerelease"),
                    draft = o.optBoolean("draft"),
                    publishedAt = o.optString("published_at").ifEmpty { null },
                    htmlUrl = o.optString("html_url").ifEmpty { null },
                    body = o.optString("body").ifEmpty { null },
                    assets = assets
                )
            )
        }
        return out
    }

    private fun pickAsset(assets: List<GitHubAsset>): String? {
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        val candidates = when {
            abi.contains("arm64") -> listOf("vvtts-arm64-v8a.apk", "vvtts-universal.apk")
            abi.contains("armeabi") || abi.contains("arm") -> listOf("vvtts-armeabi-v7a.apk", "vvtts-universal.apk")
            else -> listOf("vvtts-universal.apk")
        }
        for (want in candidates) {
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