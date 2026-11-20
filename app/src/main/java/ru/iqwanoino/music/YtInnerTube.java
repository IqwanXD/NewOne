package ru.iqwanoino.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * YouTube lewat endpoint InnerTube (endpoint yang juga dipakai aplikasi YouTube sendiri).
 * Tanpa API key, tanpa library tambahan, dan jauh lebih cepat daripada mengekstrak halaman web:
 *  - pencarian: 1 request POST (klien WEB)
 *  - stream: klien VISIONOS (visitor_id sekali, lalu 1 request POST /player)
 *
 * Klien ANDROID, ANDROID_VR dan IOS sudah tidak memberi stream (NewPipeExtractor v0.27 membuangnya
 * sebagai rusak); yang masih bekerja hanya VISIONOS, jadi itu satu-satunya klien stream di sini.
 * Nilai VISIONOS_* disalin dari ClientsConstants.java NewPipeExtractor dan perlu ikut diperbarui
 * bila YouTube mulai menolak permintaan (lihat komentar sumber di tiap konstanta).
 * Format yang dipakai hanya yang berupa URL langsung tanpa parameter n (tidak ada JavaScript di sini).
 */
final class YtInnerTube {

    private static final String BASE = "https://www.youtube.com/youtubei/v1/";
    private static final String BASE_GAPIS = "https://youtubei.googleapis.com/youtubei/v1/";
    // Sama dengan WEB_HARDCODED_CLIENT_VERSION NewPipeExtractor v0.27.1
    private static final String WEB_VERSION = "2.20260805.01.00";
    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    // Klien VISIONOS (YouTube untuk Apple Vision Pro)
    private static final String VR_NAME = "VISIONOS";
    private static final String VR_VERSION = "1.04";
    private static final String VR_MODEL = "RealityDevice17,1";
    private static final String VR_OS_VERSION = "26.6.0.23O770";
    private static final String VR_UA = "com.google.visionos.youtube/" + VR_VERSION + "(" + VR_MODEL
            + "; U; CPU visionOS 26_6_0 like Mac OS X; GB)";
    private static final String NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final long VISITOR_TTL_MS = 60L * 60L * 1000L;

    private static volatile String visitorData;
    private static volatile long visitorAt;
    private static final java.util.Random RND = new java.util.Random();

    private YtInnerTube() { }

    // ------------------------------------------------------------ pencarian

    /** Token halaman berikutnya per kata kunci (disimpan selama proses hidup). */
    private static final java.util.Map<String, String> TOKENS =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, String>());

    static List<Library.Song> search(String q, int page) throws Exception {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB").put("clientVersion", WEB_VERSION)
                .put("hl", "id").put("gl", "ID");
        JSONObject context = new JSONObject().put("client", client);
        JSONObject body;
        if (page == 0) {
            body = new JSONObject().put("context", context).put("query", q);
        } else {
            String token = TOKENS.get(q);
            if (token == null) return new ArrayList<>();     // tidak ada halaman berikutnya
            body = new JSONObject().put("context", context).put("continuation", token);
        }
        JSONObject r = post("search", body, 6000, WEB_UA, "1", WEB_VERSION);
        List<Library.Song> out = new ArrayList<>();
        walkVideos(r, out, 0);
        String next = findToken(r, 0);
        if (next != null) TOKENS.put(q, next);
        else TOKENS.remove(q);
        return out;
    }

    private static String findToken(Object node, int depth) {
        if (depth > 40) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject cc = o.optJSONObject("continuationCommand");
            if (cc != null) {
                String t = cc.optString("token", "");
                if (!t.isEmpty()) return t;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String t = findToken(o.opt(keys.next()), depth + 1);
                if (t != null) return t;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String t = findToken(a.opt(i), depth + 1);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Cari semua videoRenderer di respons. Struktur respons berubah-ubah, jadi tidak pakai jalur tetap. */
    private static void walkVideos(Object node, List<Library.Song> out, int depth) {
        if (depth > 40 || out.size() >= 30) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null) {
                Library.Song s = parseVideo(v);
                if (s != null) out.add(s);
                return;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) walkVideos(o.opt(keys.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkVideos(a.opt(i), out, depth + 1);
        }
    }

    private static Library.Song parseVideo(JSONObject v) {
        String id = v.optString("videoId", "");
        if (id.isEmpty()) return null;
        JSONObject len = v.optJSONObject("lengthText");
        int dur = parseDuration(len == null ? "" : len.optString("simpleText", ""));
        if (dur <= 0) return null;                                  // siaran langsung / tidak diketahui
        String title = text(v.optJSONObject("title"));
        String artist = text(v.optJSONObject("ownerText"));
        if (artist.isEmpty()) artist = text(v.optJSONObject("longBylineText"));
        if (artist.endsWith(" - Topic")) artist = artist.substring(0, artist.length() - 8);
        String art = bestThumb(v.optJSONObject("thumbnail"));
        if (art.isEmpty()) art = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        return Library.Song.online(Library.SRC_YOUTUBE, id, title, artist, dur * 1000, art);
    }

    private static String text(JSONObject t) {
        if (t == null) return "";
        String s = t.optString("simpleText", "");
        if (!s.isEmpty()) return s;
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) b.append(r.optString("text", ""));
        }
        return b.toString();
    }

    private static String bestThumb(JSONObject t) {
        if (t == null) return "";
        JSONArray th = t.optJSONArray("thumbnails");
        if (th == null || th.length() == 0) return "";
        JSONObject last = th.optJSONObject(th.length() - 1);
        return last == null ? "" : last.optString("url", "");
    }

    /** "3:33" -> 213 detik, "1:02:03" -> 3723 detik. */
    static int parseDuration(String s) {
        if (s == null || s.isEmpty()) return -1;
        String[] p = s.trim().split(":");
        int total = 0;
        try {
            for (String part : p) total = total * 60 + Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        return total;
    }

    // ------------------------------------------------------------ stream

    /** UA yang harus dipakai saat mengunduh URL stream hasil resolve(), atau null bila bukan dari sini. */
    static String playbackUa(String url) {
        return url != null && url.contains("c=" + VR_NAME) ? VR_UA : null;
    }

    static String resolve(String videoId) throws Exception {
        Exception last = null;
        // Percobaan kedua memakai visitorData baru: visitorData lama bisa ditolak YouTube
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String cpn = nonce(16);
                JSONObject body = new JSONObject()
                        .put("context", visionContext(visitorData(attempt > 0)))
                        .put("videoId", videoId)
                        .put("cpn", cpn)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true);
                String url = BASE_GAPIS + "player?prettyPrint=false&t=" + nonce(12) + "&id=" + videoId;
                JSONObject r = postJson(url, body, 7000, visionHeaders());
                String status = r.optJSONObject("playabilityStatus") == null ? ""
                        : r.optJSONObject("playabilityStatus").optString("status", "");
                String u = pickAudio(r, cpn);
                if (u != null) return u;
                last = new IOException("VISIONOS tanpa stream audio" + (status.isEmpty() ? "" : " (" + status + ")"));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("YouTube tidak memberi stream audio langsung");
    }

    private static JSONObject visionContext(String vd) throws Exception {
        JSONObject cl = new JSONObject()
                .put("clientName", VR_NAME).put("clientVersion", VR_VERSION)
                .put("clientScreen", "WATCH").put("platform", "MOBILE")
                .put("deviceMake", "Apple").put("deviceModel", VR_MODEL)
                .put("osName", "visionOS").put("osVersion", VR_OS_VERSION)
                .put("hl", "en-GB").put("gl", "GB").put("utcOffsetMinutes", 0);
        if (vd != null && !vd.isEmpty()) cl.put("visitorData", vd);
        return new JSONObject().put("client", cl)
                .put("request", new JSONObject().put("internalExperimentFlags", new JSONArray()).put("useSsl", true))
                .put("user", new JSONObject().put("lockedSafetyMode", false));
    }

    private static java.util.Map<String, String> visionHeaders() {
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("User-Agent", VR_UA);
        h.put("X-Goog-Api-Format-Version", "2");
        return h;
    }

    /** visitorData untuk pengguna tanpa login; wajib ada di request /player. Disimpan satu jam. */
    private static String visitorData(boolean force) throws Exception {
        String v = visitorData;
        if (!force && v != null && System.currentTimeMillis() - visitorAt < VISITOR_TTL_MS) return v;
        JSONObject body = new JSONObject().put("context", visionContext(null));
        JSONObject r = postJson(BASE + "visitor_id?prettyPrint=false", body, 6000, visionHeaders());
        JSONObject rc = r.optJSONObject("responseContext");
        String got = rc == null ? "" : rc.optString("visitorData", "");
        if (got.isEmpty()) throw new IOException("visitorData tidak ada");
        visitorData = got;
        visitorAt = System.currentTimeMillis();
        return got;
    }

    private static String nonce(int len) {
        StringBuilder b = new StringBuilder(len);
        for (int i = 0; i < len; i++) b.append(NONCE_ALPHABET.charAt(RND.nextInt(NONCE_ALPHABET.length())));
        return b.toString();
    }

    /** Prioritas: m4a (paling aman untuk MediaPlayer), lalu webm/opus. Bitrate tertinggi. */
    private static String pickAudio(JSONObject r, String cpn) {
        JSONObject sd = r.optJSONObject("streamingData");
        if (sd == null) return null;
        JSONArray a = sd.optJSONArray("adaptiveFormats");
        if (a == null) return null;
        String bestMp4 = null, bestWebm = null;
        int brMp4 = -1, brWebm = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject f = a.optJSONObject(i);
            if (f == null) continue;
            String mime = f.optString("mimeType", "");
            if (!mime.startsWith("audio/")) continue;
            String url = f.optString("url", "");
            if (url.isEmpty()) continue;                             // butuh cipher: dilewati
            if (url.contains("&n=") || url.contains("?n=")) continue; // butuh JavaScript untuk parameter n
            JSONObject track = f.optJSONObject("audioTrack");
            if (track != null && track.has("audioIsDefault") && !track.optBoolean("audioIsDefault", true)) continue;
            int br = f.optInt("bitrate", 0);
            if (mime.startsWith("audio/mp4") && br > brMp4) {
                brMp4 = br;
                bestMp4 = url;
            } else if (mime.startsWith("audio/webm") && br > brWebm) {
                brWebm = br;
                bestWebm = url;
            }
        }
        String pick = bestMp4 != null ? bestMp4 : bestWebm;
        return pick == null ? null : pick + "&cpn=" + cpn;
    }

    // ------------------------------------------------------------ HTTP

    /** POST JSON ke URL penuh dengan header sendiri (klien non-web). */
    private static JSONObject postJson(String url, JSONObject body, int timeoutMs,
                                       java.util.Map<String, String> headers) throws Exception {
        HttpURLConnection c = Net.open(url, timeoutMs);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            for (java.util.Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            byte[] data = body.toString().getBytes("UTF-8");
            OutputStream o = c.getOutputStream();
            try {
                o.write(data);
            } finally {
                o.close();
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            return new JSONObject(new String(Cache.readAll(in), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject post(String endpoint, JSONObject body, int timeoutMs, String ua,
                                   String clientId, String clientVersion) throws Exception {
        HttpURLConnection c = Net.open(BASE + endpoint + "?prettyPrint=false", timeoutMs);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("X-YouTube-Client-Name", clientId);
            c.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            byte[] data = body.toString().getBytes("UTF-8");
            OutputStream o = c.getOutputStream();
            try {
                o.write(data);
            } finally {
                o.close();
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            return new JSONObject(new String(Cache.readAll(in), "UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
