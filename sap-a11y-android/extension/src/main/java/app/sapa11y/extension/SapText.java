package app.sapa11y.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speech text helpers, ported from the Windows mod's TextExtractor and
 * PageManagerPatches so both platforms say the same thing.
 */
public final class SapText {
    private static final Pattern SPRITE =
            Pattern.compile("(?i)<sprite[^>]*name\\s*=\\s*\"?([A-Za-z]+)\"?[^>]*>");
    private static final Pattern BROKEN_LINE = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\u200B\u200C\u200D\u2060\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private SapText() {}

    /**
     * Sprites that stand for a word say it ("+2<sprite name=Attack>" is
     * "+2 Attack"), then the remaining rich-text tags go.
     */
    public static String clean(String text) {
        if (text == null) return "";
        text = spritesAsWords(text);
        text = BROKEN_LINE.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll("");
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    private static String spritesAsWords(String text) {
        Matcher matcher = SPRITE.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String word = wordForSprite(name);
            // The tag sits where the space was: "+2<sprite name=Attack>" means
            // "+2 attack", and the same goes for text right after the tag.
            // The character before the tag comes from the source text: the
            // buffer has not been filled with it yet at this point.
            char before = matcher.start() > 0 ? text.charAt(matcher.start() - 1) : ' ';
            if (!word.isEmpty() && !Character.isWhitespace(before)) {
                word = " " + word;
            }
            if (!word.isEmpty() && matcher.end() < text.length()
                    && !Character.isWhitespace(text.charAt(matcher.end()))) {
                word = word + " ";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(word));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String wordForSprite(String name) {
        if (name == null) return "";
        switch (name.toLowerCase()) {
            case "attack":
            case "damage":
                return "attack";
            case "health":
            case "hitpoints":
            case "hp":
                return "health";
            case "gold":
            case "coin":
                return "gold";
            case "tier":
                return "tier";
            case "experience":
            case "xp":
                return "experience";
            case "lives":
            case "heart":
                return "lives";
            case "wins":
            case "trophy":
                return "wins";
            default:
                return "";
        }
    }

    /** "UserSettings" -> "User Settings", the game's own screen naming. */
    public static String splitCamelCase(String name) {
        if (name == null || name.isEmpty()) return "";
        String spaced = CAMEL.matcher(name).replaceAll(" ");
        return spaced.replace('_', ' ').trim();
    }

    /** "1 win", "3 wins": a count with its noun. */
    public static String count(Object number, String one, String many) {
        String n = number == null ? "" : number.toString().trim();
        return "1".equals(n) ? "1 " + one : n + " " + many;
    }

    /**
     * Joins lines with ". ", unless one already ends a sentence: lines usually
     * end in a full stop, and a plain join read "trigger type.. Hey!".
     */
    public static String joinSentences(Collection<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String raw : parts) {
            if (raw == null) continue;
            String part = raw.trim();
            if (part.isEmpty()) continue;
            if (sb.length() > 0) {
                char end = sb.charAt(sb.length() - 1);
                sb.append(end == '.' || end == '!' || end == '?' || end == ':' ? " " : ". ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** Joins a control's own lines, the way the tree's label is built. */
    public static String joinLabels(Collection<String> parts) {
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String value = clean(part);
            if (!value.isEmpty()) cleaned.add(value);
        }
        return joinSentences(cleaned);
    }
}
