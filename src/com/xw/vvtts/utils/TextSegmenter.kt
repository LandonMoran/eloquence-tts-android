package com.xw.vvtts.utils

/**
 * Text segmentation:split mixed Chinese-English text by language,,tagging each run with its dialect.
 */
class TextSegmenter {
    class Segment(
        val text: String,
       /** 0x60000 Chinese,  0x10000 English */
        val dialect: Int,
    )

    companion object {
        const val DIALECT_ZH = 0x60000
        const val DIALECT_EN = 0x10000

        fun segment(text: String?): List<Segment> {
            val out = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return out

            var cur = StringBuilder()
            var curDialect = -1
            for (i in text.indices) {
                val c = text[i]
                val d = if (isCjk(c)) DIALECT_ZH else DIALECT_EN
                if (curDialect == -1) {
                    curDialect = d
                } else if (d != curDialect) {
                    out.add(Segment(cur.toString(), curDialect))
                    cur = StringBuilder()
                    curDialect = d
                }
                cur.append(c)
            }
            if (cur.length > 0) {
                out.add(Segment(cur.toString(), curDialect))
            }
            return out
        }

        private fun isCjk(c: Char): Boolean {
            return (c.code in 0x4E00..0x9FFF)      // base Han
                    || (c.code in 0x3400..0x4DBF)  // extension A
        }
    }
}