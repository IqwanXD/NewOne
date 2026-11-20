package ru.iqwanoino.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Internet Archive: arsip audio publik (banyak musik domain publik dan Creative Commons).
 * Tanpa API key. Pencarian lewat advancedsearch, file MP3 lewat endpoint metadata.
 */
final class SrcArchive implements Online.Source {

    private static final int ROWS = 30;

    @Override
    public int id() {
        return Library.SRC_ARCHIVE;
    }

    @Override
    public List<Library.Song> search(String q, int page) throws Exception {
        String term = q.replaceAll("[()\"\\[\\]:]", " ").trim();
        String query = "mediatype:audio AND (title:(" + term + ") OR creator:(" + term + "))";
        return query(query, "", page);
    }

    @Override
    public List<Library.Song> trending(int page) throws Exception {
        return query("mediatype:audio", "downloads desc", page);
    }

    @Override
    public String resolve(Library.Song s) throws Exception {
        JSONObject m = new JSONObject(Net.get("https://archive.org/metadata/" + Net.enc(s.ref), 8000));
        JSONArray files = m.optJSONArray("files");
        String pick = null;
        int best = 99;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String name = f.optString("name", "");
                String fmt = f.optString("format", "").toLowerCase(java.util.Locale.ROOT);
                int rank = 99;
                if (fmt.equals("vbr mp3")) rank = 1;
                else if (fmt.contains("mp3")) rank = 2;
                else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) rank = 3;
                if (rank < best) {
                    best = rank;
                    pick = name;
                }
            }
        }
        if (pick == null) throw new IOException("Tidak ada file MP3 untuk item ini");
        return "https://archive.org/download/" + s.ref + "/" + encodePath(pick);
    }

    private static String encodePath(String name) throws Exception {
        return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
    }

    private static List<Library.Song> query(String q, String sort, int page) throws Exception {
        StringBuilder u = new StringBuilder("https://archive.org/advancedsearch.php?q=").append(Net.enc(q));
        u.append("&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=length");
        if (!sort.isEmpty()) u.append("&sort%5B%5D=").append(Net.enc(sort));
        u.append("&rows=").append(ROWS).append("&page=").append(page + 1).append("&output=json");

        JSONObject r = new JSONObject(Net.get(u.toString(), 6000));
        JSONObject resp = r.optJSONObject("response");
        JSONArray docs = resp == null ? null : resp.optJSONArray("docs");
        List<Library.Song> out = new ArrayList<>();
        if (docs == null) return out;
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.optJSONObject(i);
            if (d == null) continue;
            String id = d.optString("identifier", "");
            if (id.isEmpty()) continue;
            int dur = parseLength(d.optString("length", ""));
            if (dur <= 0) continue;
            out.add(Library.Song.online(Library.SRC_ARCHIVE, id, d.optString("title", id),
                    firstString(d, "creator"), dur * 1000, "https://archive.org/services/img/" + id));
        }
        return out;
    }

    /** Panjang bisa berupa detik desimal ("213.45") atau format menit ("3:33"). */
    private static int parseLength(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return YtInnerTube.parseDuration(s);
        }
    }

    private static String firstString(JSONObject d, String key) {
        Object v = d.opt(key);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            return a.length() > 0 ? a.optString(0, "") : "";
        }
        return v == null ? "" : String.valueOf(v);
    }
}
