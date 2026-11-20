package ru.iqwanoino.music;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pengaturan, mengikuti SettingsActivity di Pinpic: judul besar yang mengecil saat di-scroll,
 * kartu dengan MaterialSwitch, dan pemilih warna aksen. Tema berubah tanpa recreate.
 */
public class SettingsActivity extends Activity {

    private static final float END_SCALE = 18f / 34f;   // 34sp mengecil jadi 18sp
    private static final int DEFAULT_ACCENT = Prefs.DEFAULT_APP_COLOR;
    private static final String KEY_SCROLL = "scrollY";
    private static final long THEME_STEP_MS = 120;
    private static final int REQ_FONT = 21;

    private int expandedH, collapsedH, maxScroll, lastY;
    private int topInset, bottomInset;
    private FrameLayout header;
    private FrameLayout rootFrame;
    private View backBtn;
    private TextView title;
    private LinearLayout col;
    private long lastThemeAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        Prefs.init(this);
        Theme.load(this);
        final int restoreY = savedInstanceState == null ? 0 : savedInstanceState.getInt(KEY_SCROLL, 0);
        buildScreen(restoreY, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_SCROLL, lastY);
    }

    /**
     * Bangun seluruh isi layar. Dipakai saat dibuka dan saat tema/warna berubah. Layar tidak di-recreate:
     * isinya dibangun ulang di tempat, scroll dipulihkan, dan tampilan lama dipudarkan.
     */
    private void buildScreen(final int restoreY, Bitmap snapshot) {
        final int viewportH = rootFrame != null ? rootFrame.getHeight() : 0;
        computeHeights();

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(M3.dp(16), expandedH, M3.dp(16), bottomInset + M3.dp(24));
        if (viewportH > 0) col.setMinimumHeight(viewportH + maxScroll);

        addBlock(section("Tampilan"));
        addBlock(displayCard());

        addBlock(section("Pustaka"));
        addBlock(card(
                actionRow(R.drawable.ic_refresh, "Muat ulang daftar lagu", "Pindai ulang lagu di perangkat",
                        v -> reloadLibrary())));

        addBlock(section("Tambahan"));
        addBlock(card(
                switchRow("Jangan tampilkan file ukuran kecil",
                        "File di bawah 500 KB disembunyikan", "Semua file ditampilkan",
                        Prefs.hideSmall(), true, null, on -> {
                            Prefs.setHideSmall(on);
                            Library.loaded = false;   // dimuat ulang saat kembali ke daftar
                        }),
                switchRow("Tampilkan tab storage",
                        "Tab Storage tampil di atas daftar", "Tab Storage disembunyikan",
                        Prefs.showStorageTab(), true, null, on -> Prefs.setShowStorageTab(on)),
                switchRow("Tampilkan sejarah diputar",
                        "Recent music menampilkan lagu yang terakhir diputar",
                        "Area Recent music disembunyikan",
                        Prefs.showHistory(), true, null, on -> Prefs.setShowHistory(on)),
                switchRow("Equalizer", "Tombol equalizer tampil di bar atas",
                        "Tombol equalizer disembunyikan",
                        Prefs.eqEnabled(), true, null, on -> Prefs.setEqEnabled(on)),
                actionRow(R.drawable.ic_tune, "Equalizer", "Buka equalizer di halaman penuh",
                        v -> startActivity(new android.content.Intent(this, EqualizerActivity.class)))));

        addBlock(section("Tentang"));
        addBlock(card(infoRow(R.drawable.ic_info, "Versi", versionName())));

        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(col);
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int min = (b - t) + maxScroll;
            if (col.getMinimumHeight() != min) col.setMinimumHeight(min);
        });
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> collapse(y));

        header = buildHeader();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(M3.background);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(header, new FrameLayout.LayoutParams(-1, expandedH, Gravity.TOP));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = i.top;
                bottomInset = i.bottom;
            } else {
                topInset = ins.getSystemWindowInsetTop();
                bottomInset = ins.getSystemWindowInsetBottom();
            }
            Ui.insetTop = topInset;
            Ui.insetBottom = bottomInset;   // dipakai Sheet
            applyInsets();
            return ins;
        });

        rootFrame = root;
        setContentView(root);
        setupEdgeToEdge();
        collapse(restoreY);

        if (restoreY > 0) {
            sv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    sv.scrollTo(0, restoreY);
                    collapse(restoreY);
                    return true;
                }
            });
        }

        if (snapshot != null) fadeOutSnapshot(root, snapshot);
    }

    private void fadeOutSnapshot(final FrameLayout host, final Bitmap bmp) {
        final ImageView cover = new ImageView(this);
        cover.setImageBitmap(bmp);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        host.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        cover.animate().alpha(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    host.removeView(cover);
                    cover.setImageDrawable(null);
                    bmp.recycle();
                }).start();
    }

    /** Hentikan ripple yang masih berjalan sebelum snapshot diambil. */
    private void endRipples(View v) {
        if (v == null) return;
        Drawable bg = v.getBackground();
        if (bg instanceof RippleDrawable) {
            v.setPressed(false);
            bg.setState(new int[0]);
            bg.jumpToCurrentState();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) endRipples(g.getChildAt(i));
        }
    }

    private Bitmap snapshotOf(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        try {
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** Ikon bar sistem dan warna bar mengikuti tema. */
    private void setupEdgeToEdge() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(M3.background));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        Ui.cutoutShortEdges(w);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        M3.styleBars(w);
    }

    private void computeHeights() {
        expandedH = topInset + M3.dp(176);
        collapsedH = topInset + M3.dp(56);
        maxScroll = expandedH - collapsedH;
    }

    private void applyInsets() {
        computeHeights();
        if (col != null) col.setPadding(M3.dp(16), expandedH, M3.dp(16), bottomInset + M3.dp(24));
        if (backBtn != null) {
            FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) backBtn.getLayoutParams();
            bl.topMargin = topInset + (M3.dp(56) - M3.dp(48)) / 2;
            backBtn.setLayoutParams(bl);
        }
        if (header != null) collapse(lastY);
    }

    private FrameLayout buildHeader() {
        FrameLayout h = new FrameLayout(this);
        h.setBackgroundColor(M3.background);

        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageDrawable(VecIcon.of(this, R.drawable.ic_back, M3.primary, M3.dp(24)));
        RippleDrawable br = new RippleDrawable(ColorStateList.valueOf(M3.rippleColor()), null, null);
        br.setRadius(M3.dp(20));
        back.setBackground(br);
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(M3.dp(48), M3.dp(48), Gravity.TOP | Gravity.START);
        bl.topMargin = (collapsedH - M3.dp(48)) / 2;
        bl.leftMargin = M3.dp(4);
        h.addView(back, bl);
        backBtn = back;

        title = M3.text(this, "Pengaturan", 34, M3.onSurface, true);
        title.setLetterSpacing(-0.02f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tl.leftMargin = M3.dp(16);
        h.addView(title, tl);

        h.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> collapse(lastY));
        return h;
    }

    /** Dipanggil setiap scroll. scrollY = jarak konten yang sudah di-scroll. */
    private void collapse(int scrollY) {
        lastY = scrollY;
        float p = Math.max(0f, Math.min(1f, scrollY / (float) maxScroll));

        int h = Math.max(collapsedH, expandedH - scrollY);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) header.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            header.setLayoutParams(lp);
        }

        float th = title.getHeight();
        if (th == 0) {
            title.measure(0, 0);
            th = title.getMeasuredHeight();
        }
        float s = 1f + (END_SCALE - 1f) * p;
        float topExpanded = expandedH - M3.dp(16) - th;
        float topCollapsed = topInset + (M3.dp(56) - th * END_SCALE) / 2f;

        title.setPivotX(0f);
        title.setPivotY(0f);
        title.setScaleX(s);
        title.setScaleY(s);
        title.setTranslationX(M3.dp(48) * p);
        title.setTranslationY(topExpanded + (topCollapsed - topExpanded) * p);
    }

    // ------------------------------------------------------------ bagian & kartu

    private TextView section(String label) {
        TextView t = M3.text(this, label, 14, M3.primary, true);
        t.setPadding(M3.dp(8), M3.dp(20), M3.dp(8), M3.dp(4));
        return t;
    }

    private void addBlock(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = M3.dp(8);
        col.addView(v, lp);
    }

    /** Kartu berisi baris-baris, dipisahkan garis tipis. */
    private LinearLayout card(View... rows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(M3.shape(M3.surfaceContainerHigh, 20));
        card.setClipToOutline(true);
        for (int i = 0; i < rows.length; i++) {
            card.addView(rows[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < rows.length - 1) card.addView(divider(), dividerParams());
        }
        return card;
    }

    // ------------------------------------------------------------ tampilan

    private interface Toggle { void onToggle(boolean on); }

    /** Format tampilan nilai slider. */
    private interface Fmt { String format(int v); }

    /** Penerima nilai slider setelah disentuh pengguna. */
    private interface IntSink { void set(int v); }

    /** Kartu Tampilan: tema, dynamic color, warna aksen, dan mode grid. */
    private LinearLayout displayCard() {
        final boolean dynamicOk = Build.VERSION.SDK_INT >= 31;
        final boolean dynamicOn = dynamicOk && Prefs.dynamicColor();

        final LinearLayout accentBox = new LinearLayout(this);
        accentBox.setOrientation(LinearLayout.VERTICAL);
        accentBox.addView(divider(), dividerParams());
        accentBox.addView(accentRow(), new LinearLayout.LayoutParams(-1, -2));
        accentBox.setVisibility(dynamicOn ? View.GONE : View.VISIBLE);

        LinearLayout themeRow = switchRow("Tema aplikasi", "Gelap", "Terang",
                Prefs.darkTheme(), true, null, on -> {
                    Prefs.setDarkTheme(on);
                    applyTheme();
                });
        LinearLayout dynRow = switchRow("Dynamic color", "Warna dari wallpaper", "Warna dari App color",
                dynamicOn, dynamicOk, "Butuh Android 12 atau lebih baru", on -> {
                    Prefs.setDynamicColor(on);
                    accentBox.setVisibility(on ? View.GONE : View.VISIBLE);
                    applyTheme();
                });
        LinearLayout gridRow = switchRow("Tampilan grid", "Grid", "List",
                Theme.storedGrid(this), true, null, on -> Theme.setGrid(this, on));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(M3.shape(M3.surfaceContainerHigh, 20));
        card.setClipToOutline(true);
        card.addView(themeRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider(), dividerParams());
        card.addView(dynRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(accentBox, new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider(), dividerParams());
        card.addView(gridRow, new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider(), dividerParams());
        card.addView(sliderRow("Kolom grid", 1, 4, Prefs.gridCols(),
                v -> v + " kolom", v -> Prefs.setGridCols(v)), new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider(), dividerParams());
        card.addView(sliderRow("Jumlah Recent music", 1, Prefs.RECENT_MAX, Prefs.recentCount(),
                v -> v + " lagu", v -> Prefs.setRecentCount(v)), new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider(), dividerParams());
        card.addView(fontRow(), new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** Baris "Font kustom": ketuk untuk memilih file font (TTF, OTF, atau jenis lain). */
    private LinearLayout fontRow() {
        final LinearLayout row = baseRow();
        boolean custom = !Prefs.customFont().isEmpty();
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(M3.text(this, "Font kustom", 16, M3.onSurface, false));
        String name = Prefs.customFontName();
        texts.addView(M3.text(this, custom ? (name.isEmpty() ? "Font kustom" : name) : "Bawaan (Google Sans)",
                13, M3.onSurfaceVariant, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> fontSheet());
        return row;
    }

    private void fontSheet() {
        final Sheet s = new Sheet(this, true);
        s.panel.addView(Ui.text(this, "Font kustom", 20, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(8)));
        s.panel.addView(Ui.text(this,
                        "Pilih file font (TTF, OTF, atau jenis lain yang didukung Android). "
                                + "Font dipakai di seluruh aplikasi.",
                        14, Theme.ON_VAR, false),
                Ui.lp(-1, -2, 0, 0, 0, Ui.dp(16)));
        s.panel.addView(Ui.button(this, "Pilih file font", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
            s.dismiss();
            pickFont();
        }), Ui.lp(-1, Ui.dp(52)));
        if (!Prefs.customFont().isEmpty()) {
            s.panel.addView(Ui.button(this, "Kembali ke font bawaan", 0, Theme.PRIMARY, () -> {
                s.dismiss();
                Fonts.reset(this);
                applyTheme();
            }), Ui.lp(-1, Ui.dp(52), 0, Ui.dp(8), 0, 0));
        }
        s.show();
    }

    private void pickFont() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");   // semua file boleh dipilih; keabsahannya dicek saat dimuat
        try {
            startActivityForResult(i, REQ_FONT);
        } catch (Exception e) {
            M3.toast(this, "Pemilih file tidak tersedia");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FONT || res != RESULT_OK || data == null || data.getData() == null) return;
        if (Fonts.install(this, data.getData())) {
            applyTheme();
        } else {
            M3.toast(this, "File itu bukan font yang valid");
        }
    }

    /**
     * Baris berisi judul, nilai di kanan atas, dan SliderRange di bawahnya. Nilai slider 0..(max-min)
     * ditambah min sebelum dikirim ke [sink] dan ditampilkan lewat [fmt].
     */
    private LinearLayout sliderRow(String label, final int min, int max, int value,
                                   final Fmt fmt, final IntSink sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(M3.dp(16), M3.dp(14), M3.dp(16), M3.dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(M3.text(this, label, 16, M3.onSurface, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = M3.text(this, fmt.format(value), 14, M3.primary, true);
        head.addView(val, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        SliderRange slider = new SliderRange(this);
        slider.setTrackActiveColor(M3.primary);
        slider.setTrackInactiveColor(M3.alpha(M3.primary, 0.3f));
        slider.setThumbColor(M3.primary);
        slider.setDotActiveColor(M3.onPrimary);
        slider.setDotInactiveColor(M3.outline);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        slider.setOnSliderChangeListener(new SliderRange.SliderListener() {
            @Override
            public void onValueChanged(SliderRange s, int v, boolean fromUser) {
                int real = v + min;
                val.setText(fmt.format(real));
                if (fromUser) sink.set(real);
            }

            @Override public void onStartTracking(SliderRange s) { }

            @Override public void onStopTracking(SliderRange s) { }
        });
        box.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** Baris dengan MaterialSwitch. Saklar hanya tampilan; baris yang menangani ketukan. */
    private LinearLayout switchRow(String label, final String onText, final String offText, boolean on,
                                   boolean enabled, String disabledText, final Toggle toggle) {
        final LinearLayout row = baseRow();
        final TextView sub = M3.text(this, "", 13, M3.onSurfaceVariant, false);
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setEnabled(false);
        sw.setFocusable(false);
        sw.setActiveColor(M3.primary);
        sw.setInactiveColor(Color.TRANSPARENT);
        sw.setStrokeActiveColor(M3.primary);
        sw.setStrokeInactiveColor(M3.outline);
        sw.setThumbActiveColor(M3.onPrimary);
        sw.setThumbInactiveColor(M3.outline);
        sw.setCheckedImmediate(on);
        sub.setText(!enabled ? disabledText : (on ? onText : offText));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(M3.text(this, label, 16, M3.onSurface, false));
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));

        if (enabled) {
            row.setOnClickListener(v -> {
                boolean next = !sw.isChecked();
                sw.setChecked(next);
                sub.setText(next ? onText : offText);
                toggle.onToggle(next);
            });
        } else {
            row.setClickable(false);
            row.setBackground(null);
            row.setAlpha(0.38f);
        }
        return row;
    }

    /** Baris "App color": ketuk untuk membuka pemilih warna. */
    private LinearLayout accentRow() {
        final LinearLayout row = baseRow();
        final int saved = Prefs.appColor();
        final int current = saved != 0 ? saved : DEFAULT_ACCENT;

        final TextView sub = M3.text(this, saved == Prefs.DEFAULT_APP_COLOR ? "Teal bawaan" : hex(saved),
                13, M3.onSurfaceVariant, false);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(M3.text(this, "App color", 16, M3.onSurface, false));
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(current);
        View dotView = new View(this);
        dotView.setBackground(dot);
        row.addView(dotView, new LinearLayout.LayoutParams(M3.dp(28), M3.dp(28)));

        row.setOnClickListener(v -> ColorPicker.show(this, current, color -> {
            Prefs.setAppColor(color);
            applyTheme();
        }));
        return row;
    }

    /** Beri jeda singkat supaya saklar yang diketuk selesai bergeser, lalu bangun ulang layar. */
    private void applyTheme() {
        rootFrame.removeCallbacks(rebuildRunnable);
        long wait = Math.max(0, lastThemeAt + THEME_STEP_MS - SystemClock.uptimeMillis());
        rootFrame.postDelayed(rebuildRunnable, wait);
    }

    private final Runnable rebuildRunnable = () -> rebuildTheme();

    private void rebuildTheme() {
        if (isFinishing() || isDestroyed()) return;
        lastThemeAt = SystemClock.uptimeMillis();
        final int y = lastY;
        endRipples(rootFrame);
        final Bitmap snap = snapshotOf(rootFrame);
        Theme.load(this);   // palet dihitung ulang dari Prefs terbaru
        buildScreen(y, snap);
    }

    private void reloadLibrary() {
        Library.loaded = false;
        Library.load(this, songs ->
                M3.toast(this, "Daftar lagu dimuat ulang (" + songs.size() + ")"));
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(M3.alpha(M3.onSurface, 0.13f));
        return v;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, M3.dp(1));
        dl.leftMargin = M3.dp(60);
        return dl;
    }

    private static String hex(int argb) {
        return String.format("#%06X", 0xFFFFFF & argb);
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(M3.dp(16), M3.dp(14), M3.dp(16), M3.dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(M3.rippleColor()), null, new ColorDrawable(Color.WHITE)));
        row.setClickable(true);
        return row;
    }

    private View actionRow(int iconRes, String label, String sub, View.OnClickListener click) {
        LinearLayout row = baseRow();
        row.addView(icon(iconRes), new LinearLayout.LayoutParams(M3.dp(24), M3.dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(M3.text(this, label, 16, M3.onSurface, false));
        texts.addView(M3.text(this, sub, 13, M3.onSurfaceVariant, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = M3.dp(20);
        row.addView(texts, tl);
        row.setOnClickListener(click);
        return row;
    }

    private View infoRow(int iconRes, String label, String value) {
        LinearLayout row = baseRow();
        row.setClickable(false);
        row.addView(icon(iconRes), new LinearLayout.LayoutParams(M3.dp(24), M3.dp(24)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(M3.text(this, label, 16, M3.onSurface, false));
        texts.addView(M3.text(this, value, 13, M3.onSurfaceVariant, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = M3.dp(20);
        row.addView(texts, tl);
        return row;
    }

    private ImageView icon(int iconRes) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(VecIcon.of(this, iconRes, M3.primary, M3.dp(24)));
        return iv;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "-";
        }
    }
}
