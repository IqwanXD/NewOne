package ru.iqwanoino.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pemutar: MediaPlayer + MediaSession + notifikasi MediaStyle.
 * Berjalan sebagai foreground service saat musik diputar.
 */
public class MusicService extends Service {

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    static final String ACTION_PLAY_PAUSE = "ru.iqwanoino.music.action.PLAY_PAUSE";
    static final String ACTION_NEXT = "ru.iqwanoino.music.action.NEXT";
    static final String ACTION_PREV = "ru.iqwanoino.music.action.PREV";
    static final String ACTION_SHUFFLE = "ru.iqwanoino.music.action.SHUFFLE";
    static final String ACTION_REPEAT = "ru.iqwanoino.music.action.REPEAT";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    interface Listener {
        void onChanged();
    }

    class LocalBinder extends Binder {
        MusicService get() {
            return MusicService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final List<Library.Song> queue = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Random rnd = new Random();

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private int index = -1;
    private int repeat = REPEAT_OFF;
    private boolean shuffle;
    private boolean prepared, loading, wantPlay, resumeAfterTransient, foreground, buffering;
    private int loadToken;
    private int cachedNext = -1;
    private String prefetchingId;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeAfterTransient = false;
                pauseInternal();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPlaying()) {
                    resumeAfterTransient = true;
                    pauseInternal();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null) mp.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mp != null) mp.setVolume(1f, 1f);
                if (resumeAfterTransient) {
                    resumeAfterTransient = false;
                    play();
                }
                break;
            default:
                break;
        }
    };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            pause();
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        Online.init(this);

        session = new MediaSession(this, "MusicService");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override public void onStop() { pause(); seekTo(0); }
            // Android 13+: kontrol media di panel notifikasi dibangun dari PlaybackState, bukan dari
            // tombol notifikasi, jadi acak dan ulangi harus didaftarkan sebagai custom action.
            @Override public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_SHUFFLE.equals(action)) toggleShuffle();
                else if (ACTION_REPEAT.equals(action)) cycleRepeat();
            }
        });
        session.setActive(true);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Pemutaran musik",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }

        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE: togglePlay(); break;
                case ACTION_NEXT: next(); break;
                case ACTION_PREV: prev(); break;
                case ACTION_SHUFFLE: toggleShuffle(); break;
                case ACTION_REPEAT: cycleRepeat(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        loadToken++;
        releasePlayer();
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        am.abandonAudioFocus(focusListener);
        session.setActive(false);
        session.release();
        getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        super.onDestroy();
    }

    // ------------------------------------------------------------ API untuk UI

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** Mengganti antrean dengan daftar ini dan mulai memutar posisi tertentu. */
    void playQueue(List<Library.Song> songs, int position) {
        if (songs.isEmpty() || position < 0 || position >= songs.size()) return;
        queue.clear();
        queue.addAll(songs);
        index = position;
        cachedNext = -1;
        wantPlay = true;
        load();
    }

    Library.Song current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    int position() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    int duration() {
        try {
            if (mp != null && prepared) {
                int d = mp.getDuration();
                if (d > 0) return d;
            }
        } catch (IllegalStateException ignored) { }
        Library.Song s = current();
        return s == null ? 0 : s.durationMs;
    }

    void play() {
        wantPlay = true;
        if (mp == null) {
            if (loading) onChanged();      // lagu online masih di-resolve; cukup tandai ingin memutar
            else load();
            return;
        }
        startPlayer();
    }

    void pause() {
        wantPlay = false;
        resumeAfterTransient = false;
        pauseInternal();
    }

    void togglePlay() {
        if (isPlaying()) pause();
        else play();
    }

    void next() {
        int n = nextIndex();
        if (n < 0) {
            pause();
            seekTo(0);
            return;
        }
        index = n;
        wantPlay = true;
        load();
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        index = index - 1 < 0 ? queue.size() - 1 : index - 1;
        wantPlay = true;
        load();
    }

    void seekTo(int ms) {
        if (mp == null || !prepared) return;
        try {
            mp.seekTo(Math.max(0, Math.min(ms, duration())));
        } catch (IllegalStateException ignored) { }
        onChanged();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        onChanged();
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        cachedNext = -1;
        onChanged();
    }

    int getRepeat() { return repeat; }

    /** Sedang menyiapkan lagu (resolve URL online, prepare, atau buffering). */
    boolean isLoading() { return loading || buffering; }

    /** Lagu yang akan diputar bila next() dipanggil (dipakai pratinjau geser di pemutar). */
    Library.Song peekNext() {
        int n = peekNextIndex();
        return n >= 0 && n < queue.size() ? queue.get(n) : null;
    }

    /** Lagu yang akan diputar bila prev() dipanggil. */
    Library.Song peekPrev() {
        if (queue.isEmpty()) return null;
        return queue.get(index - 1 < 0 ? queue.size() - 1 : index - 1);
    }

    boolean isShuffle() { return shuffle; }

    // ------------------------------------------------------------ internal

    private void load() {
        final Library.Song s = current();
        if (s == null) return;
        History.add(this, s.id);
        Stats.touch(this);
        releasePlayer();
        final int token = ++loadToken;
        loading = true;
        prepared = false;
        buffering = false;
        prefetchNext();

        if (!s.isOnline()) {
            prepare(s.uri(), false, token);
            onChanged();
            return;
        }

        OnlineStore.put(this, s);
        // Sudah pernah diputar: langsung dari cache, tanpa jaringan.
        if (Cache.hasAudio(s)) {
            File f = Cache.audioFile(s);
            Cache.touch(f);
            prepare(android.net.Uri.fromFile(f), false, token);
            onChanged();
            return;
        }
        onChanged();
        final String memo = String.valueOf(s.id);
        new Thread(() -> {
            String url = Cache.urlGet(memo), err = null;
            try {
                if (url == null) {
                    if (!Net.online(this)) throw new java.io.IOException("Tidak ada koneksi internet");
                    url = Online.resolve(s);
                    Cache.urlPut(memo, url);
                }
            } catch (Exception e) {
                url = null;
                err = e.getMessage() == null ? "Gagal memuat lagu online" : e.getMessage();
            }
            final String fu = url, fe = err;
            main.post(() -> {
                if (token != loadToken) return;      // pengguna sudah pindah lagu
                if (fu == null) fail(fe);
                else {
                    // Lewat proxy lokal: satu unduhan untuk diputar sekaligus disimpan ke cache
                    String play;
                    try {
                        play = StreamProxy.prepare(s, fu);
                    } catch (java.io.IOException ex) {
                        play = fu;
                    }
                    prepare(android.net.Uri.parse(play), true, token);
                    onChanged();
                }
            });
        }, "resolve").start();
    }

    /** Resolve URL lagu berikutnya di latar belakang, supaya pindah lagu tidak menunggu jaringan. */
    private void prefetchNext() {
        int n = peekNextIndex();
        if (n < 0 || n >= queue.size()) return;
        final Library.Song next = queue.get(n);
        if (!next.isOnline() || Cache.hasAudio(next)) return;
        final String memo = String.valueOf(next.id);
        if (memo.equals(prefetchingId) || Cache.urlGet(memo) != null) return;
        prefetchingId = memo;
        new Thread(() -> {
            try {
                if (Net.online(this)) Cache.urlPut(memo, Online.resolve(next));
            } catch (Exception ignored) {
                // gagal prefetch: saat lagu itu diputar, resolve diulang seperti biasa
            }
        }, "prefetch").start();
    }

    private void fail(String msg) {
        loading = false;
        buffering = false;
        prepared = false;
        wantPlay = false;
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        onChanged();
    }

    private void prepare(android.net.Uri uri, final boolean online, final int token) {
        MediaPlayer p = new MediaPlayer();
        mp = p;
        Eq.attach(this, p.getAudioSessionId());
        p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        p.setOnPreparedListener(player -> {
            if (player != mp) return;
            loading = false;
            buffering = false;
            prepared = true;
            if (wantPlay) startPlayer();
            else onChanged();
        });
        p.setOnCompletionListener(player -> {
            if (player == mp) onSongEnd();
        });
        p.setOnInfoListener((player, what, extra) -> {
            if (player != mp) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false;
            else return false;
            onChanged();
            return true;
        });
        p.setOnErrorListener((player, what, extra) -> {
            if (player == mp) {
                if (online) fail("Gagal memutar lagu online");
                else {
                    loading = false;
                    prepared = false;
                    wantPlay = false;
                    onChanged();
                }
            }
            return true;
        });
        try {
            if (online) {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Net.UA);
                p.setDataSource(this, uri, h);
            } else {
                p.setDataSource(this, uri);
            }
            p.prepareAsync();
        } catch (Exception e) {
            if (online) fail("Gagal memutar lagu online");
            else {
                loading = false;
                wantPlay = false;
            }
        }
    }

    private void startPlayer() {
        if (mp == null || !prepared) return;
        if (!requestFocus()) {
            wantPlay = false;
            onChanged();
            return;
        }
        if (!mp.isPlaying()) mp.start();
        onChanged();
    }

    private void pauseInternal() {
        try {
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
        } catch (IllegalStateException ignored) { }
        am.abandonAudioFocus(focusListener);
        onChanged();
    }

    private boolean requestFocus() {
        return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** Indeks lagu berikutnya tanpa mengubah apa pun (mode acak: dipilih sekali lalu disimpan). */
    private int peekNextIndex() {
        int size = queue.size();
        if (size == 0) return -1;
        if (shuffle && size > 1) {
            if (cachedNext < 0 || cachedNext >= size || cachedNext == index) {
                do {
                    cachedNext = rnd.nextInt(size);
                } while (cachedNext == index);
            }
            return cachedNext;
        }
        int n = index + 1;
        if (n >= size) return repeat == REPEAT_ALL ? 0 : -1;
        return n;
    }

    private int nextIndex() {
        int n = peekNextIndex();
        cachedNext = -1;
        return n;
    }

    private void onSongEnd() {
        if (repeat == REPEAT_ONE) {
            try {
                mp.seekTo(0);
                mp.start();
            } catch (IllegalStateException ignored) { }
            onChanged();
            return;
        }
        int n = nextIndex();
        if (n < 0) {
            wantPlay = false;
            am.abandonAudioFocus(focusListener);
            seekTo(0);
            onChanged();
            return;
        }
        index = n;
        wantPlay = true;
        load();
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        prepared = false;
        loading = false;
        buffering = false;
    }

    /** Satu titik untuk semua perubahan: sesi, notifikasi, dan UI. */
    private void onChanged() {
        Library.Song s = current();
        if (s != null) {
            Art.load(this, s, (id, bmp) -> {
                Library.Song c = current();
                if (c != null && c.id == id) {
                    updateSession();
                    updateNotification();
                }
            });
        }
        updateSession();
        updateNotification();
        for (Listener l : listeners) l.onChanged();
    }

    private void updateSession() {
        Library.Song s = current();
        if (s == null) return;
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        Bitmap art = Art.get(s.id);
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());

        int state;
        if (isPlaying()) state = PlaybackState.STATE_PLAYING;
        else if (loading || buffering) state = PlaybackState.STATE_BUFFERING;
        else if (prepared) state = PlaybackState.STATE_PAUSED;
        else state = PlaybackState.STATE_STOPPED;

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        int rep = repeat;
        PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position(), isPlaying() ? 1f : 0f);
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_SHUFFLE,
                shuffle ? "Acak: aktif" : "Acak: mati",
                shuffle ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off).build());
        pb.addCustomAction(new PlaybackState.CustomAction.Builder(ACTION_REPEAT,
                rep == REPEAT_OFF ? "Ulangi: mati" : rep == REPEAT_ALL ? "Ulangi: semua" : "Ulangi: satu",
                rep == REPEAT_ONE ? R.drawable.ic_repeat_one
                        : rep == REPEAT_ALL ? R.drawable.ic_repeat : R.drawable.ic_repeat_off).build());
        session.setPlaybackState(pb.build());
    }

    private void updateNotification() {
        Library.Song s = current();
        if (s == null) return;
        boolean playing = isPlaying();
        Notification n = buildNotification(s, playing);

        // Begitu sudah foreground, JANGAN turun lagi selama masih ada lagu.
        // Versi lama turun saat pause/loading lalu memanggil startForeground lagi
        // dari callback async (onPrepared), di luar aksi pengguna. Di Android 12+
        // itu melempar ForegroundServiceStartNotAllowedException dan service mati.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
        } catch (RuntimeException e) {
            // Sistem menolak foreground saat ini: tetap tampilkan sebagai notifikasi biasa.
            getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(Library.Song s, boolean playing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        int rep = repeat;
        b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle(s.title)
                .setContentText(s.artist)
                .setContentIntent(openAppIntent())
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                // Urutan: acak, sebelumnya, putar/jeda, berikutnya, ulangi
                .addAction(new Notification.Action(shuffle ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off,
                        shuffle ? "Acak: aktif" : "Acak: mati", actionIntent(ACTION_SHUFFLE, 4)))
                .addAction(new Notification.Action(R.drawable.ic_skip_previous,
                        "Sebelumnya", actionIntent(ACTION_PREV, 2)))
                .addAction(new Notification.Action(
                        playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Jeda" : "Putar", actionIntent(ACTION_PLAY_PAUSE, 1)))
                .addAction(new Notification.Action(R.drawable.ic_skip_next,
                        "Berikutnya", actionIntent(ACTION_NEXT, 3)))
                .addAction(new Notification.Action(
                        rep == REPEAT_ONE ? R.drawable.ic_repeat_one
                                : rep == REPEAT_ALL ? R.drawable.ic_repeat : R.drawable.ic_repeat_off,
                        rep == REPEAT_OFF ? "Ulangi: mati"
                                : rep == REPEAT_ALL ? "Ulangi: semua" : "Ulangi: satu",
                        actionIntent(ACTION_REPEAT, 5)))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));

        Bitmap art = Art.get(s.id);
        if (art != null) b.setLargeIcon(art);
        return b.build();
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, flags);
    }

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 0, i, flags);
    }
}
