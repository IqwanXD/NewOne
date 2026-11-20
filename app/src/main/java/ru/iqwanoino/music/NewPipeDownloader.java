package ru.iqwanoino.music;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HTTP untuk NewPipeExtractor. Memakai HttpURLConnection, tanpa OkHttp dan tanpa AndroidX. */
final class NewPipeDownloader extends Downloader {

    /**
     * User-Agent desktop Firefox ESR, sama dengan yang dipakai test resmi NewPipeExtractor.
     * Penting: YouTube memberi halaman web versi ponsel bila UA-nya Chrome Android, dan ekstraktor
     * mengurai halaman versi desktop (versi klien, base.js, parameter n). Klien seperti visionOS
     * mengirim UA-nya sendiri lewat header request, yang menimpa nilai ini.
     */
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(request.httpMethod());
        c.setRequestProperty("User-Agent", UA);

        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                if (h.getKey() == null || h.getValue() == null) continue;
                // Accept-Encoding dibiarkan ke HttpURLConnection agar gzip diurus sendiri
                if ("Accept-Encoding".equalsIgnoreCase(h.getKey())) continue;
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) c.setRequestProperty(h.getKey(), v);
                    else c.addRequestProperty(h.getKey(), v);
                    first = false;
                }
            }
        }

        byte[] data = request.dataToSend();
        if (data != null) {
            c.setDoOutput(true);
            OutputStream o = c.getOutputStream();
            try {
                o.write(data);
            } finally {
                o.close();
            }
        }

        try {
            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", request.url());

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(Cache.readAll(in), "UTF-8");

            Map<String, List<String>> rh = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) rh.put(e.getKey(), e.getValue());
            }
            String msg = c.getResponseMessage();
            return new Response(code, msg == null ? "" : msg, rh, body, c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }
}
