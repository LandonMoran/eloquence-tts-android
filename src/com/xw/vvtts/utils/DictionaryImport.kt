package com.xw.vvtts.utils

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.io.PushbackInputStream

object DictionaryImport {
    /** BOM-aware, bounded streaming SAF import. The input is always closed. */
    fun read(input: InputStream): List<DictEntry> {
        val limited = object : FilterInputStream(input) {
            var bytes = 0
            private fun count(n: Int): Int {
                if (n > 0) bytes += n
                require(bytes <= VoiceConfig.MAX_IMPORT_BYTES) { "Dictionary file exceeds 1 MiB" }
                return n
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
        }
        PushbackInputStream(limited, 3).use { stream ->
            val prefix = ByteArray(3)
            var size = 0
            while (size < prefix.size) {
                val b = stream.read()
                if (b < 0) break
                prefix[size++] = b.toByte()
            }
            val (skip, charset) = when {
                size >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte() -> 3 to Charsets.UTF_8
                size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> 2 to Charsets.UTF_16LE
                size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> 2 to Charsets.UTF_16BE
                else -> 0 to Charsets.UTF_8
            }
            stream.unread(prefix, skip, size - skip)
            val entries = ArrayList<DictEntry>()
            // Reject malformed input instead of silently saving replacement characters.
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader = InputStreamReader(stream, decoder).buffered()
            while (true) {
                val line = StringBuilder()
                var eof = false
                while (true) {
                    val c = reader.read()
                    if (c == -1) eof = true
                    if (c == -1 || c == '\n'.code) break
                    require(line.length < 1024) { "Dictionary line exceeds 1024 characters" }
                    line.append(c.toChar())
                }
                if (line.isEmpty() && eof) break
                val s = line.toString().trim()
                if (s.isEmpty() || s.startsWith('#')) continue
                val separator = if ('|' in s) '|' else if ('\t' in s) '\t' else ','
                val parts = s.split(separator, limit = 3)
                require(parts.size >= 2) { "Dictionary entry needs a word and replacement" }
                val word = parts[0].trim()
                val spoken = parts[1].trim()
                if (word.equals("word", true) && spoken.equals("replacement", true)) continue
                require(word.isNotEmpty() && word.length <= VoiceConfig.MAX_WORD_CHARS) { "Invalid dictionary word length" }
                require(spoken.isNotEmpty() && spoken.length <= VoiceConfig.MAX_SPOKEN_CHARS) { "Invalid dictionary replacement length" }
                require(entries.size < VoiceConfig.MAX_DICT_ENTRIES) { "Dictionary exceeds 1000 entries" }
                entries += DictEntry(word, spoken, parts.getOrNull(2).equals("cs", true))
            }
            return entries
        }
    }
}
