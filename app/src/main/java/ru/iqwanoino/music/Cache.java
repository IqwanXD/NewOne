package ru.iqwanoino.music;

import android.content.Context;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache di cacheDir (boleh dibersihkan sistem):
 *  - hasil pencarian online, berlaku SEARCH_TTL
 *  - audio lagu online yang pernah diputar, dibatasi AUDIO_MAX dengan hapus yang paling lama tidak dipakai
 *  - artwork online
 *  - URL stream yang sudah di-resolve, berlaku URL_TTL (URL sumber biasanya kedaluwarsa)
 */
final class Cache {

    static final long SEARCH_TTL = 30L * 60 * 1000;
    static final long URL_TTL = 15L * 60 * 1000;
    static final long AUDIO_MAX = 400L * 1024 * 1024;
    static final long ART_MAX = 60L * 1024 * 1024;

    private static File root;
    private static final Map<String, String> URLS = new HashMap<>();
    private static final Map<String, Long> URL_AT = new HashMap<>();

    private Cache() { }

    static synchronized void init(Context c) {
        if (root == null) root = c.getApplicationContext().getCacheDir();
    }

    private static File dir(String name) {
        File d = new File(root, name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Nama file aman dari teks apa pun (FNV-1a 64 bit dalam hex). */
    static String key(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }

    // ------------------------------------------------------------ hasil pencarian

    static List<Library.Song> searchGet(String q) {
        File f = new File(dir("search"), key(q) + ".json");
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > SEARCH_TTL) return null;
        try {
            JSONArray a = new JSONArray(new String(readAll(new FileInputStream(f)), "UTF-8"));
            List<Library.Song> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(OnlineStore.fromJson(a.getJSONObject(i)));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static void searchPut(String q, List<Library.Song> songs) {
        try {
            JSONArray a = new JSONArray();
            for (Library.Song s : songs) a.put(OnlineStore.toJson(s));
            writeAll(new File(dir("search"), key(q) + ".json"), a.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ audio

    static File audioFile(Library.Song s) {
        return new File(dir("audio"), s.source + "_" + key(s.ref) + ".audio");
    }

    static boolean hasAudio(Library.Song s) {
        return audioFile(s).exists();
    }

    static void touch(File f) {
        f.setLastModified(System.currentTimeMillis());
    }

    // ------------------------------------------------------------ artwork

    static byte[] artBytes(String url) throws IOException {
        File f = new File(dir("art"), key(url) + ".img");
        if (f.exists()) {
            touch(f);
            return readAll(new FileInputStream(f));
        }
        byte[] b = Net.getBytes(url, 10000, 4 * 1024 * 1024);
        try {
            OutputStream o = new FileOutputStream(f);
            o.write(b);
            o.close();
        } catch (IOException ignored) { }
        trim(dir("art"), ART_MAX);
        return b;
    }

    // ------------------------------------------------------------ URL stream

    static synchronized String urlGet(String k) {
        Long t = URL_AT.get(k);
        if (t == null || System.currentTimeMillis() - t > URL_TTL) return null;
        return URLS.get(k);
    }

    static synchronized void urlPut(String k, String url) {
        URLS.put(k, url);
        URL_AT.put(k, System.currentTimeMillis());
    }

    // ------------------------------------------------------------ util

    /** Hapus file paling lama tidak dipakai sampai total ukuran di bawah max. */
    static void trim(File d, long max) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long total = 0;
        for (File f : fs) total += f.length();
        for (File f : fs) {
            if (total <= max) break;
            if (f.getName().endsWith(".part")) continue;
            total -= f.length();
            f.delete();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            copy(in, o);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }
}
