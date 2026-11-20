package ru.iqwanoino.music;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;

/**
 * Simpan lagu online ke Download/Music, lengkap dengan artis dan thumbnail:
 *  - MP3: tag ID3v2 (judul, artis, dan gambar sampul) ditulis di depan file.
 *  - Format lain (m4a, ogg, webm): tag tidak ditulis; thumbnail disimpan sebagai <nama>.jpg di sebelahnya.
 * Android 10 ke atas lewat MediaStore (tanpa izin tulis); Android 9 ke bawah lewat path langsung.
 */
final class Downloader {

    private static final String SUB = "Music";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Downloader() { }

    static void save(Context c, final Library.Song s) {
        final Context app = c.getApplicationContext();
        Online.init(app);
        M3.toast(app, "Mengunduh " + s.title + "…");
        new Thread(() -> {
            String msg;
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                String mime;
                if (Cache.hasAudio(s)) {
                    in = new FileInputStream(Cache.audioFile(s));
                    mime = "audio/mpeg";
                } else {
                    String memo = String.valueOf(s.id);
                    String url = Cache.urlGet(memo);
                    if (url == null) {
                        if (!Net.online(app)) throw new Exception("tidak ada koneksi");
                        url = Online.resolve(s);
                        Cache.urlPut(memo, url);
                    }
                    conn = Net.open(url, 20000);
                    in = conn.getInputStream();
                    mime = mimeOf(conn.getContentType());
                }
                String ext = extOf(mime);
                String base = fileName(s);
                String name = base + ext;

                // Thumbnail (sampul). Gagal mengambilnya tidak menggagalkan unduhan.
                byte[] cover = null;
                if (!s.artUrl.isEmpty()) {
                    try {
                        cover = Cache.artBytes(s.artUrl);
                    } catch (Exception ignored) { }
                }

                byte[] tag = null;
                if (ext.equals(".mp3")) {
                    in = stripId3(in);                        // buang tag lama agar tidak dobel
                    tag = buildId3(s.title, s.artist, cover);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    saveMediaStore(app, name, mime, in, tag);
                    if (cover != null && !ext.equals(".mp3")) saveCoverMediaStore(app, base, cover);
                } else {
                    saveLegacy(app, name, in, tag);
                    if (cover != null && !ext.equals(".mp3")) saveCoverLegacy(app, base, cover);
                }
                msg = "Tersimpan di Download/" + SUB + ": " + name;
            } catch (Exception e) {
                msg = "Gagal mengunduh" + (e.getMessage() == null ? "" : ": " + e.getMessage());
            } finally {
                try {
                    if (in != null) in.close();
                } catch (Exception ignored) { }
                if (conn != null) conn.disconnect();
            }
            final String m = msg;
            MAIN.post(() -> M3.toast(app, m));
        }, "download").start();
    }

    // ------------------------------------------------------------ penyimpanan

    private static void saveMediaStore(Context c, String name, String mime, InputStream in, byte[] head)
            throws Exception {
        ContentResolver r = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + SUB);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("penyimpanan tidak tersedia");
        try {
            OutputStream out = r.openOutputStream(u);
            if (out == null) throw new Exception("tidak bisa menulis file");
            try {
                if (head != null) out.write(head);
                Cache.copy(in, out);
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(u, done, null, null);
        } catch (Exception e) {
            r.delete(u, null, null);
            throw e;
        }
    }

    private static void saveCoverMediaStore(Context c, String base, byte[] cover) throws Exception {
        ContentResolver r = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + ".jpg");
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB);
        Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) return;
        OutputStream out = r.openOutputStream(u);
        if (out == null) return;
        try {
            out.write(cover);
        } finally {
            out.close();
        }
    }

    private static void saveLegacy(Context c, String name, InputStream in, byte[] head) throws Exception {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB);
        if (!d.exists() && !d.mkdirs()) throw new Exception("folder tidak bisa dibuat");
        File f = unique(d, name);
        OutputStream out = new FileOutputStream(f);
        try {
            if (head != null) out.write(head);
            Cache.copy(in, out);
        } finally {
            out.close();
        }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
    }

    private static void saveCoverLegacy(Context c, String base, byte[] cover) throws Exception {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB);
        if (!d.exists() && !d.mkdirs()) return;
        File f = unique(d, base + ".jpg");
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(cover);
        } finally {
            out.close();
        }
        MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, null, null);
    }

    // ------------------------------------------------------------ ID3v2 (MP3)

    /** Lewati tag ID3v2 yang sudah ada di awal stream (kalau ada). Sisanya dikembalikan utuh. */
    static InputStream stripId3(InputStream in) throws java.io.IOException {
        PushbackInputStream p = new PushbackInputStream(in, 10);
        byte[] h = new byte[10];
        int n = readFully(p, h, 10);
        if (n == 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            long skip = synchsafe(h, 6) + ((h[5] & 0x10) != 0 ? 10 : 0);
            skipFully(p, skip);
            return p;
        }
        if (n > 0) p.unread(h, 0, n);
        return p;
    }

    /** Tag ID3v2.3: TIT2 (judul), TPE1 (artis), APIC (sampul JPEG). Teks memakai UTF-16 (didukung v2.3). */
    static byte[] buildId3(String title, String artist, byte[] cover) throws java.io.IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (title != null && !title.isEmpty()) frame(frames, "TIT2", textData(title));
        if (artist != null && !artist.isEmpty()) frame(frames, "TPE1", textData(artist));
        if (cover != null && cover.length > 0) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            d.write(0x00);                                     // ISO-8859-1
            d.write("image/jpeg".getBytes("ISO-8859-1"));
            d.write(0x00);
            d.write(0x03);                                     // sampul depan
            d.write(0x00);                                     // deskripsi kosong
            d.write(cover);
            frame(frames, "APIC", d.toByteArray());
        }
        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 3, 0, 0});
        writeSynchsafe(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] textData(String s) throws java.io.IOException {
        byte[] txt = s.getBytes("UTF-16");                     // dengan BOM
        byte[] d = new byte[txt.length + 1];
        d[0] = 0x01;                                           // UTF-16 dengan BOM
        System.arraycopy(txt, 0, d, 1, txt.length);
        return d;
    }

    private static void frame(ByteArrayOutputStream out, String id, byte[] data) throws java.io.IOException {
        out.write(id.getBytes("ISO-8859-1"));
        int n = data.length;
        out.write((n >>> 24) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>> 8) & 0xFF);
        out.write(n & 0xFF);
        out.write(0);
        out.write(0);
        out.write(data);
    }

    private static void writeSynchsafe(OutputStream o, int n) throws java.io.IOException {
        o.write((n >>> 21) & 0x7F);
        o.write((n >>> 14) & 0x7F);
        o.write((n >>> 7) & 0x7F);
        o.write(n & 0x7F);
    }

    private static long synchsafe(byte[] b, int off) {
        return ((long) (b[off] & 0x7F) << 21) | ((long) (b[off + 1] & 0x7F) << 14)
                | ((long) (b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int readFully(InputStream in, byte[] b, int len) throws java.io.IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, total, len - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws java.io.IOException {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            left -= r;
        }
    }

    // ------------------------------------------------------------ nama & tipe

    private static File unique(File d, String name) {
        File f = new File(d, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) f = new File(d, base + " (" + i + ")" + ext);
        return f;
    }

    /** Nama file: "Artis - Judul". Karakter yang tidak aman untuk nama file diganti. */
    static String fileName(Library.Song s) {
        String artist = s.artist == null || s.artist.isEmpty() ? "" : s.artist + " - ";
        String base = (artist + s.title).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (base.length() > 120) base = base.substring(0, 120).trim();
        return base.isEmpty() ? "lagu" : base;
    }

    private static String mimeOf(String ct) {
        if (ct == null) return "audio/mpeg";
        int semi = ct.indexOf(';');
        String m = (semi >= 0 ? ct.substring(0, semi) : ct).trim();
        return m.startsWith("audio/") ? m : "audio/mpeg";
    }

    private static String extOf(String mime) {
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) return ".m4a";
        if (mime.contains("ogg") || mime.contains("opus")) return ".ogg";
        if (mime.contains("webm")) return ".webm";
        return ".mp3";
    }
}
