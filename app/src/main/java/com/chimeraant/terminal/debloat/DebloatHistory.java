package com.chimeraant.terminal.debloat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers every debloat action so it can be undone.
 *
 * Disabling or uninstalling for user 0 is reversible, but only if we know what
 * was changed. This keeps an on-device log of package, action and timestamp.
 */
public final class DebloatHistory {

    public static class Entry {
        public final String action;
        public final String packageName;
        public final long timestamp;

        public Entry(String action, String packageName, long timestamp) {
            this.action = action;
            this.packageName = packageName;
            this.timestamp = timestamp;
        }
    }

    private static final String PREFS = "chimera_debloat";
    private static final String KEY_LOG = "log";
    private static final int MAX_ENTRIES = 500;

    private DebloatHistory() {
    }

    public static void record(Context context, String action, String packageName) {
        List<Entry> entries = load(context);
        entries.add(new Entry(action, packageName, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        save(context, entries);
    }

    public static List<Entry> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_LOG, "[]");
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                entries.add(new Entry(
                        object.optString("action"),
                        object.optString("package"),
                        object.optLong("time")));
            }
        } catch (JSONException e) {
            // Corrupt log; start fresh rather than crash.
        }
        return entries;
    }

    /** Packages that were disabled or removed and can be restored. */
    public static List<String> restorablePackages(Context context) {
        List<String> packages = new ArrayList<>();
        for (Entry entry : load(context)) {
            if (("uninstall".equals(entry.action) || "disable".equals(entry.action))
                    && !packages.contains(entry.packageName)) {
                packages.add(entry.packageName);
            }
        }
        return packages;
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LOG).apply();
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject object = new JSONObject();
            try {
                object.put("action", entry.action);
                object.put("package", entry.packageName);
                object.put("time", entry.timestamp);
                array.put(object);
            } catch (JSONException ignored) {
                // Skip malformed entry.
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LOG, array.toString()).apply();
    }
}
