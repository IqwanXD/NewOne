package ru.iqwanoino.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Pemutar dalam bentuk bottom sheet.
 *  progress 0 = mini player melayang di atas bottom nav (tinggi MINI_DP).
 *  progress 1 = pemutar penuh, naik dari bawah sampai menutup layar.
 * Sheet mengikuti jari secara realtime saat ditarik vertikal (di mana pun di area pemutar, mini
 * maupun penuh). Dilepas: fling menentukan arah, kalau tidak ada fling, ke posisi terdekat, dan
 * gerak lanjutannya memakai kecepatan jari (pegas teredam kritis, tanpa pantulan).
 * Mini: ketuk untuk buka; geser horizontal untuk ganti lagu.
 *
 * Kartu selalu setinggi layar dan hanya digeser (translationY) + dipotong lewat outline, jadi
 * tidak ada layout ulang di tiap frame saat menarik.
 */
final class PlayerPanel extends FrameLayout {

    static final int MINI_DP = 72;
    private static final float VEL = 500f;             // px/detik, ambang fling
    private static final int MINI_ART_DP = 48;
    private static final int MINI_SIDE_DP = 12;        // jarak mini dari tepi kiri/kanan layar
    private static final float OMEGA = 22f;            // rad/detik: kekakuan pegas penetap

    private final Activity act;
    private final int slop;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MusicService svc;
    private boolean shown;

    private final View scrim;
    private final FrameLayout card;
    private final GradientDrawable cardBg;
    private final FrameLayout miniView, fullView;
    private final View miniTrack, miniProgress;
    private final ImageView miniArt, miniPlay, art, play, shuffleBtn, repeatBtn;
    private final TextView miniTitle, miniArtist, title, artist, cur, dur;
    private final WaveSlider seek;
    private final FrameLayout.LayoutParams artLp, titleLp, seekLp, timesLp, controlsLp, handleLp;
    private final LinearLayout controls, titles, times;
    private final FrameLayout handleArea;
    private View navView;                  // bottom nav: turun dan hilang saat pemutar penuh

    private float progress;                // 0 mini, 1 penuh
    private int bottomInset;               // jarak mini dari tepi bawah (bottom nav + margin)
    private long lastId = -1;
    private int lastLoading = -1, lastPlaying = -1, lastRepeat = -1, lastShuffle = -1;
    private boolean seeking, dragging, axisV, downOnSeek, cancelSent, gestureActive;
    private float downRawX, downRawY, startP;
    private VelocityTracker vt;
    private ValueAnimator anim;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            h.postDelayed(this, 250);
        }
    };

    PlayerPanel(Activity a) {
        super(a);
        act = a;
        slop = ViewConfiguration.get(a).getScaledTouchSlop();
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int fullArt = Math.min(Ui.dp(340), screenW - Ui.dp(64));

        // Latar gelap di belakang sheet: menebal mengikuti progres, ketuk untuk menutup.
        scrim = new View(a);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0f);
        scrim.setVisibility(INVISIBLE);
        scrim.setOnClickListener(v -> animateTo(0f));
        addView(scrim, new LayoutParams(-1, -1));

        card = new FrameLayout(a) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                // Kartu setinggi layar (hanya digeser), jadi hanya bagian yang terlihat yang boleh
                // menerima sentuhan. Sisanya (bottom nav di bawah mini) diteruskan ke view di belakang.
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !hitVisible(e.getX(), e.getY())) return false;
                return super.dispatchTouchEvent(e);
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent e) {
                return intercept(e);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (touch(e)) {
                    // Gestur kita: batalkan state tekan/klik milik View supaya tidak ikut terbuka
                    if (e.getActionMasked() != MotionEvent.ACTION_DOWN && !cancelSent) {
                        cancelSent = true;
                        MotionEvent c = MotionEvent.obtain(e);
                        c.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(c);
                        c.recycle();
                    }
                    return true;
                }
                return super.onTouchEvent(e);          // klik (ketuk mini untuk membuka)
            }
        };
        cardBg = new GradientDrawable();
        cardBg.setColor(Theme.SURFACE_HIGH);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                // Bentuk yang terlihat: dari mini melayang (membulat, ada jarak tepi) ke layar penuh
                float k = 1f - progress;
                int side = Math.round(Ui.dp(MINI_SIDE_DP) * k);
                o.setRoundRect(side, 0, v.getWidth() - side, Math.round(visibleHeight()), Ui.dp(28) * k);
            }
        });
        card.setOnClickListener(v -> {
            if (progress < 0.5f) animateTo(1f);
        });
        addView(card, new LayoutParams(-1, -1));

        // ---- Mini: bar progres tipis di atas, art, judul, tombol putar
        miniView = new FrameLayout(a);
        miniTrack = new View(a);
        miniTrack.setBackgroundColor(M3.alpha(Theme.PRIMARY, 0.25f));
        miniView.addView(miniTrack, flp(-1, Ui.dp(2), Gravity.TOP, 0, 0, 0, 0));
        miniProgress = new View(a);
        miniProgress.setBackgroundColor(Theme.PRIMARY);
        miniProgress.setPivotX(0f);
        miniProgress.setScaleX(0f);
        miniView.addView(miniProgress, flp(-1, Ui.dp(2), Gravity.TOP, 0, 0, 0, 0));

        miniArt = new ImageView(a);
        miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(miniArt, 12);
        miniView.addView(miniArt, flp(Ui.dp(MINI_ART_DP), Ui.dp(MINI_ART_DP), Gravity.TOP | Gravity.LEFT,
                Ui.dp(12), Ui.dp(12), 0, 0));

        LinearLayout miniTexts = new LinearLayout(a);
        miniTexts.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.text(a, "", 15, Theme.ON, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = Ui.text(a, "", 13, Theme.ON_VAR, false);
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        miniTexts.addView(miniTitle, Ui.lp(-1, -2));
        miniTexts.addView(miniArtist, Ui.lp(-1, -2));
        miniView.addView(miniTexts, flp(-1, -2, Gravity.CENTER_VERTICAL, Ui.dp(72), 0, Ui.dp(68), 0));

        miniPlay = new ImageView(a);
        miniPlay.setScaleType(ImageView.ScaleType.CENTER);
        miniPlay.setBackground(Ui.ripple(Theme.PRIMARY, 22));
        miniPlay.setPadding(Ui.dp(11), Ui.dp(11), Ui.dp(11), Ui.dp(11));
        miniPlay.setOnClickListener(v -> {
            if (svc != null) svc.togglePlay();
        });
        miniView.addView(miniPlay, flp(Ui.dp(44), Ui.dp(44), Gravity.CENTER_VERTICAL | Gravity.RIGHT,
                0, 0, Ui.dp(14), 0));
        card.addView(miniView, flp(-1, Ui.dp(MINI_DP), Gravity.TOP, Ui.dp(MINI_SIDE_DP), 0, Ui.dp(MINI_SIDE_DP), 0));

        // ---- Penuh: handle, art besar, judul, seek bergelombang, waktu, kontrol
        fullView = new FrameLayout(a);
        handleArea = new FrameLayout(a);
        View handle = new View(a);
        handle.setBackground(Ui.rect(Theme.OUTLINE, 2));
        handleArea.addView(handle, flp(Ui.dp(36), Ui.dp(4), Gravity.CENTER, 0, 0, 0, 0));
        handleArea.setOnClickListener(v -> animateTo(0f));
        handleLp = flp(-1, Ui.dp(40), Gravity.TOP, 0, 0, 0, 0);
        fullView.addView(handleArea, handleLp);

        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(art, 24);
        artLp = flp(fullArt, fullArt, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0);
        fullView.addView(art, artLp);

        titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        title = Ui.text(a, "", 22, Theme.ON, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        artist = Ui.text(a, "", 15, Theme.ON_VAR, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setGravity(Gravity.CENTER);
        titles.addView(title, Ui.lp(-1, -2));
        titles.addView(artist, Ui.lp(-1, -2, 0, Ui.dp(4), 0, 0));
        titleLp = flp(fullArt, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0);
        fullView.addView(titles, titleLp);

        seek = new WaveSlider(a);
        seek.setTrackActiveColor(Theme.PRIMARY);
        seek.setTrackInactiveColor((0x55 << 24) | (Theme.PRIMARY & 0x00FFFFFF));
        seek.setThumbColor(Theme.PRIMARY);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) cur.setText(Ui.time(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (svc != null) svc.seekTo(sb.getProgress());
            }
        });
        seekLp = flp(-1, Ui.dp(44), Gravity.TOP | Gravity.LEFT, Ui.dp(24), 0, Ui.dp(24), 0);
        fullView.addView(seek, seekLp);

        times = new LinearLayout(a);
        cur = Ui.text(a, "0:00", 12, Theme.ON_VAR, false);
        dur = Ui.text(a, "0:00", 12, Theme.ON_VAR, false);
        dur.setGravity(Gravity.END);
        times.addView(cur, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(dur, new LinearLayout.LayoutParams(0, -2, 1f));
        timesLp = flp(-1, -2, Gravity.TOP | Gravity.LEFT, Ui.dp(32), 0, Ui.dp(32), 0);
        fullView.addView(times, timesLp);

        controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        shuffleBtn = Ui.iconButton(a, R.drawable.ic_shuffle, Theme.ON_VAR, 48, () -> {
            if (svc != null) {
                svc.toggleShuffle();
                refresh();
            }
        });
        ImageView prev = Ui.iconButton(a, R.drawable.ic_skip_previous, Theme.ON, 56, () -> {
            if (svc != null) svc.prev();
        });
        ImageView next = Ui.iconButton(a, R.drawable.ic_skip_next, Theme.ON, 56, () -> {
            if (svc != null) svc.next();
        });
        repeatBtn = Ui.iconButton(a, R.drawable.ic_repeat, Theme.ON_VAR, 48, () -> {
            if (svc != null) {
                svc.cycleRepeat();
                refresh();
            }
        });
        play = new ImageView(a);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setBackground(Ui.ripple(Theme.PRIMARY, 40));
        play.setPadding(Ui.dp(20), Ui.dp(20), Ui.dp(20), Ui.dp(20));
        play.setOnClickListener(v -> {
            if (svc != null) svc.togglePlay();
        });
        controls.addView(shuffleBtn, Ui.lp(Ui.dp(48), Ui.dp(48)));
        controls.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
        controls.addView(prev, Ui.lp(Ui.dp(56), Ui.dp(56)));
        controls.addView(new View(a), new LinearLayout.LayoutParams(Ui.dp(12), 0));
        controls.addView(play, Ui.lp(Ui.dp(80), Ui.dp(80)));
        controls.addView(new View(a), new LinearLayout.LayoutParams(Ui.dp(12), 0));
        controls.addView(next, Ui.lp(Ui.dp(56), Ui.dp(56)));
        controls.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
        controls.addView(repeatBtn, Ui.lp(Ui.dp(48), Ui.dp(48)));
        controlsLp = flp(-1, -2, Gravity.BOTTOM, Ui.dp(24), 0, Ui.dp(24), Ui.dp(32));
        fullView.addView(controls, controlsLp);

        card.addView(fullView, flp(-1, -1, Gravity.TOP, 0, 0, 0, 0));
        applyInsets();
    }

    // ------------------------------------------------------------ API

    void attach(MusicService s) {
        svc = s;
        refresh();
    }

    /** Inset sistem berubah: atur ulang posisi konten penuh. */
    void applyInsets() {
        int t = Ui.insetTop;
        handleLp.topMargin = t;
        artLp.topMargin = t + Ui.dp(40);
        titleLp.topMargin = artLp.topMargin + artLp.height + Ui.dp(22);
        seekLp.topMargin = titleLp.topMargin + Ui.dp(72);
        timesLp.topMargin = seekLp.topMargin + Ui.dp(44) + Ui.dp(10);      // di bawah WaveSlider, tidak menabrak
        controlsLp.bottomMargin = Ui.insetBottom + Ui.dp(32);
        handleArea.setLayoutParams(handleLp);
        art.setLayoutParams(artLp);
        titles.setLayoutParams(titleLp);
        seek.setLayoutParams(seekLp);
        times.setLayoutParams(timesLp);
        controls.setLayoutParams(controlsLp);
        applyProgress();
    }

    /** Bottom nav yang ikut turun saat pemutar dibuka penuh. */
    void setNav(View nav) {
        navView = nav;
        applyProgress();
    }

    /** Jarak mini dari tepi bawah: bottom nav + margin. Tanpa nav, memakai inset sistem. */
    void setBottomInset(int px) {
        bottomInset = px;
        applyProgress();
    }

    /** Tombol kembali: dari penuh turun ke mini. false kalau sudah mini. */
    boolean backStep() {
        if (progress > 0.02f) {
            animateTo(0f);
            return true;
        }
        return false;
    }

    private int lift() {
        return bottomInset > 0 ? bottomInset : Ui.insetBottom;
    }

    // ------------------------------------------------------------ geometri

    private void setProgress(float v) {
        float c = Math.max(0f, Math.min(1f, v));
        if (c == progress) return;
        progress = c;
        applyProgress();
    }

    private float visibleHeight() {
        return lerp(Ui.dp(MINI_DP), getHeight(), progress);
    }

    /** Jarak tempuh tepi atas kartu dari mini ke penuh (px). Tepi atas bergerak linear terhadap progress. */
    private float range() {
        return Math.max(1f, getHeight() - Ui.dp(MINI_DP) - lift());
    }

    private boolean hitVisible(float x, float y) {
        float side = Ui.dp(MINI_SIDE_DP) * (1f - progress);
        return x >= side && x <= card.getWidth() - side && y >= 0 && y <= visibleHeight();
    }

    private void applyProgress() {
        float p = progress, k = 1f - p;
        float H = getHeight();
        if (H <= 0) return;
        // Hanya translasi + outline + alpha: tidak ada requestLayout, jadi mulus saat ditarik
        card.setTranslationY(H - visibleHeight() - lift() * k);
        cardBg.setColor(M3.mix(Theme.SURFACE_HIGH, Theme.BG, p));
        card.invalidateOutline();

        // Mini memudar cepat; penuh muncul di paruh kedua
        float miniA = Math.max(0f, Math.min(1f, 1f - p * 2.5f));
        float fullA = Math.max(0f, Math.min(1f, (p - 0.35f) / 0.65f));
        miniView.setAlpha(miniA);
        miniView.setVisibility(miniA < 0.01f ? INVISIBLE : VISIBLE);
        fullView.setAlpha(fullA);
        fullView.setVisibility(fullA < 0.01f ? INVISIBLE : VISIBLE);
        seek.setWaveFactor(p);                                  // gelombang penuh saat terbuka, lurus saat diciutkan

        if (navView != null) {
            int navBottom = ((FrameLayout.LayoutParams) navView.getLayoutParams()).bottomMargin;
            float drop = navView.getHeight() + navBottom + Ui.dp(16);   // cukup untuk keluar layar
            navView.setTranslationY(drop * p);
            navView.setVisibility(p > 0.98f ? INVISIBLE : VISIBLE);
        }

        scrim.setAlpha(0.5f * p);
        scrim.setVisibility(p < 0.01f ? INVISIBLE : VISIBLE);
        scrim.setClickable(p > 0.5f);
    }

    /** Layer hardware selama bergerak: alpha dua tampilan ini tidak perlu digambar ulang tiap frame. */
    private void layers(boolean on) {
        int t = on ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (miniView.getLayerType() != t) miniView.setLayerType(t, null);
        if (fullView.getLayerType() != t) fullView.setLayerType(t, null);
    }

    // ------------------------------------------------------------ gestur

    private void begin(MotionEvent e) {
        cancelAnim();
        dragging = false;
        axisV = false;
        cancelSent = false;
        gestureActive = true;
        downRawX = e.getRawX();
        downRawY = e.getRawY();
        startP = progress;
        // Menggeser di atas seek bar berarti mencari posisi lagu, bukan menutup pemutar
        downOnSeek = progress > 0.9f && fullView.getVisibility() == VISIBLE
                && e.getX() >= seek.getLeft() && e.getX() <= seek.getRight()
                && e.getY() >= seek.getTop() - Ui.dp(12) && e.getY() <= seek.getBottom() + Ui.dp(12);
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        track(e);
    }

    /** Kecepatan dihitung dari koordinat layar: koordinat lokal ikut bergeser saat kartu bergerak. */
    private void track(MotionEvent e) {
        if (vt == null) return;
        MotionEvent c = MotionEvent.obtain(e);
        c.setLocation(e.getRawX(), e.getRawY());
        vt.addMovement(c);
        c.recycle();
    }

    private void clearTracker() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        gestureActive = false;
    }

    /** Mulai menyeret bila gerak sudah melewati slop dan dominan vertikal. */
    private boolean maybeStartDrag(MotionEvent e) {
        if (dragging) return true;
        if (downOnSeek) return false;
        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
        if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
            dragging = true;
            axisV = true;
            // Titik acuan dipindah ke posisi jari sekarang supaya sheet tidak melompat sejauh slop
            downRawY = e.getRawY();
            startP = progress;
            layers(true);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return false;
    }

    /** Dipanggil kartu saat ada anak yang memegang sentuhan: ambil alih begitu jelas vertikal. */
    private boolean intercept(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                track(e);
                return maybeStartDrag(e);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {                      // anak (tombol, seek bar) yang menuntaskan sentuhan
                    clearTracker();
                    if (anim == null) layers(false);
                }
                return false;
            default:
                return false;
        }
    }

    /** Dipanggil saat kartu sendiri yang memegang sentuhan. true bila sentuhan ini gestur kita. */
    private boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!gestureActive) begin(e);
                return false;                        // biarkan View mencatat tekan untuk klik
            case MotionEvent.ACTION_MOVE:
                track(e);
                if (!dragging) maybeStartDrag(e);
                if (dragging) {
                    setProgress(startP - (e.getRawY() - downRawY) / range());      // realtime, 1:1 dengan jari
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return finish(e);
            default:
                return false;
        }
    }

    private boolean finish(MotionEvent e) {
        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
        float vy = 0f;
        if (vt != null) {
            track(e);
            vt.computeCurrentVelocity(1000);
            vy = vt.getYVelocity();
        }
        clearTracker();
        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
        boolean wasDragging = dragging;
        dragging = false;
        if (wasDragging) {
            float target;
            if (up && Math.abs(vy) > VEL) target = vy < 0 ? 1f : 0f;     // fling menentukan arah
            else target = progress > 0.5f ? 1f : 0f;                       // kalau tidak, yang terdekat
            settle(target, -vy / range());                                  // lanjut dengan kecepatan jari
            return true;
        }
        if (anim == null) layers(false);
        // Geser horizontal di mini: ganti lagu
        if (up && progress < 0.5f && Math.abs(dx) > Ui.dp(80) && Math.abs(dx) > Math.abs(dy) && svc != null) {
            if (dx < 0) svc.next();
            else svc.prev();
            return true;
        }
        return false;
    }

    private void animateTo(float to) {
        settle(to, 0f);
    }

    /**
     * Pegas teredam kritis menuju `to`, dimulai dengan kecepatan v0 (progress/detik) supaya gerak
     * lepas jari menyambung mulus dengan gerak jari. Solusi tertutup: x(t) = (a + b t) e^(-wt),
     * tanpa integrasi numerik, jadi stabil di frame rate berapa pun.
     */
    private void settle(final float to, final float v0) {
        cancelAnim();
        final float a0 = progress - to;
        if (Math.abs(a0) < 0.001f && Math.abs(v0) < 0.05f) {
            setProgress(to);
            layers(false);
            return;
        }
        final float b0 = v0 + OMEGA * a0;
        // Durasi: sampai sisa jarak < 0.08% dan kecepatan hampir nol
        float t = 0f;
        while (t < 1.2f) {
            double ex = Math.exp(-OMEGA * t);
            float x = (float) ((a0 + b0 * t) * ex);
            float v = (float) ((b0 - OMEGA * (a0 + b0 * t)) * ex);
            if (Math.abs(x) < 0.0008f && Math.abs(v) < 0.02f) break;
            t += 0.004f;
        }
        final float total = Math.max(0.08f, t);
        layers(true);
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration((long) (total * 1000f));
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(x -> {
            float tt = x.getAnimatedFraction() * total;
            setProgress(to + (float) ((a0 + b0 * tt) * Math.exp(-OMEGA * tt)));
        });
        va.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                setProgress(to);
                if (anim == va) anim = null;
                if (!dragging) layers(false);
            }
        });
        anim = va;
        va.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
        }
    }

    // ------------------------------------------------------------ data

    void refresh() {
        Library.Song s = svc == null ? null : svc.current();
        boolean vis = s != null;
        if (vis != shown) {
            shown = vis;
            setVisibility(vis ? VISIBLE : GONE);
        }
        if (!vis) return;

        boolean changedNow = s.id != lastId;
        if (changedNow) {
            lastId = s.id;
            miniTitle.setText(s.title);
            title.setText(s.title);
            miniArt.setBackground(Ui.rect(M3.alpha(Theme.ON_VAR, 0.10f), 12));
            Art.bind(miniArt, s, Ui.tinted(act, R.drawable.ic_note, Theme.ON_VAR), Ui.dp(12));
            art.setBackground(Ui.rect(M3.alpha(Theme.ON_VAR, 0.10f), 24));
            Art.bind(art, s, Ui.tinted(act, R.drawable.ic_note, Theme.ON_VAR), Ui.dp(24));
        }
        int ld = svc.isLoading() ? 1 : 0;
        if (ld != lastLoading || changedNow) {
            lastLoading = ld;
            String sub = ld == 1 ? "Memuat…" : s.artist;
            miniArtist.setText(sub);
            artist.setText(sub);
        }

        boolean playing = svc.isPlaying();
        int pl = playing ? 1 : 0;
        if (pl != lastPlaying) {
            lastPlaying = pl;
            int res = playing ? R.drawable.ic_pause : R.drawable.ic_play;
            miniPlay.setImageDrawable(Ui.tinted(act, res, Theme.ON_PRIMARY));
            play.setImageDrawable(Ui.tinted(act, res, Theme.ON_PRIMARY));
        }
        boolean sh = svc.isShuffle();
        int shv = sh ? 1 : 0;
        if (shv != lastShuffle) {
            lastShuffle = shv;
            shuffleBtn.setImageDrawable(Ui.tinted(act, sh ? R.drawable.ic_shuffle : R.drawable.ic_shuffle_off,
                    sh ? Theme.PRIMARY : Theme.ON_VAR));
        }
        int rep = svc.getRepeat();
        if (rep != lastRepeat) {
            lastRepeat = rep;
            int rr = rep == MusicService.REPEAT_ONE ? R.drawable.ic_repeat_one
                    : rep == MusicService.REPEAT_ALL ? R.drawable.ic_repeat : R.drawable.ic_repeat_off;
            repeatBtn.setImageDrawable(Ui.tinted(act, rr, rep == MusicService.REPEAT_OFF ? Theme.ON_VAR : Theme.PRIMARY));
        }

        int d = svc.duration();
        int pos = svc.position();
        if (d > 0) {
            if (seek.getMax() != d) seek.setMax(d);
            dur.setText(Ui.time(d));
            miniProgress.setScaleX(Math.min(1f, (float) pos / d));
        }
        if (!seeking) {
            seek.setProgress(pos);
            cur.setText(Ui.time(pos));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        cancelAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) applyProgress();
    }

    // ------------------------------------------------------------ util

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static FrameLayout.LayoutParams flp(int w, int hgt, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }
}
