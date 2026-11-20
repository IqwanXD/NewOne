package ru.iqwanoino.music;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Riwayat lagu yang diputar: ID MediaStore, yang terbaru di depan. Disimpan di SharedPreferences. */
final class History {

    private static final String KEY = "history";
    private static final int MAX = 50;

    private History() { }

    /** Catat lagu yang mulai diputar. Tidak melakukan apa-apa jika fitur sejarah dimatikan. */
    static void add(Context c, long id) {
        if (!Prefs.showHistory()) return;
        List<Long> ids = ids(c);
        ids.remove(Long.valueOf(id));
        ids.add(0, id);
        while (ids.size() > MAX) ids.remove(ids.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Long v : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        Prefs.prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    /** ID lagu dari yang terbaru diputar. */
    static List<Long> ids(Context c) {
        List<Long> out = new ArrayList<>();
        String s = Prefs.prefs(c).getString(KEY, "");
        if (s.isEmpty()) return out;
        for (String part : s.split(",")) {
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }
}
