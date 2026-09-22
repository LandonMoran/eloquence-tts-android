package com.github.pemistahl.lingua.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/** * Replaces per-file language-model JSON loading from the classpath with a single
 * packed models.elqm (see tools/model_pack.py, ELQM v2).  Returns a JSON
 * InputStream identical in shape to the original per-order files, so the
 * existing Moshi-based fromJson parser is untouched.
 * * Fallback: if models.elqm is absent or unreadable, serve the original
 * classpath JSON resource (the pre-P3 path); missing iso/order sections
 * inside a valid pack return null (which the caller treats as the original
 * missing-file empty-map behavior).
 */
public final class ElqmBridge {
    private static final String PREFIX = "/language-models/";
    private static final String PACK_PATH = "/language-models/models.elqm";
    private static final String[] ORDER_NAMES = {
        "", "unigrams", "bigrams", "trigrams", "quadrigrams", "fivegrams"
    };

    private static volatile boolean triedLoad;
    private static volatile byte[] pack;

    private ElqmBridge() {}

    public static InputStream open(String path) {
        try {
            byte[] p = pack();
            if (p == null) {
                return fallback(path);
            }
            int order = parseOrder(path);
            if (order < 0) {
                return null;
            }
            long[] offLen = findSection(p, path);
            if (offLen == null) {
                return null;
            }
            byte[] section = inflate(p, (int) offLen[0], (int) offLen[1]);
            String json = reconstruct(section, order);
            if (json == null) {
                return null;
            }
            return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return fallback(path);
        } catch (RuntimeException e) {
            return fallback(path);
        }
    }

    private static byte[] pack() throws IOException {
        if (!triedLoad) {
            synchronized (ElqmBridge.class) {
                if (!triedLoad) {
                    triedLoad = true;
                    pack = readAll(PACK_PATH);
                }
            }
        }
        return pack;
    }

    private static InputStream fallback(String path) {
        return ElqmBridge.class.getResourceAsStream(path);
    }

    private static byte[] readAll(String res) throws IOException {
        try (InputStream in = ElqmBridge.class.getResourceAsStream(res)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Path shapes: /language-models/<iso>/<name>.json.  Returns order id 1..5or -1. */ private static int parseOrder(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash < 0 || dot < slash) {
            return -1;
        }
        String stem = path.substring(slash + 1, dot);
        for (int i = 1; i < ORDER_NAMES.length; i++) {
            if (ORDER_NAMES[i].equals(stem)) {
                return i;
            }
        }
        return -1;
    }

    private static long[] findSection(byte[] p, String path) {
        int s1 = PREFIX.length();
        int s2 = path.indexOf('/', s1);
        if (s2 < 0) {
            return null;
        }
        int pos = 8; // magic(4) + version(1) + flags(1) + langCount(2)
        int nlangs = readU16(p, 6);
        for (int i = 0; i < nlangs; i++) {
            int clen = p[pos] & 0xFF;
            if (regionMatches(p, pos + 1, path, s1, s2 - s1)) {
                return new long[] { readU64(p, pos + 1 + clen), readU64(p, pos + 1 + clen + 8) };
            }
            pos += 1 + clen + 16;
        }
        return null;
    }

    private static boolean regionMatches(byte[] a, int aOff, String s, int sOff, int len) {
        for (int i = 0; i < len; i++) {
            if (a[aOff + i] != (byte) s.charAt(sOff + i)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] inflate(byte[] p, int off, int len) throws IOException {
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(p, off, len))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(len * 2);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Reconstruct the original JSON document ({"ngrams":{...}}} from one ELQM lang section. */ private static String reconstruct(byte[] s, int order) throws IOException {
        int[] pos = { 0 };
        int norders = s[pos[0]++] & 0xFF;
        int[] ids = new int[norders];
        int[] rels = new int[norders];
        for (int i = 0; i < norders; i++) {
            ids[i] = s[pos[0]++] & 0xFF;
            rels[i] = (int) readU32(s, pos[0]);
            pos[0] += 4;
        }
        int rel = -1;
        for (int i = 0; i < norders; i++) {
            if (ids[i] == order) {
                rel = rels[i];
                break;
            }
        }
        if (rel < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(1 << 18);
        sb.append("{\"ngrams\":{");
        pos[0] = rel;
        long ngroups = varint(s, pos);
        boolean first = true;
        for (long g = 0; g < ngroups; g++) {
            long count = varint(s, pos);
            long denom = varint(s, pos);
            long ntokens = varint(s, pos);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(count).append('/').append(denom).append("\":\"");
            byte[] prev = new byte[0];
                        for (long t = 0; t < ntokens; t++) {
                            int shared = readU16(s, pos[0]);
                            int slen = readU16(s, pos[0] + 2);
                            pos[0] += 4;
                            if (shared < 0 || slen < 0 || pos[0] + slen > s.length) {
                                throw new IOException("bad token header");
                            }
                            byte[] merged = new byte[shared + slen];
                            System.arraycopy(prev, 0, merged, 0, Math.min(shared, prev.length));
                            System.arraycopy(s, pos[0], merged, shared, slen);
                            pos[0] += slen;
                            String tok = new String(merged, StandardCharsets.UTF_8);
                            if (t > 0) {
                                sb.append(' ');
                            }
                            sb.append(tok);
                            prev = merged;
                        }
            sb.append('"');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static long varint(byte[] a, int[] pos) throws IOException {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = a[pos[0]++] & 0xFF;
            result |= (long) (b &  0x7F) << shift;
            if ((b &  0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("varint too long");
            }
        }
    }

    private static int readU16(byte[] a, int off) {
        return (a[off] &  0xFF) | ((a[off + 1] &  0xFF) << 8);
    }

    private static long readU32(byte[] a, int off) {
        return (readU16(a, off) &  0xFFFFFFFFL) | ((readU16(a, off + 2) &  0xFFFFFFFFL) << 16);
    }

    private static long readU64(byte[] a, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (a[off + i] &  0xFF) << (8 * i);
        }
        return v;
    }
}