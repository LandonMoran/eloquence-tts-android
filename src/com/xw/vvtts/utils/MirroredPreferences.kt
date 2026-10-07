package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Disk snapshots shared by the UI and :tts processes, including before first unlock.
 * Every writer uses the same device-storage file lock. Each XML replacement uses
 * fsync followed by a same-directory rename, so a crash leaves a complete old or new snapshot.
 */
class MirroredPreferences(context: Context, private val name: String) {
    private val device = context.createDeviceProtectedStorageContext()
    // applicationContext is the app's CE context; the credential-context factory
    // is hidden from the public Android SDK. No CE file is opened here.
    private val credential = context.applicationContext ?: context
    private data class Stamp(val path: String, val inode: Long, val modified: Long, val size: Long)
    private var stamp: Stamp? = null
    private var cached: Map<String, Any> = emptyMap()

    // A DE write made while locked is newer than the unavailable CE mirror.
    // Keep this marker until a later unlocked transaction updates both copies.
    /** Locate the marker indicating that device-protected edits must survive the next unlock. */
    private fun pendingDeviceWrite() = File(device.filesDir, "$name.direct-boot-dirty")

    /** Locate a named preferences XML file within the supplied storage context. */
    private fun file(ctx: Context) = File(ctx.dataDir, "shared_prefs/$name.xml")
    /** Report whether credential-protected storage is available according to UserManager. */
    private fun unlocked() = device.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    /** Capture path, inode, timestamp, and size for detecting atomic file replacements. */
    private fun stamp(f: File): Stamp {
        val stat = Os.stat(f.path)
        return Stamp(f.path, stat.st_ino, f.lastModified(), stat.st_size)
    }

    /** Run a reentrant transaction under the process monitor and shared device-storage file lock. */
    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        if (heldLock.get() == true) return@synchronized block()
        val lockFile = File(device.filesDir, "preferences.lock")
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                heldLock.set(true)
                try { block() } finally { heldLock.remove() }
            }
        }
    }

    /** Read the authoritative complete snapshot, falling back across storage areas on failure. */
    fun read(): Map<String, Any> = locked {
        val candidates = when {
            !unlocked() -> listOf(device)
            pendingDeviceWrite().exists() -> listOf(device, credential)
            else -> listOf(credential, device)
        }
        for (context in candidates) {
            try {
                // Obtaining dataDir can itself fail while CE storage is unavailable.
                val f = file(context)
                if (!f.exists() && !File(f.path + ".bak").exists()) continue
                val current = if (File(f.path + ".bak").exists()) null else stamp(f)
                if (current != null && current == stamp) return@locked cached
                val parsed = parse(f) // Publish only a complete document, including a valid empty map.
                cached = parsed
                stamp = stamp(f)
                return@locked parsed
            } catch (_: Exception) {
                // Inaccessible or malformed CE storage must immediately try DE.
            }
        }
        // Do not return an unlocked credential cache during Direct Boot.
        if (!unlocked()) emptyMap() else cached
    }

    /** Check whether the latest readable snapshot contains a key. */
    fun contains(key: String) = read().containsKey(key)
    /** Return a string-valued view of the latest readable preferences. */
    fun strings(): Map<String, String> = read().mapValues { it.value.toString() }
    /** Read a Boolean value, using the default for missing or differently typed values. */
    fun getBoolean(key: String, default: Boolean) = read()[key] as? Boolean ?: default
    /** Read an Int value, using the default for missing or differently typed values. */
    fun getInt(key: String, default: Int) = read()[key] as? Int ?: default
    /** Read a string set, falling back to the supplied default when absent or incompatible. */
    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String>?) = read()[key] as? Set<String> ?: default

    /** Transform the latest on-disk state under the cross-process transaction lock. */
    fun update(block: (MutableMap<String, Any>) -> Unit): Boolean = locked {
        val changes = read().toMutableMap()
        block(changes)
        val previous = read()
        try {
            val writeCredential = unlocked()
            val primary = if (writeCredential) file(credential) else file(device)
            if (!writeCredential) {
                // Publish before replacing DE so process death cannot leave a newer
                // device copy without the marker. The common file lock guards readers.
                FileOutputStream(pendingDeviceWrite()).use { it.fd.sync() }
            }
            write(primary, changes)
            if (primary != file(device)) {
                try { write(file(device), changes) } catch (e: Exception) {
                    // No cooperating writer can run between this write and rollback.
                    write(primary, previous)
                    throw e
                }
            }
            cached = changes.toMap()
            stamp = stamp(primary)
            if (writeCredential) pendingDeviceWrite().delete()
            true
        } catch (e: Exception) {
            stamp = null
            Log.e("VvTtsPrefs", "Could not persist $name", e)
            false
        }
    }

    /** Collect editor operations and persist them together through one locked update. */
    fun edit(block: (SharedPreferences.Editor) -> Unit): Boolean {
        val editor = Changes()
        block(editor)
        return update { values ->
            if (editor.clear) values.clear()
            for ((key, value) in editor.values) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }

    private class Changes : SharedPreferences.Editor {
        val values = LinkedHashMap<String, Any?>()
        var clear = false
        /** Queue a string value, or remove the key when the value is null. */
        override fun putString(k: String, v: String?) = apply { values[k] = v }
        /** Queue a defensive copy of a string set, or remove the key for null. */
        override fun putStringSet(k: String, v: Set<String>?) = apply { values[k] = v?.toSet() }
        /** Queue an integer value for the enclosing edit transaction. */
        override fun putInt(k: String, v: Int) = apply { values[k] = v }
        /** Queue a long value for the enclosing edit transaction. */
        override fun putLong(k: String, v: Long) = apply { values[k] = v }
        /** Queue a floating-point value for the enclosing edit transaction. */
        override fun putFloat(k: String, v: Float) = apply { values[k] = v }
        /** Queue a Boolean value for the enclosing edit transaction. */
        override fun putBoolean(k: String, v: Boolean) = apply { values[k] = v }
        /** Queue removal of a key from the next snapshot. */
        override fun remove(k: String) = apply { values[k] = null }
        /** Clear existing values before applying the queued changes. */
        override fun clear() = apply { clear = true }
        /** Reject direct commit; MirroredPreferences.edit owns persistence of this editor. */
        override fun commit(): Boolean = error("Use MirroredPreferences.edit")
        /** Reject direct apply; MirroredPreferences.edit owns persistence of this editor. */
        override fun apply(): Unit = error("Use MirroredPreferences.edit")
    }

    companion object {
        private val processLock = Any()
        // Reentrant callers use the monitor, but must not reacquire the OS lock.
        private val heldLock = ThreadLocal<Boolean>()

        /** Parse a complete typed preferences map, recovering AtomicFile backups and rejecting malformed XML. */
        private fun parse(file: File): Map<String, Any> {
            val values = LinkedHashMap<String, Any>()
            AtomicFile(file).openRead().use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null)
                require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map")
                while (parser.nextTag() == XmlPullParser.START_TAG) {
                    val key = requireNotNull(parser.getAttributeValue(null, "name"))
                    val value = parser.getAttributeValue(null, "value")
                    values[key] = when (parser.name) {
                        "string" -> parser.nextText()
                        "set" -> {
                            val set = linkedSetOf<String>()
                            while (parser.nextTag() == XmlPullParser.START_TAG) {
                                require(parser.name == "string")
                                set += parser.nextText()
                            }
                            require(parser.name == "set")
                            set
                        }
                        else -> {
                            val parsed: Any = when (parser.name) {
                                "boolean" -> requireNotNull(value?.toBooleanStrictOrNull())
                                "int" -> requireNotNull(value).toInt()
                                "long" -> requireNotNull(value).toLong()
                                "float" -> requireNotNull(value).toFloat()
                                else -> error("Unknown preference type")
                            }
                            require(parser.nextTag() == XmlPullParser.END_TAG)
                            parsed
                        }
                    }
                }
                require(parser.name == "map")
                var event = parser.next()
                while (event == XmlPullParser.TEXT && parser.isWhitespace) event = parser.next()
                require(event == XmlPullParser.END_DOCUMENT)
            }
            return values
        }

        /** Serialize a complete snapshot, fsync it, and atomically rename it over the destination. */
        private fun write(file: File, values: Map<String, Any>) {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile(".prefs-", ".tmp", file.parentFile)
            try {
                FileOutputStream(temp).use { output ->
                    val xml = Xml.newSerializer()
                    xml.setOutput(output, "UTF-8")
                    xml.startDocument("UTF-8", true)
                    xml.startTag(null, "map")
                    for ((key, value) in values) {
                        val type = when (value) {
                            is String -> "string"; is Boolean -> "boolean"; is Int -> "int"
                            is Long -> "long"; is Float -> "float"; is Set<*> -> "set"
                            else -> error("Unsupported preference type")
                        }
                        xml.startTag(null, type).attribute(null, "name", key)
                        when (value) {
                            is String -> xml.text(value)
                            is Set<*> -> value.forEach { xml.startTag(null, "string").text(it.toString()).endTag(null, "string") }
                            else -> xml.attribute(null, "value", value.toString())
                        }
                        xml.endTag(null, type)
                    }
                    xml.endTag(null, "map")
                    xml.endDocument()
                    xml.flush()
                    output.fd.sync()
                }
                Os.rename(temp.path, file.path)
            } finally {
                temp.delete()
            }
        }
    }
}
