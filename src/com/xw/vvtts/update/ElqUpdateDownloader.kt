package com.xw.vvtts.update

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

object ElqUpdateDownloader {

    private const val TIMEOUT_MS = 30000
    private const val TAG = "ElqUpdateDownloader"

    fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "EloquenceTTS-Updater/1.0 (Android)")
            val code = c.responseCode
            if (code !in 200..299) return false
            val total = c.contentLengthLong
            val input = c.inputStream
            input.use { inp ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                    output.flush()
                }
            }
            val complete = when {
                total > 0L -> dest.length() == total
                total == 0L -> false        // explicit zero-length body can never be an APK
                else -> dest.length() > 0L   // unknown length: completeness = the stream read to EOF (see catch(; structural check gates truncation
            }
            return complete && isLikelyApk(dest)
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            runCatching { dest.delete() }
            return false
        } finally {
            runCatching { conn?.disconnect() }
        }
    /** Verifies the download looks like a structurally valid APK (ZIP(: PK magic + readable
     * central directory + a top-level AndroidManifest.xml entry. Catches truncated downloads
     * even when the server sent no Content-Length (so PackageInstaller never sees a torn file(. */
    private fun isLikelyApk(file: File): Boolean {
        return try {
            if (file.length() < 8) return false
            val magic = DataInputStream(file.inputStream()).use { ins ->
                ByteArray(4).also { b -> ins.readFully(b) }
            }
            if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte() || magic[2] != 3.toByte() || magic[3] != 4.toByte()) return false
            ZipFile(file).use { zf -> zf.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            false
        }
    }
}