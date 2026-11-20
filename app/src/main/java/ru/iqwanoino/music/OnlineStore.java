package ru.iqwanoino.music;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Menyimpan metadata lagu online yang pernah diputar atau dimasukkan ke playlist, supaya
 * playlist dan riwayat (yang hanya menyimpan ID) tetap bisa menampilkan dan memutarnya lagi.
 */
final class OnlineStore {

    private static final String KEY = "online_songs";
    private static final int MAX = 300;

    private static LinkedHashMap<Long, Library.Song> cache;

    private OnlineStore() { }

    private static synchronized LinkedHashMap<Long, Library.Song> load(Context c) {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        try {
            JSONArray a = new JSONArray(Prefs.prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Library.Song s = fromJson(a.getJSONObject(i));
                cache.put(s.id, s);
            }
        } catch (Exception ignored) { }
        return cache;
    }

    static synchronized Library.Song get(Context c, long id) {
        return load(c).get(id);
    }

    static synchronized void put(Context c, Library.Song s) {
        if (s == null || !s.isOnline()) return;
        Map<Long, Library.Song> m = load(c);
        m.remove(s.id);
        m.put(s.id, s);
        while (m.size() > MAX) m.remove(m.keySet().iterator().next());
        save(c);
    }

    private static void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Library.Song s : cache.values()) a.put(toJson(s));
            Prefs.prefs(c).edit().putString(KEY, a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static JSONObject toJson(Library.Song s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("s", s.source);
        o.put("r", s.ref);
        o.put("t", s.title);
        o.put("a", s.artist);
        o.put("d", s.durationMs);
        o.put("u", s.artUrl);
        return o;
    }

    static Library.Song fromJson(JSONObject o) {
        return Library.Song.online(o.optInt("s"), o.optString("r"), o.optString("t"),
                o.optString("a"), o.optInt("d"), o.optString("u"));
    }
}
