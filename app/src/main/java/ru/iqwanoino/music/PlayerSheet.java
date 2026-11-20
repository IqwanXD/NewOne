package ru.iqwanoino.music;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Pemutar dalam bottom sheet: artwork (geser untuk pindah lagu), seekbar, dan kontrol. */
final class PlayerSheet {

    private final Activity act;
    private final MusicService svc;
    private final Runnable onClose;
    private final Sheet sheet;
    private final Handler h = new Handler(Looper.getMainLooper());

    private ImageView art, shuffleBtn, repeatBtn, playBtn;
    private TextView titleTv, artistTv, curTv, durTv;
    private SeekBar seek;
    private boolean dragging;
    private long lastArt = -1;
    private boolean lastPlaying;
    private int lastRepeat = -1, lastShuffle = -1;

    private final MusicService.Listener listener = this::refresh;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 250);
        }
    };

    private PlayerSheet(Activity a, MusicService s, Runnable close) {
        act = a;
        svc = s;
        onClose = close;
        sheet = new Sheet(a, true);
        build(sheet.panel);
        sheet.setOnDismiss(() -> {
            h.removeCallbacks(tick);
            svc.removeListener(listener);
            onClose.run();
        });
    }

    static Sheet show(Activity a, MusicService s, Runnable onClose) {
        PlayerSheet p = new PlayerSheet(a, s, onClose);
        s.addListener(p.listener);
        p.refresh();
        p.h.post(p.tick);
        p.sheet.show();
        return p.sheet;
    }

    private void build(LinearLayout col) {
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        // Artwork
        art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rect(Theme.SURFACE, 28));
        Ui.round(art, 28);
        art.setClickable(true);
        final GestureDetector gd = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Ui.dp(60) && Math.abs(dx) > Math.abs(dy) && Math.abs(vx) > 500) {
                    if (dx < 0) svc.next();
                    else svc.prev();
                    return true;
                }
                return false;
            }
        });
        art.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        col.addView(art, Ui.lp(Ui.dp(280), Ui.dp(280), 0, Ui.dp(8), 0, Ui.dp(24)));

        // Judul & artis
        titleTv = Ui.text(act, "", 22, Theme.ON, true);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(titleTv, Ui.lp(-1, -2));

        artistTv = Ui.text(act, "", 15, Theme.ON_VAR, false);
        artistTv.setGravity(Gravity.CENTER);
        artistTv.setSingleLine(true);
        artistTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(artistTv, Ui.lp(-1, -2, 0, Ui.dp(4), 0, Ui.dp(20)));

        // Seekbar interaktif
        seek = new SeekBar(act);
        seek.setProgressTintList(ColorStateList.valueOf(Theme.PRIMARY));
        seek.setThumbTintList(ColorStateList.valueOf(Theme.PRIMARY));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf((0x55 << 24) | (Theme.OUTLINE & 0x00FFFFFF)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) curTv.setText(Ui.time(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                svc.seekTo(sb.getProgress());
            }
        });
        col.addView(seek, Ui.lp(-1, -2));

        LinearLayout times = new LinearLayout(act);
        curTv = Ui.text(act, "0:00", 13, Theme.ON_VAR, false);
        durTv = Ui.text(act, "0:00", 13, Theme.ON_VAR, false);
        durTv.setGravity(Gravity.END);
        times.addView(curTv, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(durTv, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(times, Ui.lp(-1, -2, Ui.dp(4), 0, Ui.dp(4), Ui.dp(16)));

        // Kontrol
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        shuffleBtn = Ui.iconButton(act, Icons.SHUFFLE, Theme.ON_VAR, 48, () -> {
            svc.toggleShuffle();
            refresh();
        });
        ImageView prevBtn = Ui.iconButton(act, Icons.PREV, Theme.ON, 60, svc::prev);

        playBtn = new ImageView(act);
        playBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playBtn.setPadding(Ui.dp(22), Ui.dp(22), Ui.dp(22), Ui.dp(22));
        playBtn.setBackground(Ui.ripple(Theme.PRIMARY, 40));
        playBtn.setClickable(true);
        playBtn.setImageDrawable(new Icons(Icons.PLAY, Theme.ON_PRIMARY));
        playBtn.setOnClickListener(v -> {
            svc.togglePlay();
            refresh();
        });

        ImageView nextBtn = Ui.iconButton(act, Icons.NEXT, Theme.ON, 60, svc::next);
        repeatBtn = Ui.iconButton(act, Icons.REPEAT, Theme.ON_VAR, 48, () -> {
            svc.cycleRepeat();
            refresh();
        });

        row.addView(shuffleBtn, new LinearLayout.LayoutParams(0, Ui.dp(56), 1f));
        row.addView(prevBtn, new LinearLayout.LayoutParams(0, Ui.dp(64), 1f));
        row.addView(playBtn, new LinearLayout.LayoutParams(Ui.dp(80), Ui.dp(80)));
        row.addView(nextBtn, new LinearLayout.LayoutParams(0, Ui.dp(64), 1f));
        row.addView(repeatBtn, new LinearLayout.LayoutParams(0, Ui.dp(56), 1f));
        col.addView(row, Ui.lp(-1, -2));
    }

    /** Sinkronkan seluruh UI dengan keadaan service. */
    private void refresh() {
        Library.Song s = svc.current();
        if (s == null) return;

        if (s.id != lastArt) {
            lastArt = s.id;
            titleTv.setText(s.title);
            artistTv.setText(s.artist);
            Art.bind(art, s, new Icons(Icons.NOTE, Theme.ON_VAR), Ui.dp(90));
        }

        boolean playing = svc.isPlaying();
        if (playing != lastPlaying) {
            lastPlaying = playing;
            playBtn.setImageDrawable(new Icons(playing ? Icons.PAUSE : Icons.PLAY, Theme.ON_PRIMARY));
        }

        int rep = svc.getRepeat();
        if (rep != lastRepeat) {
            lastRepeat = rep;
            repeatBtn.setImageDrawable(new Icons(
                    rep == MusicService.REPEAT_ONE ? Icons.REPEAT_ONE : Icons.REPEAT,
                    rep == MusicService.REPEAT_OFF ? Theme.ON_VAR : Theme.PRIMARY));
        }

        int sh = svc.isShuffle() ? 1 : 0;
        if (sh != lastShuffle) {
            lastShuffle = sh;
            shuffleBtn.setImageDrawable(new Icons(Icons.SHUFFLE, sh == 1 ? Theme.PRIMARY : Theme.ON_VAR));
        }

        int dur = svc.duration();
        int pos = svc.position();
        if (dur > 0 && seek.getMax() != dur) seek.setMax(dur);
        durTv.setText(Ui.time(dur));
        if (!dragging) {
            seek.setProgress(pos);
            curTv.setText(Ui.time(pos));
        }
    }
}
