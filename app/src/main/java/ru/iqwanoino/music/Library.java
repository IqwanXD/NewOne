package ru.iqwanoino.music;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Daftar lagu dari MediaStore. Query dijalankan di background. */
final class Library {

    interface Callback {
        void onLoaded(List<Song> songs);
    }

    static final int SRC_LOCAL = 0, SRC_SOUNDCLOUD = 1, SRC_AUDIUS = 2, SRC_YOUTUBE = 3, SRC_ARCHIVE = 4;

    static final class Song {
        final long id;
        final String title, artist, album, folder;
        final int durationMs;
        final long dateAdded;
        /** SRC_LOCAL untuk file di perangkat, selain itu lagu online. */
        final int source;
        /** ID lagu di sumber online (kosong untuk lagu lokal). */
        final String ref;
        /** URL artwork online (kosong untuk lagu lokal). */
        final String artUrl;

        Song(long id, String title, String artist, String album, int durationMs, String folder, long dateAdded) {
            this(id, title, artist, album, durationMs, folder, dateAdded, SRC_LOCAL, "", "");
        }

        Song(long id, String title, String artist, String album, int durationMs, String folder, long dateAdded,
             int source, String ref, String artUrl) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.folder = folder;
            this.dateAdded = dateAdded;
            this.source = source;
            this.ref = ref == null ? "" : ref;
            this.artUrl = artUrl == null ? "" : artUrl;
        }

        /** Lagu online. ID negatif stabil dari hash sumber+ref, supaya tidak bentrok dengan ID MediaStore. */
        static Song online(int source, String ref, String title, String artist, int durationMs, String artUrl) {
            return new Song(onlineId(source, ref), orDefault(title, "Tanpa judul"),
                    orDefault(artist, "Artis tidak diketahui"), "", durationMs, "", 0L, source, ref, artUrl);
        }

        boolean isOnline() {
            return source != SRC_LOCAL;
        }

        Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }

        String sourceName() {
            switch (source) {
                case SRC_SOUNDCLOUD: return "SoundCloud";
                case SRC_AUDIUS: return "Audius";
                case SRC_YOUTUBE: return "YouTube";
                case SRC_ARCHIVE: return "Internet Archive";
                default: return "";
            }
        }
    }

    /** FNV-1a 64 bit, dipaksa negatif. */
    static long onlineId(int source, String ref) {
        String k = source + ":" + ref;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < k.length(); i++) {
            h ^= k.charAt(i);
            h *= 0x100000001b3L;
        }
        return -(h >>> 1) - 1;
    }

    /** Cari lagu dari ID: perpustakaan lokal dulu, lalu lagu online yang pernah disimpan (playlist/riwayat). */
    static Song find(Context c, long id) {
        for (Song s : songs) if (s.id == id) return s;
        return id < 0 ? OnlineStore.get(c, id) : null;
    }

    /** Diakses hanya dari main thread. */
    static final List<Song> songs = new ArrayList<>();
    static boolean loaded;

    /** Batas file "kecil" untuk opsi Jangan tampilkan file ukuran kecil. */
    static final long SMALL_BYTES = 500L * 1024;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Library() { }

    static void load(Context c, final Callback cb) {
        final Context app = c.getApplicationContext();
        final boolean hideSmall = Prefs.hideSmall();
        IO.execute(() -> {
            final List<Song> out = new ArrayList<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.SIZE
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE";
            try (Cursor cur = app.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, order)) {
                if (cur != null) {
                    int iId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int iT = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int iA = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int iAl = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int iD = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int iP = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int iDate = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    int iSize = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    while (cur.moveToNext()) {
                        if (hideSmall && cur.getLong(iSize) < SMALL_BYTES) continue;
                        String path = cur.getString(iP);
                        String folder = path == null ? "" : path.substring(0, Math.max(0, path.lastIndexOf('/')));
                        out.add(new Song(
                                cur.getLong(iId),
                                orDefault(cur.getString(iT), "Tanpa judul"),
                                orDefault(cur.getString(iA), "Artis tidak diketahui"),
                                orDefault(cur.getString(iAl), ""),
                                cur.getInt(iD),
                                folder,
                                cur.getLong(iDate)));
                    }
                }
            } catch (Exception ignored) { }
            MAIN.post(() -> {
                Library.songs.clear();
                Library.songs.addAll(out);
                loaded = true;
                if (cb != null) cb.onLoaded(Library.songs);
            });
        });
    }

    /** Nama tampilan folder dari path lengkap. */
    static String folderName(String folder) {
        if (folder == null || folder.isEmpty()) return "Lainnya";
        return folder.substring(folder.lastIndexOf('/') + 1);
    }

    static String orDefault(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s;
    }
}
