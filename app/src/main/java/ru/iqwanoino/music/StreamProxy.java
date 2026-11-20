package ru.iqwanoino.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Proxy HTTP lokal (127.0.0.1) pengganti cache ExoPlayer tanpa AndroidX. MediaPlayer memutar dari
 * proxy ini; proxy mengunduh stream asli SATU kali ke file cache sambil melayani pemutar dari file
 * yang sedang tumbuh. Hasilnya: tidak ada unduhan ganda, dan lagu yang sudah selesai langsung
 * tersimpan di cache. Seek jauh ke depan dilayani langsung dari server asli lewat header Range.
 */
final class StreamProxy {

    private static final long NEAR_SEEK = 1024 * 1024;       // seek sedekat ini cukup ditunggu
    private static final long STALL_MS = 30000;
    private static final long IDLE_CANCEL_MS = 15000;

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static ServerSocket server;
    private static int port;

    private StreamProxy() { }

    /** Kembalikan URL lokal yang bisa dimainkan MediaPlayer untuk stream asli upstream. */
    static synchronized String prepare(Library.Song s, String upstream) throws IOException {
        start();
        String token = Cache.key(s.source + ":" + s.ref);
        Entry e = ENTRIES.get(token);
        if (e != null && (!e.upstream.equals(upstream) || e.failed || e.cancelled)) {
            e.cancelled = true;
            e = null;
        }
        if (e == null) {
            e = new Entry(token, s, upstream);
            ENTRIES.put(token, e);
        }
        purge();
        return "http://127.0.0.1:" + port + "/s/" + token;
    }

    private static void purge() {
        Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
        while (it.hasNext() && ENTRIES.size() > 12) {
            Entry e = it.next().getValue();
            if (e.readers == 0 && (e.done || e.failed || e.cancelled)) it.remove();
        }
    }

    private static synchronized Entry get(String token) {
        return ENTRIES.get(token);
    }

    private static void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket c = ss.accept();
                    POOL.execute(() -> handle(c));
                } catch (IOException e) {
                    if (ss.isClosed()) return;
                }
            }
        }, "stream-proxy");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ state satu lagu

    private static final class Entry {
        final String token, upstream;
        final File dest, part;
        final Object lock = new Object();
        long total = -1, written;
        boolean started, headersReady, done, failed;
        volatile boolean cancelled;
        int readers;
        String type = "audio/mpeg";

        Entry(String token, Library.Song s, String upstream) {
            this.token = token;
            this.upstream = upstream;
            dest = Cache.audioFile(s);
            part = new File(dest.getPath() + ".part");
            if (dest.exists()) {                 // sudah lengkap di cache
                total = written = dest.length();
                started = headersReady = done = true;
            }
        }
    }

    // ------------------------------------------------------------ unduhan upstream -> cache

    private static void ensureStarted(final Entry e) {
        synchronized (e.lock) {
            if (e.started) return;
            e.started = true;
        }
        POOL.execute(() -> download(e));
    }

    private static void download(Entry e) {
        HttpURLConnection c = null;
        FileOutputStream out = null;
        boolean complete = false;
        try {
            c = Net.open(e.upstream, 20000);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long len = -1;
            try {
                len = Long.parseLong(c.getHeaderField("Content-Length"));
            } catch (Exception ignored) { }
            String ct = c.getContentType();
            InputStream in = c.getInputStream();
            out = new FileOutputStream(e.part);
            synchronized (e.lock) {
                e.total = len;
                if (ct != null && ct.startsWith("audio/")) e.type = ct;
                e.headersReady = true;
                e.lock.notifyAll();
            }
            byte[] buf = new byte[16384];
            int n;
            while (!e.cancelled && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                synchronized (e.lock) {
                    e.written += n;
                    e.lock.notifyAll();
                }
            }
            out.close();
            out = null;
            synchronized (e.lock) {
                complete = !e.cancelled && (e.total < 0 || e.written == e.total);
            }
            if (complete) {
                if (e.part.renameTo(e.dest)) Cache.trim(e.dest.getParentFile(), Cache.AUDIO_MAX);
                else complete = false;
            }
        } catch (Exception ex) {
            complete = false;
        } finally {
            try {
                if (out != null) out.close();
            } catch (IOException ignored) { }
            if (c != null) c.disconnect();
            if (!complete) e.part.delete();
            synchronized (e.lock) {
                e.done = complete;
                e.failed = !complete;
                e.headersReady = true;
                e.lock.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------ melayani pemutar

    private static void handle(Socket c) {
        Entry e = null;
        try {
            c.setSoTimeout(30000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "ISO-8859-1"));
            String req = r.readLine();
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            e = get(path.substring(path.lastIndexOf('/') + 1));

            long start = 0, end = -1;
            boolean hasRange = false;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "range:", 0, 6)) {
                    String v = line.substring(6).trim();
                    if (v.startsWith("bytes=")) {
                        String[] se = v.substring(6).split("-", -1);
                        try {
                            if (!se[0].isEmpty()) {
                                start = Long.parseLong(se[0].trim());
                                if (se.length > 1 && !se[1].trim().isEmpty()) end = Long.parseLong(se[1].trim());
                                hasRange = true;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            OutputStream os = c.getOutputStream();
            if (e == null) {
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                return;
            }
            synchronized (e.lock) {
                e.readers++;
            }
            serve(e, os, start, end, hasRange);
            os.flush();
        } catch (Exception ignored) {
            // pemutar menutup koneksi saat seek atau pindah lagu: wajar
        } finally {
            try {
                c.close();
            } catch (IOException ignored) { }
            if (e != null) leave(e);
        }
    }

    /** Pemutar terakhir pergi: bila unduhan belum selesai dan tidak ada yang kembali, hentikan. */
    private static void leave(final Entry e) {
        boolean idle;
        synchronized (e.lock) {
            e.readers = Math.max(0, e.readers - 1);
            idle = e.readers == 0 && !e.done;
        }
        if (!idle) return;
        POOL.execute(() -> {
            try {
                Thread.sleep(IDLE_CANCEL_MS);
            } catch (InterruptedException ignored) { }
            synchronized (e.lock) {
                if (e.readers == 0 && !e.done) {
                    e.cancelled = true;
                    e.lock.notifyAll();
                }
            }
        });
    }

    private static void serve(Entry e, OutputStream os, long start, long end, boolean hasRange) throws Exception {
        ensureStarted(e);
        long total;
        String type;
        synchronized (e.lock) {
            long deadline = System.currentTimeMillis() + 25000;
            while (!e.headersReady) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                e.lock.wait(left);
            }
            if (!e.headersReady || (e.failed && e.written == 0)) {
                os.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
                return;
            }
            total = e.total;
            type = e.type;
        }

        // Seek jauh melewati bagian yang sudah diunduh: layani langsung dari server asli
        boolean far;
        synchronized (e.lock) {
            far = hasRange && start > e.written + NEAR_SEEK && !e.done;
        }
        if (far && passthrough(e, os, start, end, type)) return;

        if (hasRange && total >= 0 && start >= total) {
            os.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
            return;
        }
        long last = total >= 0 ? (end >= 0 ? Math.min(end, total - 1) : total - 1) : Long.MAX_VALUE;
        if (total < 0 && start > 0) start = 0;          // panjang tidak diketahui: tidak bisa seek

        StringBuilder h = new StringBuilder();
        if (hasRange && total >= 0) {
            h.append("HTTP/1.1 206 Partial Content\r\n")
                    .append("Content-Range: bytes ").append(start).append('-').append(last).append('/').append(total).append("\r\n")
                    .append("Content-Length: ").append(last - start + 1).append("\r\n");
        } else {
            h.append("HTTP/1.1 200 OK\r\n");
            if (total >= 0) h.append("Content-Length: ").append(total).append("\r\n");
        }
        h.append("Content-Type: ").append(type).append("\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n");
        os.write(h.toString().getBytes("ISO-8859-1"));

        RandomAccessFile raf = open(e);
        try {
            byte[] buf = new byte[16384];
            long pos = start;
            while (pos <= last) {
                long avail;
                synchronized (e.lock) {
                    long until = System.currentTimeMillis() + STALL_MS;
                    while (e.written <= pos && !e.done && !e.failed && !e.cancelled) {
                        long left = until - System.currentTimeMillis();
                        if (left <= 0) return;
                        e.lock.wait(Math.min(left, 1000));
                    }
                    avail = e.written - pos;
                    if (avail <= 0) break;                 // selesai atau gagal
                }
                int want = (int) Math.min(buf.length, Math.min(avail, last - pos + 1));
                raf.seek(pos);
                int n = raf.read(buf, 0, want);
                if (n <= 0) break;
                os.write(buf, 0, n);
                pos += n;
            }
        } finally {
            raf.close();
        }
    }

    /** Buka file cache: yang lengkap dulu, lalu yang sedang diunduh (.part). */
    private static RandomAccessFile open(Entry e) throws IOException {
        for (int i = 0; i < 3; i++) {
            try {
                return new RandomAccessFile(e.dest.exists() ? e.dest : e.part, "r");
            } catch (FileNotFoundException ignored) { }      // berganti nama tepat di antara dua cek
        }
        throw new FileNotFoundException(e.dest.getPath());
    }

    /** Teruskan permintaan Range langsung ke server asli, tanpa menulis ke cache. */
    private static boolean passthrough(Entry e, OutputStream os, long start, long end, String type) throws Exception {
        HttpURLConnection c = Net.open(e.upstream, 20000);
        try {
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Range", "bytes=" + start + "-" + (end >= 0 ? String.valueOf(end) : ""));
            if (c.getResponseCode() != 206) return false;
            String range = c.getHeaderField("Content-Range");
            String len = c.getHeaderField("Content-Length");
            StringBuilder h = new StringBuilder("HTTP/1.1 206 Partial Content\r\n");
            if (range != null) h.append("Content-Range: ").append(range).append("\r\n");
            if (len != null) h.append("Content-Length: ").append(len).append("\r\n");
            h.append("Content-Type: ").append(type).append("\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            os.write(h.toString().getBytes("ISO-8859-1"));
            InputStream in = c.getInputStream();
            try {
                Cache.copy(in, os);
            } finally {
                in.close();
            }
            return true;
        } finally {
            c.disconnect();
        }
    }
}
