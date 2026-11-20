package ru.iqwanoino.music;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** HTTP sederhana tanpa library. Panggil hanya dari thread latar. */
final class Net {

    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Mobile Safari/537.36";

    private Net() { }

    /** Ada koneksi internet aktif? (menggantikan SketchwareUtil.isConnected) */
    @SuppressWarnings("deprecation")
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String get(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, timeoutMs);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return new String(read(c.getInputStream(), 4 * 1024 * 1024), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static byte[] getBytes(String url, int timeoutMs, int maxBytes) throws IOException {
        HttpURLConnection c = open(url, timeoutMs);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            return read(c.getInputStream(), maxBytes);
        } finally {
            c.disconnect();
        }
    }

    static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        // URL stream YouTube dari klien VISIONOS hanya mau diunduh dengan UA klien yang sama
        String ua = YtInnerTube.playbackUa(url);
        c.setRequestProperty("User-Agent", ua != null ? ua : UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        return c;
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("Respons terlalu besar");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }
}
