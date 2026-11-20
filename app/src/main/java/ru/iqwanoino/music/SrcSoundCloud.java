package ru.iqwanoino.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SoundCloud tanpa API key: client_id publik di-scrape dari bundle JS situs web mereka. */
final class SrcSoundCloud implements Online.Source {

    private static final String API = "https://api-v2.soundcloud.com";
    private static final String PREF = "sc_client_id";
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"");
    private static final Pattern CID = Pattern.compile("client_id\\s*[:=]\\s*\"([A-Za-z0-9]{32})\"");

    private static volatile String clientId;

    @Override
    public int id() {
        return Library.SRC_SOUNDCLOUD;
    }

    @Override
    public List<Library.Song> search(String q, int page) throws Exception {
        JSONObject r = api("/search/tracks?q=" + Net.enc(q) + "&limit=25&offset=" + (page * 25) + "&linked_partitioning=1");
        return parse(r.optJSONArray("collection"), false);
    }

    @Override
    public List<Library.Song> trending(int page) throws Exception {
        JSONObject r = api("/charts?kind=top&genre=soundcloud%3Agenres%3Aall-music&limit=25&offset=" + (page * 25));
        return parse(r.optJSONArray("collection"), true);
    }

    @Override
    public String resolve(Library.Song s) throws Exception {
        JSONObject t = api("/tracks/" + Net.enc(s.ref));
        String auth = Online.str(t, "track_authorization");
        JSONObject media = t.optJSONObject("media");
        JSONArray tc = media == null ? null : media.optJSONArray("transcodings");
        if (tc == null || tc.length() == 0) throw new IOException("Lagu tidak bisa di-stream");

        String progressive = null, hls = null;
        for (int i = 0; i < tc.length(); i++) {
            JSONObject o = tc.optJSONObject(i);
            if (o == null) continue;
            JSONObject f = o.optJSONObject("format");
            String proto = f == null ? "" : Online.str(f, "protocol");
            String mime = f == null ? "" : Online.str(f, "mime_type");
            String url = Online.str(o, "url");
            if ("progressive".equals(proto) && mime.contains("mpeg")) progressive = url;
            else if ("hls".equals(proto) && mime.contains("mpeg") && hls == null) hls = url;
        }
        String pick = progressive != null ? progressive : hls;
        if (pick == null) throw new IOException("Format audio tidak didukung");

        String extra = auth.isEmpty() ? "" : "&track_authorization=" + Net.enc(auth);
        JSONObject r = getJson(pick + (pick.contains("?") ? "&" : "?") + "client_id=" + clientId(false) + extra, true);
        String url = Online.str(r, "url");
        if (url.isEmpty()) throw new IOException("Stream tidak ditemukan");
        return url;
    }

    // ------------------------------------------------------------ internal

    private static List<Library.Song> parse(JSONArray a, boolean wrapped) {
        List<Library.Song> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            if (wrapped) t = t.optJSONObject("track");
            if (t == null) continue;
            if (!"track".equals(Online.str(t, "kind")) && t.has("kind")) continue;
            String policy = Online.str(t, "policy");
            if ("BLOCK".equals(policy) || "SNIP".equals(policy)) continue;     // tidak bisa diputar penuh
            if (!t.optBoolean("streamable", true)) continue;
            long id = t.optLong("id", 0);
            if (id == 0) continue;

            JSONObject user = t.optJSONObject("user");
            String art = Online.str(t, "artwork_url");
            if (art.isEmpty()) art = Online.str(user, "avatar_url");
            art = art.replace("-large.", "-t500x500.");

            out.add(Library.Song.online(Library.SRC_SOUNDCLOUD, String.valueOf(id),
                    Online.str(t, "title"), Online.str(user, "username"),
                    t.optInt("duration", 0), art));
        }
        return out;
    }

    private static JSONObject api(String path) throws Exception {
        String url = API + path + (path.contains("?") ? "&" : "?") + "client_id=" + clientId(false);
        return getJson(url, true);
    }

    /** GET JSON; bila ditolak (client_id kedaluwarsa) ambil client_id baru sekali lalu coba lagi. */
    private static JSONObject getJson(String url, boolean retry) throws Exception {
        try {
            return new JSONObject(Net.get(url, 12000));
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (retry && (m.contains("401") || m.contains("403"))) {
                String old = clientId;
                String fresh = clientId(true);
                if (old != null) url = url.replace(old, fresh);
                return getJson(url, false);
            }
            throw e;
        }
    }

    private static synchronized String clientId(boolean refresh) throws Exception {
        if (!refresh && clientId != null) return clientId;
        if (!refresh && Online.ctx() != null) {
            String saved = Prefs.prefs(Online.ctx()).getString(PREF, "");
            if (!saved.isEmpty()) {
                clientId = saved;
                return clientId;
            }
        }
        String html = Net.get("https://soundcloud.com/", 15000);
        List<String> scripts = new ArrayList<>();
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) scripts.add(m.group(1));
        // client_id biasanya ada di bundle yang paling belakang
        for (int i = scripts.size() - 1; i >= 0; i--) {
            try {
                String js = Net.get(scripts.get(i), 15000);
                Matcher c = CID.matcher(js);
                if (c.find()) {
                    clientId = c.group(1);
                    if (Online.ctx() != null) Prefs.prefs(Online.ctx()).edit().putString(PREF, clientId).apply();
                    return clientId;
                }
            } catch (IOException ignored) { }
        }
        throw new IOException("client_id SoundCloud tidak ditemukan");
    }
}
