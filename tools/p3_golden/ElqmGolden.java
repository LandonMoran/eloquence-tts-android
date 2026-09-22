import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class ElqmGolden {
    public static void main(String[] args) throws Exception {
        LanguageDetector d = LanguageDetectorBuilder
                .fromLanguages(
                        Language.ENGLISH, Language.GERMAN, Language.SPANISH, Language.FINNISH,
                        Language.FRENCH, Language.ITALIAN, Language.JAPANESE, Language.KOREAN,
                        Language.PORTUGUESE, Language.CHINESE)
                .withPreloadedLanguageModels()
                .build();
        String[] probes = {
                "The quick brown fox jumps over the lazy dog",
                "Das ist ein wunderbarer sonniger Tag in Berlin",
                "Tama on kaunis kesapaiva ja linnut laulavat",
                "kino wa tomodachi to eiga o mimashita",
                "jintian tianqi hen hao, women qu gongyuan sanbu ba",
                "This is a test sentence with some German mixed in: das Haus is beautiful",
                "12345 !@#$%^&*()",
                "emoji only"
        };
        StringBuilder sb = new StringBuilder();
        for (String p : probes) {
            Language top = d.detectLanguageOf(p);
            sb.append(p);
            sb.append('\n');
            sb.append("top=").append(top == null ? "NONE" : top).append('\n');
            SortedMap<Language, Double> conf = d.computeLanguageConfidenceValues(p);
            List<String> keys = new ArrayList<>();
            for (Language l : conf.keySet()) keys.add(l.name());
            Collections.sort(keys);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String n : keys) {
                Language l = Language.valueOf(n);
                long bits = Double.doubleToRawLongBits(conf.get(l));
                for (int i = 0; i < 8; i++) md.update((byte) (bits >> (8 * i)));
                sb.append(n).append('=').append(Double.toHexString(conf.get(l))).append('\n');
            }
            byte[] h = md.digest();
            for (byte b : h) sb.append(String.format("%02x", b));
            sb.append('\n');
        }
        System.out.print(sb);
    }
}