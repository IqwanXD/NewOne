package ru.iqwanoino.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Audius: katalog musik independen, publik dan tanpa API key (cukup app_name). */
final class SrcAudius implements Online.Source {

    private static final String APP = "app_name=MusicApp";
    private static final String[] HOSTS = {
            "https://api.audius.co",
            "https://discoveryprovider.audius.co",
            "https://discoveryprovider2.audius.co",
            "https://discoveryprovider3.audius.co"
    };

    private static volatile String host = HOSTS[0];

    @Override
    public int id() {
        return Library.SRC_AUDIUS;
    }

    @Override
    public List<Library.Song> search(String q, int page) throws Exception {
        return parse(api("/v1/tracks/search?query=" + Net.enc(q) + "&limit=25&offset=" + (page * 25) + "&" + APP));
    }

    @Override
    public List<Library.Song> trending(int page) throws Exception {
        return parse(api("/v1/tracks/trending?limit=25&offset=" + (page * 25) + "&" + APP));
    }

    @Override
    public String resolve(Library.Song s) throws Exception {
        String path = "/v1/tracks/" + Net.enc(s.ref) + "/stream?" + APP;
        try {
            // no_redirect: server mengembalikan URL node konten sebagai JSON
            JSONObject r = api(path + "&no_redirect=true");
            String url = Online.str(r, "data");
            if (!url.isEmpty()) return url;
        } catch (Exception ignored) { }
        return host + path;     // MediaPlayer ikut redirect 302
    }

    private static List<Library.Song> parse(JSONObject r) {
        List<Library.Song> out = new ArrayList<>();
        JSONArray a = r.optJSONArray("data");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            if (!t.optBoolean("is_streamable", true)) continue;
            String id = Online.str(t, "id");
            if (id.isEmpty()) continue;
            JSONObject art = t.optJSONObject("artwork");
            String img = art == null ? "" : Online.str(art, "480x480");
            if (img.isEmpty() && art != null) img = Online.str(art, "150x150");
            JSONObject user = t.optJSONObject("user");
            out.add(Library.Song.online(Library.SRC_AUDIUS, id, Online.str(t, "title"),
                    Online.str(user, "name"), t.optInt("duration", 0) * 1000, img));
        }
        return out;
    }

    /** Coba host terakhir yang berhasil dulu, lalu host lain, lalu daftar host dari server. */
    private static JSONObject api(String path) throws Exception {
        List<String> order = new ArrayList<>();
        order.add(host);
        for (String h : HOSTS) if (!order.contains(h)) order.add(h);
        Exception last = null;
        for (String h : order) {
            try {
                JSONObject o = new JSONObject(Net.get(h + path, 6000));
                host = h;
                return o;
            } catch (Exception e) {
                last = e;
            }
        }
        try {
            JSONArray list = new JSONObject(Net.get("https://api.audius.co", 8000)).optJSONArray("data");
            if (list != null) {
                for (int i = 0; i < list.length() && i < 4; i++) {
                    String h = list.optString(i);
                    if (h.isEmpty() || order.contains(h)) continue;
                    try {
                        JSONObject o = new JSONObject(Net.get(h + path, 6000));
                        host = h;
                        return o;
                    } catch (Exception e) {
                        last = e;
                    }
                }
            }
        } catch (Exception e) {
            last = e;
        }
        throw last != null ? last : new IOException("Audius tidak terjangkau");
    }
}
