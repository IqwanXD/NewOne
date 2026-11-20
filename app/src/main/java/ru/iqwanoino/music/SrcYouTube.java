package ru.iqwanoino.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * YouTube. Pencarian dan resolve dijalankan paralel lewat dua jalur:
 *  - NewPipeExtractor (NewPipeBackend), jalur utama seperti aplikasi NewPipe
 *  - InnerTube (YtInnerTube), request langsung yang biasanya lebih cepat
 * Hasil pertama yang berisi dipakai; yang lain dibiarkan selesai di belakang. Piped jadi cadangan terakhir.
 */
final class SrcYouTube implements Online.Source {

    private static final long SEARCH_MS = 9000;
    private static final long RESOLVE_MS = 12000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private static final String[] PIPED = {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee",
            "https://pipedapi.leptons.xyz"
    };

    private static volatile String pipedGood;

    @Override
    public int id() {
        return Library.SRC_YOUTUBE;
    }

    @Override
    public List<Library.Song> search(String q, int page) throws Exception {
        if (page > 0) return YtInnerTube.search(q, page);      // halaman berikutnya: continuation InnerTube
        ExecutorCompletionService<List<Library.Song>> cs = new ExecutorCompletionService<>(POOL);
        cs.submit(() -> NewPipeBackend.search(q));
        cs.submit(() -> YtInnerTube.search(q, 0));
        long deadline = System.currentTimeMillis() + SEARCH_MS;
        for (int i = 0; i < 2; i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            Future<List<Library.Song>> f = cs.poll(left, TimeUnit.MILLISECONDS);
            if (f == null) break;
            try {
                List<Library.Song> r = f.get();
                if (r != null && !r.isEmpty()) return r;
            } catch (Exception ignored) { }
        }
        return pipedSearch(q);
    }

    @Override
    public List<Library.Song> trending(int page) {
        return new ArrayList<>();
    }

    @Override
    public String resolve(Library.Song s) throws Exception {
        final String id = s.ref;
        ExecutorCompletionService<String> cs = new ExecutorCompletionService<>(POOL);
        cs.submit(() -> NewPipeBackend.resolve(id));
        cs.submit(() -> YtInnerTube.resolve(id));
        long deadline = System.currentTimeMillis() + RESOLVE_MS;
        for (int i = 0; i < 2; i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            Future<String> f = cs.poll(left, TimeUnit.MILLISECONDS);
            if (f == null) break;
            try {
                String u = f.get();
                if (u != null && !u.isEmpty()) return u;
            } catch (Exception ignored) { }
        }
        return pipedResolve(id);
    }

    static String videoId(String url) {
        if (url == null) return "";
        int k = url.indexOf("v=");
        if (k < 0) return "";
        String id = url.substring(k + 2);
        int amp = id.indexOf('&');
        return amp > 0 ? id.substring(0, amp) : id;
    }

    // ------------------------------------------------------------ cadangan: Piped

    private static List<Library.Song> pipedSearch(String q) throws Exception {
        List<Library.Song> out = pipedParse(piped("/search?q=" + Net.enc(q) + "&filter=all"));
        return out;
    }

    private static String pipedResolve(String id) throws Exception {
        JSONObject r = piped("/streams/" + Net.enc(id));
        JSONArray a = r.optJSONArray("audioStreams");
        if (a == null || a.length() == 0) throw new IOException("Stream audio tidak tersedia");
        String best = null, any = null;
        int bBest = -1, bAny = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String url = Online.str(o, "url");
            if (url.isEmpty()) continue;
            int br = o.optInt("bitrate", 0);
            if (Online.str(o, "mimeType").startsWith("audio/mp4") && br > bBest) {
                bBest = br;
                best = url;
            }
            if (br > bAny) {
                bAny = br;
                any = url;
            }
        }
        String pick = best != null ? best : any;
        if (pick == null) throw new IOException("Stream audio tidak tersedia");
        return pick;
    }

    private static List<Library.Song> pipedParse(JSONObject r) {
        List<Library.Song> out = new ArrayList<>();
        JSONArray a = r.optJSONArray("items");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String type = Online.str(o, "type");
            if (!type.isEmpty() && !"stream".equals(type)) continue;
            int dur = o.optInt("duration", -1);
            if (dur <= 0) continue;
            String id = videoId(Online.str(o, "url"));
            if (id.isEmpty()) continue;
            String artist = Online.str(o, "uploaderName");
            if (artist.endsWith(" - Topic")) artist = artist.substring(0, artist.length() - 8);
            out.add(Library.Song.online(Library.SRC_YOUTUBE, id, Online.str(o, "title"), artist,
                    dur * 1000, Online.str(o, "thumbnail")));
        }
        return out;
    }

    private static JSONObject piped(String path) throws Exception {
        List<String> order = new ArrayList<>();
        if (pipedGood != null) order.add(pipedGood);
        for (String h : PIPED) if (!order.contains(h)) order.add(h);
        Exception last = null;
        for (String h : order) {
            try {
                JSONObject o = new JSONObject(Net.get(h + path, 5000));
                pipedGood = h;
                return o;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("Instance Piped tidak terjangkau");
    }
}
