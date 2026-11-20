package ru.iqwanoino.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Artwork: diambil dari tag lagu di background, disimpan di cache memori kecil. */
final class Art {

    interface Callback {
        void onArt(long id, Bitmap bmp);
    }

    private static final int MAX_SIDE = 512;

    private static final LruCache<Long, Bitmap> CACHE = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Lagu yang tidak punya artwork, supaya tidak dicoba berulang. */
    private static final Set<Long> NONE = Collections.synchronizedSet(new HashSet<Long>());
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Art() { }

    static Bitmap get(long id) {
        return CACHE.get(id);
    }

    /** Pasang artwork ke ImageView; placeholder dipakai saat belum ada gambar. */
    static void bind(final ImageView iv, final Library.Song s, Drawable placeholder, final int padPx) {
        iv.setTag(s.id);
        Bitmap b = get(s.id);
        if (b != null) {
            iv.setPadding(0, 0, 0, 0);
            iv.setImageBitmap(b);
            return;
        }
        iv.setPadding(padPx, padPx, padPx, padPx);
        iv.setImageDrawable(placeholder);
        load(iv.getContext(), s, (id, bmp) -> {
            Object tag = iv.getTag();
            if (bmp != null && tag != null && tag.equals(id)) {
                iv.setPadding(0, 0, 0, 0);
                iv.setImageBitmap(bmp);
            }
        });
    }

    /** Memuat artwork di background. Callback dipanggil di main thread, hanya jika benar-benar dimuat. */
    static void load(Context c, final Library.Song s, final Callback cb) {
        if (CACHE.get(s.id) != null || NONE.contains(s.id)) return;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            Bitmap b = decode(app, s);
            if (b != null) CACHE.put(s.id, b);
            else NONE.add(s.id);
            if (cb != null) MAIN.post(() -> cb.onArt(s.id, b));
        });
    }

    private static Bitmap decode(Context c, Library.Song s) {
        if (s.isOnline()) {
            if (s.artUrl.isEmpty()) return null;
            try {
                return decodeBytes(Cache.artBytes(s.artUrl));
            } catch (Exception | OutOfMemoryError e) {
                return null;
            }
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, s.uri());
            return decodeBytes(r.getEmbeddedPicture());
        } catch (Exception | OutOfMemoryError e) {
            // OOM di thread latar tidak boleh menjatuhkan aplikasi; artwork cukup dilewati.
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static Bitmap decodeBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / sample > MAX_SIDE * 2 || o.outHeight / sample > MAX_SIDE * 2) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
