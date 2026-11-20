package ru.iqwanoino.music;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * YouTube lewat NewPipeExtractor (library yang sama dengan aplikasi NewPipe).
 * Pencarian mengambil semua video (bukan hanya lagu), resolve mengambil stream audio saja.
 * Video siaran langsung dilewati karena tidak bisa diputar sebagai audio biasa.
 */
final class NewPipeBackend {

    private static boolean inited;

    private NewPipeBackend() { }

    private static synchronized void ensure() {
        if (!inited) {
            NewPipe.init(new NewPipeDownloader());
            inited = true;
        }
    }

    static List<Library.Song> search(String q) throws Exception {
        ensure();
        SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube,
                ServiceList.YouTube.getSearchQHFactory().fromQuery(q, Collections.<String>emptyList(), ""));
        List<Library.Song> out = new ArrayList<>();
        for (InfoItem it : info.getRelatedItems()) {
            if (!(it instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) it;
            long dur = s.getDuration();
            if (dur <= 0) continue;                                   // siaran langsung
            String id = SrcYouTube.videoId(s.getUrl());
            if (id.isEmpty()) continue;
            String art = "";
            int bestH = -2;
            for (org.schabi.newpipe.extractor.Image im : s.getThumbnails()) {
                if (im.getHeight() > bestH) {
                    bestH = im.getHeight();
                    art = im.getUrl();
                }
            }
            String artist = s.getUploaderName() == null ? "" : s.getUploaderName();
            if (artist.endsWith(" - Topic")) artist = artist.substring(0, artist.length() - 8);
            out.add(Library.Song.online(Library.SRC_YOUTUBE, id, s.getName(), artist, (int) (dur * 1000), art));
        }
        return out;
    }

    /**
     * URL audio progresif (HTTP biasa). Manifest DASH/HLS tidak bisa dibaca MediaPlayer, jadi dilewati.
     * Ekstraktor menyimpan kegagalan parsing pemutar YouTube (base.js) selama proses hidup, jadi satu
     * kegagalan sesaat akan membuat semua lagu berikutnya gagal. Karena itu cache dibersihkan dan
     * dicoba sekali lagi sebelum menyerah.
     */
    static String resolve(String videoId) throws Exception {
        ensure();
        try {
            return pick(videoId);
        } catch (Exception first) {
            YoutubeJavaScriptPlayerManager.clearAllCaches();
            return pick(videoId);
        }
    }

    private static String pick(String videoId) throws Exception {
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
        String best = null;
        int bestBr = -1;
        boolean bestM4a = false;
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            // Lewati trek dubbing / deskripsi audio; pakai trek asli
            AudioTrackType tt = a.getAudioTrackType();
            if (tt != null && tt != AudioTrackType.ORIGINAL) continue;
            String url = a.getContent();
            if (url == null || url.isEmpty()) continue;
            boolean m4a = a.getFormat() == MediaFormat.M4A;
            int br = a.getAverageBitrate();
            if ((m4a && !bestM4a) || (m4a == bestM4a && br > bestBr)) {
                best = url;
                bestBr = br;
                bestM4a = m4a;
            }
        }
        if (best == null) throw new IOException("NewPipe: tidak ada stream audio progresif");
        return best;
    }
}
