package com.xw.vvtts.update

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

object ElqUpdateDownloader {

    private const val TIMEOUT_MS = 30000
    private const val TAG = "ElqUpdateDownloader"
    /** Hard cap on APK download size (250 MB). */
    private const val MAX_APK_SIZE_BYTES = 250L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    /** Hosts allowed to serve the APK payload: GitHub releases and their asset CDNs. */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubassets.com"
    )

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)? = null): Boolean {
        var conn: HttpURLConnection? = null
        var tmp: File? = null
        try {
            // Redirects are followed manually so that every hop can be validated
            // (https + allowlisted release host) before it is followed.
            var current = URL(url)
            validateUrl(current)
            var redirects = 0
            var code = -1
            while (true) {
                val c = current.openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "EloquenceTTS-Updater/1.0 (Android)")
                code = c.responseCode
                if (code in REDIRECT_CODES) {



                    if (redirects >= MAX_REDIRECTS) throw IOException("too many redirects")
                    val location = c.getHeaderField("Location")
                        ?: throw IOException("redirect without Location header")
                    redirects++
                    c.disconnect()
                    conn = null
                    current = URL(current, location)
                    validateUrl(current)
                    continue
                }
                break
            }
            if (code !in 200..299) return false
            val finalConn = conn ?: throw IOException("no connection")
            val total = finalConn.contentLengthLong
            if (total > MAX_APK_SIZE_BYTES) {

                Log.w(TAG, "Refusing APK: declared size $total exceeds cap $MAX_APK_SIZE_BYTES")
                return false
            }
            // Download to a unique temporary file in the destination directory so a crash
            // or partial write can never leave a stale APK at the final path.

            val parent = dest.parentFile ?: File(".")
            val tmpFile = File.createTempFile(dest.name + ".part", ".tmp", parent)
            tmp = tmpFile
            finalConn.inputStream.use { inp ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done =  0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_SIZE_BYTES) {

                            throw IOException("APK download exceeds maximum size $MAX_APK_SIZE_BYTES")
                        }
                        if (total > 0 && done > total) {

                            throw IOException("server sent more bytes than Content-Length declared")
                        }
                        output.write(buf, 0, n)
                        onProgress?.invoke(done, total)
                    }
                    output.flush()
                    output.fd.sync()
                    if (total > 0 && done != total) {

                        throw IOException("truncated download: got $done of $total bytes")
                    }
                    if (done <= 0) throw IOException("empty APK download")
                }
            }
// Never let a torn or non-APK payload reach the final path: structurally validate
            // the temporary file before it is moved into place (catches truncation even when
            // the server sent no Content-Length, so PackageInstaller never sees a broken file).
            if (!isLikelyApk(tmpFile)) throw IOException("downloaded file is not a valid APK")
            // Atomically replace the destination; fall back to a plain replace when the
            // filesystem cannot do an atomic move. The temporary file is deleted on any failure.

            try {
                Files.move(tmpFile.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmpFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: FileAlreadyExistsException) {

                Files.move(tmpFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            tmp = null
            return true
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            runCatching { tmp?.delete() }
            return false
        } finally {
            // Disconnect the actual connection on every hop, success or failure.
            runCatching { conn?.disconnect() }
        }
    }

/** Rejects any hop that is not HTTPS on an allowlisted release host. */
    private fun validateUrl(url: URL) {
        if (url.protocol != "https") {throw IOException("refusing non-https download URL: ${url.protocol}://${url.host}")
        }
        val host = url.host?.lowercase() ?: ""
        if (host !in ALLOWED_HOSTS) {throw IOException("refusing download URL on non-allowlisted host: $host")
        }
    }


    /** Verifies the download looks like a structurally valid APK (ZIP(: PK magic + readable
     * central directory + a top-level AndroidManifest.xml entry. Catches truncated
     * downloads even when the server sent no Content-Length (so PackageInstaller
     * never sees a torn file(. */
    private fun isLikelyApk(file: File): Boolean {

        return try {
            if (file.length() < 8) return false
            val magic = DataInputStream(file.inputStream()).use { ins ->
                ByteArray(4).also { b -> ins.readFully(b) }
            }
            if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte() || magic[2] != 3.toByte() || magic[3] != 4.toByte()) return false
ZipFile(file).use { zf -> zf.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            Log.w(TAG, "APK validation failed", e)
            false
        }
    }
}