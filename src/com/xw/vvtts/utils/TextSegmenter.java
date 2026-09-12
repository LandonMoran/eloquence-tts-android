package com.xw.vvtts.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分段：把中英混合文本按语言切成段，每段标注方言。
 */
public class TextSegmenter {
    public static class Segment {
        public final String text;
        public final int dialect; // 0x60000 中文, 0x10000 英文
        Segment(String t, int d) { text = t; dialect = d; }
    }

    public static final int DIALECT_ZH = 0x60000;
    public static final int DIALECT_EN = 0x10000;

    public static List<Segment> segment(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        int curDialect = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int d = isCjk(c) ? DIALECT_ZH : DIALECT_EN;
            if (curDialect == -1) {
                curDialect = d;
            } else if (d != curDialect) {
                out.add(new Segment(cur.toString(), curDialect));
                cur = new StringBuilder();
                curDialect = d;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(new Segment(cur.toString(), curDialect));
        }
        return out;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF); // 扩展A
    }
}