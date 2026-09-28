package com.xw.vvtts.update

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
            val output = FileOutputStream(dest)
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                done += n
                onProgress?.invoke(done, total)
            }
            output.flush()
            output.close()
            input.close()
            return dest.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            runCatching { dest.delete() }
            return false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}