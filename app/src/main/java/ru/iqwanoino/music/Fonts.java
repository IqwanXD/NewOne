package ru.iqwanoino.music;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Font aplikasi. Urutan: font kustom pilihan pengguna (disalin ke penyimpanan internal),
 * lalu res/font/me.ttf (Android 8.0+), lalu font sistem.
 */
final class Fonts {
    private static Typeface regular, bold;
    private static String loadedPath;

    private Fonts() { }

    static Typeface regular(Context c) {
        load(c);
        return regular;
    }

    /** Font biasanya hanya satu bobot, jadi tebal dibuat oleh sistem. */
    static Typeface bold(Context c) {
        load(c);
        return bold;
    }

    private static void load(Context c) {
        Prefs.init(c);
        String path = Prefs.customFont();
        if (regular != null && path.equals(loadedPath)) return;

        Typeface t = null;
        if (!path.isEmpty()) t = fromFile(new File(path));
        if (t == null && Build.VERSION.SDK_INT >= 26) {
            try { t = c.getApplicationContext().getResources().getFont(R.font.me); } catch (Exception ignored) { }
        }
        regular = t != null ? t : Typeface.DEFAULT;
        bold = Typeface.create(regular, Typeface.BOLD);
        loadedPath = path;
    }

    /** null jika file bukan font yang bisa dibaca Android. */
    private static Typeface fromFile(File f) {
        if (!f.exists()) return null;
        try {
            if (Build.VERSION.SDK_INT >= 26) return new Typeface.Builder(f).build();
            return Typeface.createFromFile(f);
        } catch (Exception e) {
            return null;
        }
    }

    /** Salin font pilihan pengguna dan jadikan font aplikasi. false jika file tidak valid. */
    static boolean install(Context c, Uri uri) {
        Prefs.init(c);
        File dir = new File(c.getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // Nama unik supaya path berubah dan font lama tidak tertahan di cache.
        File out = new File(dir, "custom_" + System.currentTimeMillis());
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return false;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        }
        if (fromFile(out) == null) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        }
        removeStored();
        Prefs.setCustomFont(out.getPath(), displayName(c, uri));
        return true;
    }

    /** Kembali ke font bawaan dan hapus file font kustom. */
    static void reset(Context c) {
        Prefs.init(c);
        removeStored();
        Prefs.setCustomFont("", "");
    }

    private static void removeStored() {
        String old = Prefs.customFont();
        if (!old.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(old).delete();
        }
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                String n = q.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        return p == null ? "Font kustom" : p;
    }
}
