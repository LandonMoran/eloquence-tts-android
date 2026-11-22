package app.sapa11y.extension;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Keyword dictionary, ported from the Windows mod's DictionaryMenu.
 *
 * Same data (the wiki-scraped WikiDictionary.json that ships in the mod),
 * same behaviour: categories, entries, and a typed search over names and
 * descriptions. Browsing is Up/Down, categories Left/Right, Enter reads the
 * full entry, Escape closes.
 */
final class SapDictionary {
    private static final String TAG = "SapA11y";
    private static final String ALL = "All";

    static final class Entry {
        final String category;
        final String name;
        final String description;

        Entry(String category, String name, String description) {
            this.category = category;
            this.name = name;
            this.description = description;
        }
    }

    /** Pet name to wiki description, built once from the same bundled file. */
    private static java.util.Map<String, String> byName;

    /**
     * Wiki description for a pet, or null when the name is not in the file.
     *
     * Used where a pet card's localized ability text did not resolve: the card
     * still has to say what the pet does. The dictionary is the same
     * WikiDictionary.json the browse menu reads, so the two cannot disagree.
     */
    static synchronized String descriptionFor(Context context, String name) {
        if (context == null || name == null || name.trim().isEmpty()) return null;
        if (byName == null) {
            java.util.Map<String, String> map = new java.util.HashMap<>();
            try (InputStream in = context.getAssets().open("sapa11y/WikiDictionary.json")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
                JSONArray array = new JSONArray(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.optJSONObject(i);
                    if (o == null) continue;
                    String key = o.optString("name", "").trim().toLowerCase(Locale.ROOT);
                    String description = o.optString("description", "").trim();
                    if (!key.isEmpty() && !description.isEmpty()) map.put(key, description);
                }
            } catch (Exception e) {
                Log.w(TAG, "dictionary unavailable for pet descriptions", e);
            }
            byName = map;
        }
        return byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final List<Entry> view = new ArrayList<>();
    private final StringBuilder query = new StringBuilder();

    private int categoryIndex;
    private int index;
    private boolean open;

    SapDictionary(Context context) {
        categories.add(ALL);
        load(context);
    }

    private void load(Context context) {
        try (InputStream in = context.getAssets().open("sapa11y/WikiDictionary.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            JSONArray array = new JSONArray(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String category = o.optString("category", "Other");
                all.add(new Entry(category, o.optString("name", ""), o.optString("description", "")));
                if (!categories.contains(category)) categories.add(category);
            }
            Collections.sort(categories.subList(1, categories.size()));
        } catch (Exception e) {
            Log.w(TAG, "dictionary unavailable", e);
        }
    }

    boolean isOpen() {
        return open;
    }

    boolean isEmpty() {
        return all.isEmpty();
    }

    void open() {
        open = true;
        categoryIndex = 0;
        index = 0;
        query.setLength(0);
        refresh();
    }

    void close() {
        open = false;
    }

    private void refresh() {
        view.clear();
        String needle = query.toString().toLowerCase(Locale.ROOT);
        String category = categories.get(categoryIndex);
        for (Entry entry : all) {
            if (categoryIndex != 0 && !entry.category.equals(category)) continue;
            if (!needle.isEmpty()) {
                String haystack = (entry.name + " " + entry.description).toLowerCase(Locale.ROOT);
                if (!haystack.contains(needle)) continue;
            }
            view.add(entry);
        }
        if (index >= view.size()) index = view.isEmpty() ? 0 : view.size() - 1;
    }

    String move(int delta) {
        if (view.isEmpty()) return "No entries.";
        index = Math.floorMod(index + delta, view.size());
        return current();
    }

    String moveCategory(int delta) {
        categoryIndex = Math.floorMod(categoryIndex + delta, categories.size());
        index = 0;
        refresh();
        return categories.get(categoryIndex) + ", " + SapText.count(view.size(), "entry", "entries") + ". " + current();
    }

    String type(char c) {
        query.append(c);
        refresh();
        index = 0;
        return searchResult();
    }

    String backspace() {
        if (query.length() > 0) query.setLength(query.length() - 1);
        refresh();
        index = 0;
        return searchResult();
    }

    private String searchResult() {
        if (view.isEmpty()) return "Search " + query + ". No matches.";
        return "Search " + query + ", " + SapText.count(view.size(), "match", "matches") + ". " + view.get(0).name;
    }

    String current() {
        if (view.isEmpty()) return "No entries.";
        Entry entry = view.get(index);
        return entry.name + ". " + (index + 1) + " of " + view.size() + ". " + entry.description;
    }

    String summary() {
        String position = view.isEmpty() ? "0 of 0" : (index + 1) + " of " + view.size();
        return categories.get(categoryIndex) + ", " + SapText.count(view.size(), "entry", "entries")
                + ", " + position + ".";
    }
}
