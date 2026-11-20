package ru.iqwanoino.music;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Koordinator sumber musik online tanpa API key. Hasil tiap sumber langsung tampil begitu tiba.
 * Sumber yang gagal diam saja; pesan error hanya muncul bila tidak ada hasil sama sekali.
 * Setiap halaman di-cache terpisah (kunci: kata kunci + nomor halaman).
 */
final class Online {

    interface Source {
        int id();

        List<Library.Song> search(String q, int page) throws Exception;

        List<Library.Song> trending(int page) throws Exception;

        String resolve(Library.Song s) throws Exception;
    }

    /** Dipanggil di main thread. done = false berarti sumber lain masih berjalan. */
    interface Callback {
        void onUpdate(List<Library.Song> songs, String error, boolean done);
    }

    private static final long GIVE_UP_MS = 9000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Source[] SOURCES = {
            new SrcSoundCloud(), new SrcAudius(), new SrcYouTube(), new SrcArchive()
    };

    private static Context app;

    private Online() { }

    static synchronized void init(Context c) {
        if (app == null) {
            app = c.getApplicationContext();
            Cache.init(app);
        }
    }

    static Context ctx() {
        return app;
    }

    /**
     * Ambil satu halaman hasil dari semua sumber. q kosong = populer.
     * fresh = true melewati cache (untuk tarik-untuk-muat).
     */
    static void fetch(Context c, final String q, final int page, final boolean fresh, final Callback cb) {
        init(c);
        final String term = q == null ? "" : q.trim();
        final boolean trending = term.isEmpty();
        final String key = (trending ? "#populer" : term.toLowerCase(Locale.ROOT)) + "#" + page;
        POOL.execute(() -> {
            if (!fresh) {
                List<Library.Song> hit = Cache.searchGet(key);
                if (hit != null && !hit.isEmpty()) {
                    MAIN.post(() -> cb.onUpdate(hit, null, true));
                    return;
                }
            }
            if (!Net.online(app)) {
                MAIN.post(() -> cb.onUpdate(new ArrayList<Library.Song>(), "Tidak ada koneksi internet", true));
                return;
            }
            MAIN.post(() -> new Gather(cb, key, term, page).start(trending));
        });
    }

    /** Pengumpul hasil satu halaman. Semua state hanya disentuh dari main thread. */
    private static final class Gather {
        private final Callback cb;
        private final String key, term;
        private final int page;
        private final Map<Integer, List<Library.Song>> got = new HashMap<>();
        private int left;
        private boolean finished;
        private final Runnable giveUp = () -> finish(merge());

        Gather(Callback cb, String key, String term, int page) {
            this.cb = cb;
            this.key = key;
            this.term = term;
            this.page = page;
        }

        void start(final boolean trending) {
            left = SOURCES.length;
            MAIN.postDelayed(giveUp, GIVE_UP_MS);
            for (int i = 0; i < SOURCES.length; i++) {
                final int idx = i;
                final Source s = SOURCES[i];
                POOL.execute(() -> {
                    List<Library.Song> r = null;
                    try {
                        r = trending ? s.trending(page) : s.search(term, page);
                    } catch (Exception ignored) { }
                    final List<Library.Song> fr = r;
                    MAIN.post(() -> part(idx, fr));
                });
            }
        }

        private void part(int idx, List<Library.Song> r) {
            if (finished) return;
            if (r != null && !r.isEmpty()) got.put(idx, r);
            left--;
            if (left <= 0) {
                finish(merge());
                return;
            }
            List<Library.Song> merged = merge();
            if (!merged.isEmpty()) cb.onUpdate(merged, null, false);
        }

        private void finish(List<Library.Song> merged) {
            if (finished) return;
            finished = true;
            MAIN.removeCallbacks(giveUp);
            String err = merged.isEmpty() ? "Tidak ada hasil dari internet" : null;
            cb.onUpdate(merged, err, true);
            if (!merged.isEmpty()) {
                final List<Library.Song> copy = new ArrayList<>(merged);
                POOL.execute(() -> Cache.searchPut(key, copy));
            }
        }

        /** Gabung bergantian antar sumber supaya semua sumber terlihat di bagian atas. */
        private List<Library.Song> merge() {
            List<Library.Song> out = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            int max = 0;
            for (List<Library.Song> p : got.values()) max = Math.max(max, p.size());
            for (int i = 0; i < max; i++) {
                for (int s = 0; s < SOURCES.length; s++) {
                    List<Library.Song> p = got.get(s);
                    if (p != null && i < p.size() && seen.add(p.get(i).id)) out.add(p.get(i));
                }
            }
            return out;
        }
    }

    /**
     * URL audio untuk diputar. Sinkron: panggil dari thread latar. Jika stream YouTube gagal,
     * lagu dengan judul yang sama dicari di sumber lain dan itu yang diputar.
     */
    static String resolve(Library.Song s) throws Exception {
        try {
            return direct(s);
        } catch (Exception first) {
            if (s.source != Library.SRC_YOUTUBE) throw first;
            for (Source src : SOURCES) {
                if (src.id() == s.source) continue;
                try {
                    for (Library.Song m : src.search(s.title, 0)) {
                        if (sameSong(s, m)) return src.resolve(m);
                    }
                } catch (Exception ignored) { }
            }
            throw new java.io.IOException("YouTube tidak bisa diputar, dan tidak ada padanan di sumber lain");
        }
    }

    private static String direct(Library.Song s) throws Exception {
        for (Source src : SOURCES) {
            if (src.id() == s.source) return src.resolve(s);
        }
        throw new IllegalArgumentException("Sumber tidak dikenal");
    }

    /** Judul sama (abaikan huruf dan simbol) dan durasi tidak beda jauh. */
    private static boolean sameSong(Library.Song a, Library.Song b) {
        String x = norm(a.title), y = norm(b.title);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (!(x.equals(y) || x.contains(y) || y.contains(x))) return false;
        return !(a.durationMs > 0 && b.durationMs > 0 && Math.abs(a.durationMs - b.durationMs) > 15000);
    }

    private static String norm(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }
}
