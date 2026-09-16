package com.xw.vvtts.utils

/**
 * 中英混合文本分段路由（移植自广荣 BilingualRoutingPolicy）。
 *
 * 解决"CJK 语音不读英文"的问题：苹果中文/日/韩库只认本国字符，
 * 遇到纯 ASCII 英文/字母段直接丢弃。正确做法不是让中文库硬读英文，
 * 而是按语言切段——中文段走中文引擎，英文段走英文引擎，最后 PCM 拼接。
 *
 * 广荣逻辑（已逆向 routeSegment）：
 *   segmentByLanguage=true 且当前段是英文 → ENGLISH voice
 *   否则 → CHINESE voice
 */
class LanguageSegmenter {
    class Segment(
        val text: String,
        /** true=英文段, false=中文段 */
        val english: Boolean,
    )

    companion object {
        /** 按语言切段。返回的段：中文/符号/数字走中文，纯英文字母串走英文。 */
        fun segment(text: String?): List<Segment> {
            val out = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return out

            var cur = StringBuilder()
            var curType = -1  // 0=中文/其他, 1=英文字母
            for (i in text.indices) {
                val c = text[i]
                val type = if (isEnglishChar(c)) 1 else 0
                if (curType == -1) {
                    curType = type
                } else if (type != curType) {
                    out.add(Segment(cur.toString(), curType == 1))
                    cur = StringBuilder()
                    curType = type
                }
                cur.append(c)
            }
            if (cur.length > 0) {
                out.add(Segment(cur.toString(), curType == 1))
            }
            return out
        }

        /** 是否为英文字母（a-z A-Z），数字和符号不算英文段（数字交给中文数字读法） */
        private fun isEnglishChar(c: Char): Boolean {
            return (c in 'a'..'z') || (c in 'A'..'Z')
        }

        /** 判断某段是否含英文（供上层决定是否整段走英文引擎） */
        fun containsEnglish(text: String?): Boolean {
            if (text == null) return false
            for (i in text.indices) {
                if (isEnglishChar(text[i])) return true
            }
            return false
        }

        /** 判断某段是否含 CJK（中文/日/韩） */
        fun containsCjk(text: String?): Boolean {
            if (text == null) return false
            for (i in text.indices) {
                val c = text[i]
                if ((c.code in 0x4E00..0x9FFF)      // 基本汉字
                        || (c.code in 0x3400..0x4DBF)  // 扩展A
                        || (c.code in 0x3040..0x30FF)  // 日文假名
                        || (c.code in 0xAC00..0xD7AF)) { // 韩文
                    return true
                }
            }
            return false
        }
    }
}