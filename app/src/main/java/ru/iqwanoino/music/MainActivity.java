package ru.iqwanoino.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String STORAGE = "\u0001storage";
    private static final int REQ_PERM = 7;
    private static final String ALL = "\u0001all";
    private static final String ONLINE = "\u0001online";
    private static final String PERM_AUDIO = Build.VERSION.SDK_INT >= 33
            ? "android.permission.READ_MEDIA_AUDIO"
            : "android.permission.READ_EXTERNAL_STORAGE";
    private static final String PERM_NOTIF = "android.permission.POST_NOTIFICATIONS";

    // Tautan terkait, sama dengan Pinpic
    private static final String URL_DISCORD = "https://discord.gg/u7QPpQD7QC";
    private static final String URL_GITHUB = "https://github.com/IqwanXD/";
    private static final String URL_TELEGRAM = "https://t.me/IqwanoINO/";

    private PushDrawerLayout drawerLayout;
    private SimpleSwipeRefreshLayout swipe;
    private LinearLayout drawer, topBar, pillRow, recentRow;
    private HorizontalScrollView pillScroll, recentScroll;
    private TextView subtitle, emptyText, recentLabel;
    private ImageView gridBtn, tuneBtn;
    private FrameLayout content;
    private ListView list;
    private GridView grid;
    private ListView folderList;
    private android.widget.EditText searchEdit;
    private LinearLayout searchBox;
    private View focusSink;
    private ImageView searchClear;
    private String query = "";
    private final Map<String, Integer> folderCounts = new HashMap<>();
    private FolderAdapter folderAdapter;
    private boolean storageMode, lastStorageTab;
    // Mode Online: hasil pencarian / populer dari sumber online
    private boolean onlineMode, onlineLoading;
    private String onlineQuery = null, onlineError;
    private int searchSeq;
    private final List<Library.Song> onlineResults = new ArrayList<>();
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = () -> runOnlineSearch(false, false);
    private SongAdapter listAdapter, gridAdapter;
    private PlayerPanel panel;
    private LinearLayout col;
    private BottomNav nav;
    private HomePage home;
    private android.widget.ScrollView you;
    private int tab = BottomNav.HOME;
    // Paging hasil online: tiap halaman disimpan terpisah, lalu digabung tanpa duplikat
    private int onlinePage, trendBase;
    private boolean onlineFetching, onlineExhausted;
    private final java.util.Map<Integer, List<Library.Song>> onlinePages = new java.util.HashMap<>();
    private final android.widget.AbsListView.OnScrollListener moreListener =
            new android.widget.AbsListView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(android.widget.AbsListView v, int s) { }

        @Override
        public void onScroll(android.widget.AbsListView v, int first, int vis, int total) {
            if (onlineMode && !onlineFetching && !onlineExhausted && !onlineResults.isEmpty()
                    && total > 0 && first + vis >= total - 8) {
                runOnlineSearch(true, false);
            }
        }
    };
    private Sheet openSheet;
    private MusicService svc;
    private boolean bound, gridMode;
    private String shown = "";
    private int tileWidth;
    private int appliedCols = -1, lastRecent = -1;
    private boolean lastShowHistory;
    private long lastRecentPlaying = -2;
    private String selectedFolder;
    private final List<String> folders = new ArrayList<>();
    private final List<Library.Song> visible = new ArrayList<>();

    private final MusicService.Listener uiListener = this::refreshUi;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((MusicService.LocalBinder) binder).get();
            svc.addListener(uiListener);
            panel.attach(svc);
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            panel.attach(null);
        }
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Prefs.init(this);
        Theme.load(this);

        gridMode = Theme.storedGrid(this);
        lastStorageTab = Prefs.showStorageTab();
        shown = M3.paletteKey;
        if (state != null) restoreState(state);     // ganti tema = activity dibuat ulang: pulihkan posisi
        buildUi();
        if (state != null) applyRestored();
        // Harus SETELAH setContentView (buildUi). Jika sebelumnya, flag contrast nav bar
        // tidak bertahan dan scrim sistem tetap muncul. Pinpic dan Settings memanggilnya setelah.
        Ui.setupEdge(this);

        startService(new Intent(this, MusicService.class));
        bound = bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);

        if (hasAudio()) {
            loadLibrary();
        } else {
            rebuildFolders();       // tab Online tetap bisa dipakai tanpa izin audio
            updateEmpty("Izin audio belum diberikan.\nKetuk untuk memberi izin.");
            showPermissionSheet();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("q", query);
        out.putBoolean("online", onlineMode);
        out.putBoolean("storage", storageMode);
        out.putString("folder", selectedFolder == null ? "" : selectedFolder);
        out.putInt("tab", tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!M3.paletteKey.equals(shown)) {
            recreate();
            return;
        }
        tuneBtn.setVisibility(Prefs.eqEnabled() ? android.view.View.VISIBLE : android.view.View.GONE);
        if (home != null && tab == BottomNav.HOME) home.refresh();
        boolean g = Theme.storedGrid(this);
        if (g != gridMode) {
            gridMode = g;
            applyGridMode();
        }
        // Tab Storage diubah di Pengaturan: terapkan saat kembali.
        boolean st = Prefs.showStorageTab();
        if (st != lastStorageTab) {
            lastStorageTab = st;
            if (!st && storageMode) {
                storageMode = false;
                selectedFolder = null;
            }
            rebuildFolders();
            applyFilter();
            applyGridMode();
        }
        // Kolom grid dan jumlah Recent bisa diubah di Pengaturan: terapkan saat kembali.
        int cols = Prefs.gridCols();
        if (cols != appliedCols) {
            appliedCols = cols;
            applyGridLayout();
        }
        int rc = Prefs.recentCount();
        boolean sh = Prefs.showHistory();
        if (rc != lastRecent || sh != lastShowHistory) {
            lastRecent = rc;
            lastShowHistory = sh;
            buildRecent();
        }
        if (hasAudio() && !Library.loaded) loadLibrary();
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        if (svc != null) svc.removeListener(uiListener);
        if (bound) unbindService(conn);
        uiHandler.removeCallbacks(searchRunnable);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isOpen()) drawerLayout.close();
        else if (openSheet != null) openSheet.dismiss();
        else if (panel.backStep()) { /* turun satu tingkat pemutar */ }
        else if (tab != BottomNav.HOME) showTab(BottomNav.HOME);
        else super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (hasAudio()) {
            loadLibrary();
        } else {
            subtitle.setText("Belum ada akses");
            updateEmpty("Izin audio ditolak.\nKetuk untuk memberi izin.");
        }
    }

    // ------------------------------------------------------------ layout

    private void buildUi() {
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        // Penampung fokus: begitu kolom pencarian dilepas, fokus pindah ke sini (bukan kembali ke EditText).
        col.setFocusable(true);
        col.setFocusableInTouchMode(true);
        col.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        focusSink = col;

        // Top bar: menu, judul, toggle grid/list
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(Ui.iconButton(this, R.drawable.ic_menu, Theme.PRIMARY, 48, () -> drawerLayout.toggle()),
                Ui.lp(Ui.dp(48), Ui.dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "Music", 22, Theme.ON, true), Ui.lp(-2, -2));
        subtitle = Ui.text(this, "Memuat lagu…", 13, Theme.ON_VAR, false);
        titles.addView(subtitle, Ui.lp(-2, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Ui.dp(8);
        topBar.addView(titles, tl);

        tuneBtn = Ui.iconButton(this, R.drawable.ic_tune, Theme.ON, 48, this::showEqSheet);
        tuneBtn.setVisibility(Prefs.eqEnabled() ? android.view.View.VISIBLE : android.view.View.GONE);
        topBar.addView(tuneBtn, Ui.lp(Ui.dp(48), Ui.dp(48)));
        gridBtn = Ui.iconButton(this, gridMode ? R.drawable.ic_list : R.drawable.ic_grid, Theme.ON, 48,
                this::toggleGrid);
        topBar.addView(gridBtn, Ui.lp(Ui.dp(48), Ui.dp(48)));
        col.addView(topBar, Ui.lp(-1, -2));

        // Kolom pencarian (nama lagu, artis, album; di tab Storage mencari nama folder)
        searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.rect(Theme.SURFACE_HIGH, 26));
        searchBox.setPadding(Ui.dp(16), 0, Ui.dp(6), 0);
        searchBox.addView(Ui.icon(this, R.drawable.ic_search, Theme.PRIMARY), Ui.lp(Ui.dp(22), Ui.dp(22)));

        searchEdit = new android.widget.EditText(this);
        searchEdit.setBackground(null);
        searchEdit.setHint("Cari lagu");
        searchEdit.setHintTextColor(Theme.ON_VAR);
        searchEdit.setTextColor(Theme.ON);
        searchEdit.setTextSize(15);
        searchEdit.setTypeface(Fonts.regular(this));
        searchEdit.setSingleLine(true);
        searchEdit.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        searchEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        InoInputLayout.tintCursor(searchEdit);
        searchEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s.toString().trim();
                searchClear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (onlineMode) {
                    scheduleOnlineSearch();
                    return;
                }
                applyFilter();
                if (listAdapter.getCount() > 0) list.setSelection(0);
                if (gridAdapter.getCount() > 0) grid.setSelection(0);
                if (folderAdapter.getCount() > 0) folderList.setSelection(0);
            }
        });
        searchEdit.setOnEditorActionListener((v, action, ev) -> {
            hideKeyboard();
            // Tombol Cari: dari daftar lokal langsung beralih ke pencarian online
            if (!onlineMode && !query.isEmpty()) selectFolder(ONLINE);
            else if (onlineMode) {
                uiHandler.removeCallbacks(searchRunnable);
                runOnlineSearch(false, false);
            }
            return true;
        });
        searchBox.addView(searchEdit, new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));

        searchClear = Ui.iconButton(this, R.drawable.ic_close, Theme.ON_VAR, 40, () -> searchEdit.setText(""));
        searchClear.setVisibility(View.GONE);
        searchBox.addView(searchClear, Ui.lp(Ui.dp(40), Ui.dp(40)));
        col.addView(searchBox, Ui.lp(-1, Ui.dp(52), Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(8)));

        // Tab pil folder
        pillScroll = new HorizontalScrollView(this);
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillRow = new LinearLayout(this);
        pillRow.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(12));
        pillScroll.addView(pillRow);
        col.addView(pillScroll, Ui.lp(-1, -2));

        // Recent music
        recentLabel = Ui.text(this, "Recent music", 14, Theme.ON, true);
        recentLabel.setPadding(Ui.dp(16), 0, Ui.dp(16), Ui.dp(8));
        col.addView(recentLabel, Ui.lp(-1, -2));

        recentScroll = new HorizontalScrollView(this);
        recentScroll.setHorizontalScrollBarEnabled(false);
        recentRow = new LinearLayout(this);
        recentRow.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        recentScroll.addView(recentRow);
        col.addView(recentScroll, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(12)));

        // Konten list/grid, dibungkus SwipeRefresh (tarik untuk memuat ulang)
        content = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        Ui.fade(list);
        list.setClipToPadding(false);
        listAdapter = new SongAdapter(this, false, 0);
        list.setAdapter(listAdapter);
        list.setOnScrollListener(moreListener);
        list.setOnItemClickListener((parent, view, pos, id) -> playPosition(pos));
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos < visible.size()) Playlists.pickSheet(this, visible.get(pos));
            return true;
        });
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));

        grid = new GridView(this);
        grid.setHorizontalSpacing(Ui.dp(12));
        grid.setVerticalSpacing(Ui.dp(12));
        grid.setClipToPadding(false);
        Ui.fade(grid);
        grid.setSelector(new ColorDrawable(0));
        gridAdapter = new SongAdapter(this, true, tileWidth);
        grid.setAdapter(gridAdapter);
        grid.setOnScrollListener(moreListener);
        grid.setOnItemClickListener((parent, view, pos, id) -> playPosition(pos));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos < visible.size()) Playlists.pickSheet(this, visible.get(pos));
            return true;
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        // Tab Storage: daftar folder dengan ikon folder, ketuk untuk membuka isinya
        folderList = new ListView(this);
        folderList.setDivider(null);
        folderList.setDividerHeight(0);
        folderList.setSelector(new ColorDrawable(0));
        folderList.setCacheColorHint(0);
        Ui.fade(folderList);
        folderList.setClipToPadding(false);
        folderAdapter = new FolderAdapter(this);
        folderList.setAdapter(folderAdapter);
        folderList.setOnItemClickListener((parent, view, pos, id) -> selectFolder(folderAdapter.getItem(pos)));
        content.addView(folderList, new FrameLayout.LayoutParams(-1, -1));

        emptyText = Ui.text(this, "", 15, Theme.ON_VAR, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(Ui.dp(32), 0, Ui.dp(32), 0);
        emptyText.setOnClickListener(v -> {
            if (!hasAudio()) showPermissionSheet();
        });
        emptyText.setVisibility(View.GONE);
        content.addView(emptyText, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        ScrollThumb.attach(list, content);
        ScrollThumb.attach(grid, content);
        ScrollThumb.attach(folderList, content);

        swipe = new SimpleSwipeRefreshLayout(this);
        swipe.setProgressColor(Theme.PRIMARY);
        swipe.setIndicatorBackgroundColor(Theme.SURFACE_HIGH);
        swipe.addView(content, new FrameLayout.LayoutParams(-1, -1));
        swipe.setOnRefreshListener(this::reloadLibrary);
        col.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1f));

        applyGridLayout();
        applyGridMode();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        col.setVisibility(View.GONE);      // Beranda dulu; Cari/Pustaka dibuka lewat nav

        // Tab Beranda: halaman sendiri
        home = new HomePage(this, (songs, pos) -> {
            if (svc != null) svc.playQueue(songs, pos);
        });
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        // Tab Kamu: menu (Playlist, Pengaturan, dll) memakai item drawer yang sama
        you = new android.widget.ScrollView(this);
        you.setVerticalScrollBarEnabled(false);
        LinearLayout youCol = new LinearLayout(this);
        youCol.setOrientation(LinearLayout.VERTICAL);
        youCol.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        buildDrawer(youCol);
        youCol.addView(drawerItem(R.drawable.ic_tune, "Ekualiser", this::showEqSheet));
        you.addView(youCol);
        root.addView(you, new FrameLayout.LayoutParams(-1, -1));
        you.setVisibility(View.GONE);

        // Bottom nav mengambang: di atas konten, di bawah pemutar
        nav = new BottomNav(this, this::showTab);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, Ui.dp(68), Gravity.BOTTOM);
        nlp.setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.insetBottom + Ui.dp(12));
        root.addView(nav, nlp);

        // Pemutar di atas segalanya
        panel = new PlayerPanel(this);
        panel.setNav(nav);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        drawer = new LinearLayout(this);
        buildDrawer(drawer);

        drawerLayout = new PushDrawerLayout(this, (int) (getResources().getDisplayMetrics().widthPixels * 0.80f));
        drawerLayout.setViews(root, drawer);
        drawerLayout.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            applyInsets();
            return ins;
        });
        setContentView(drawerLayout);
        applyInsets();
        refreshUi();
    }

    private void restoreState(Bundle s) {
        query = s.getString("q", "");
        onlineMode = s.getBoolean("online", false);
        storageMode = s.getBoolean("storage", false);
        String f = s.getString("folder", "");
        selectedFolder = f.isEmpty() ? null : f;
        tab = s.getInt("tab", BottomNav.HOME);
    }

    /** Setelah tampilan dibuat: kembalikan kata kunci dan tab. Hasil ambil dari cache, tanpa jaringan ulang. */
    private void applyRestored() {
        if (!query.isEmpty()) searchEdit.setText(query);
        showTab(tab);
    }

    /** Pindah tab bawah. Cari dan Pustaka memakai daftar yang sama; bedanya sumber: online atau lokal. */
    private void showTab(int t) {
        tab = t;
        if (nav != null) nav.select(t);
        boolean browse = t == BottomNav.SEARCH || t == BottomNav.LIBRARY;
        col.setVisibility(browse ? View.VISIBLE : View.GONE);
        home.setVisibility(t == BottomNav.HOME ? View.VISIBLE : View.GONE);
        you.setVisibility(t == BottomNav.YOU ? View.VISIBLE : View.GONE);
        if (t == BottomNav.HOME) home.refresh();
        if (t == BottomNav.SEARCH && !onlineMode) selectFolder(ONLINE);
        if (t == BottomNav.LIBRARY && onlineMode) selectFolder(ALL);
    }

    /** Padding yang bergantung pada inset sistem. */
    private void applyInsets() {
        int t = Ui.insetTop, b = Ui.insetBottom;
        topBar.setPadding(Ui.dp(8), t + Ui.dp(8), Ui.dp(12), Ui.dp(8));
        drawer.setPadding(Ui.dp(24), t + Ui.dp(24), Ui.dp(24), b + Ui.dp(24));
        int pad = b + Ui.dp(PlayerPanel.MINI_DP) + Ui.dp(96);      // mini player + bottom nav
        list.setPadding(Ui.dp(8), 0, Ui.dp(8), pad);
        folderList.setPadding(Ui.dp(12), 0, Ui.dp(12), pad);
        grid.setPadding(Ui.dp(16), 0, Ui.dp(16), pad);
        if (nav != null) {
            FrameLayout.LayoutParams nlp = (FrameLayout.LayoutParams) nav.getLayoutParams();
            nlp.bottomMargin = b + Ui.dp(12);
            nav.setLayoutParams(nlp);
        }
        if (you != null) you.setPadding(0, t + Ui.dp(16), 0, b + Ui.dp(140));
        if (panel != null) {
            panel.setBottomInset(b + Ui.dp(90));
            panel.applyInsets();
        }
    }

    private void applyGridMode() {
        boolean songs = !storageMode;
        list.setVisibility(songs && !gridMode ? View.VISIBLE : View.GONE);
        grid.setVisibility(songs && gridMode ? View.VISIBLE : View.GONE);
        folderList.setVisibility(storageMode ? View.VISIBLE : View.GONE);
        if (swipe != null) swipe.setTargetView(storageMode ? folderList : (gridMode ? grid : list));
        if (gridBtn != null) {
            gridBtn.setImageDrawable(Ui.tinted(this, gridMode ? R.drawable.ic_list : R.drawable.ic_grid, Theme.PRIMARY));
        }
    }

    /** Terapkan jumlah kolom grid dari Pengaturan. Kotak artwork mengikuti lebar kolom. */
    private void applyGridLayout() {
        int cols = Prefs.gridCols();
        if (grid.getNumColumns() != cols) grid.setNumColumns(cols);

        int sw = getResources().getDisplayMetrics().widthPixels;
        int gap = Ui.dp(12);
        tileWidth = (sw - Ui.dp(32) - gap * (cols - 1)) / cols;
        gridAdapter.setTile(tileWidth, tileWidth - Ui.dp(12));
    }

    private void toggleGrid() {
        gridMode = !gridMode;
        Theme.setGrid(this, gridMode);
        applyGridMode();
    }

    private void buildDrawer(LinearLayout d) {
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(Theme.BG);
        d.setClickable(true);

        d.addView(Ui.text(this, "Music", 34, Theme.PRIMARY, true), Ui.lp(-2, -2));
        d.addView(Ui.text(this, "Musik lokal dan online", 14, Theme.ON_VAR, false),
                Ui.lp(-2, -2, 0, 0, 0, Ui.dp(20)));

        d.addView(drawerItem(R.drawable.ic_queue_music, "Playlist",
                () -> startActivity(new Intent(this, PlaylistActivity.class))));
        d.addView(drawerItem(R.drawable.ic_info, "Informasi", this::showInfo));
        d.addView(drawerItem(R.drawable.ic_settings, "Pengaturan",
                () -> startActivity(new Intent(this, SettingsActivity.class))));

        View div = new View(this);
        div.setBackgroundColor((0x33 << 24) | (Theme.ON & 0x00FFFFFF));
        d.addView(div, Ui.lp(-1, Ui.dp(1), 0, Ui.dp(16), 0, Ui.dp(16)));

        TextView related = Ui.text(this, "Tautan terkait", 14, Theme.ON_VAR, true);
        related.setPadding(0, 0, 0, Ui.dp(8));
        d.addView(related, Ui.lp(-2, -2));
        d.addView(drawerItem(R.drawable.ic_discord, "Discord", () -> openUrl(URL_DISCORD)));
        d.addView(drawerItem(R.drawable.ic_github, "GitHub", () -> openUrl(URL_GITHUB)));
        d.addView(drawerItem(R.drawable.ic_telegram, "Telegram", () -> openUrl(URL_TELEGRAM)));
    }

    private View drawerItem(int icon, String label, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        row.setBackground(Ui.ripple(0, 12));
        row.setClickable(true);
        row.addView(Ui.icon(this, icon, Theme.PRIMARY), Ui.lp(Ui.dp(26), Ui.dp(26)));
        LinearLayout.LayoutParams tl = Ui.lp(-2, -2);
        tl.leftMargin = Ui.dp(20);
        row.addView(Ui.text(this, label, 16, Theme.ON, false), tl);
        row.setOnClickListener(v -> {
            drawerLayout.close();
            action.run();
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            M3.toast(this, "Tidak bisa membuka tautan");
        }
    }

    // ------------------------------------------------------------ folder & recent

    private void rebuildFolders() {
        Set<String> set = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Library.Song s : Library.songs) {
            set.add(s.folder);
            Integer c = counts.get(s.folder);
            counts.put(s.folder, c == null ? 1 : c + 1);
        }
        folders.clear();
        folders.addAll(set);
        Collections.sort(folders, String.CASE_INSENSITIVE_ORDER);
        if (selectedFolder != null && !set.contains(selectedFolder)) selectedFolder = null;
        folderCounts.clear();
        folderCounts.putAll(counts);

        pillRow.removeAllViews();
        if (Prefs.showStorageTab()) addPill(STORAGE, "Storage");
        addPill(ALL, "Semua");
        addPill(ONLINE, "Online");
        for (String f : folders) addPill(f, Library.folderName(f));
        restylePills();
    }

    private void addPill(final String key, String label) {
        TextView t = Ui.text(this, label, 14, Theme.ON, false);
        t.setTag(key);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        t.setOnClickListener(v -> selectFolder(key));
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(8);
        pillRow.addView(t, lp);
    }

    private void restylePills() {
        for (int i = 0; i < pillRow.getChildCount(); i++) {
            TextView t = (TextView) pillRow.getChildAt(i);
            String key = (String) t.getTag();
            boolean sel = onlineMode ? ONLINE.equals(key)
                    : storageMode ? STORAGE.equals(key)
                    : (selectedFolder == null ? ALL.equals(key) : key.equals(selectedFolder));
            t.setBackground(Ui.ripple(sel ? Theme.PRIMARY : Theme.SURFACE_HIGH, 18));
            t.setTextColor(sel ? Theme.ON_PRIMARY : Theme.ON);
        }
    }

    /** Pilih tab: STORAGE (daftar folder), ALL (semua lagu), atau path folder tertentu. */
    private void selectFolder(String key) {
        onlineMode = ONLINE.equals(key);
        storageMode = STORAGE.equals(key);
        selectedFolder = onlineMode || storageMode || ALL.equals(key) ? null : key;
        searchEdit.setHint(onlineMode ? "Cari lagu online" : "Cari lagu");
        if (onlineMode && (onlineResults.isEmpty() || !query.equals(onlineQuery == null ? "" : onlineQuery))) {
            uiHandler.removeCallbacks(searchRunnable);
            runOnlineSearch(false, false);
        }
        applyFilter();
        applyGridMode();
        restylePills();
    }

    // ------------------------------------------------------------ pencarian online

    private void scheduleOnlineSearch() {
        uiHandler.removeCallbacks(searchRunnable);
        uiHandler.postDelayed(searchRunnable, 700);
    }

    /** Cari online sesuai kolom pencarian; kolom kosong menampilkan daftar populer. */
    /** Cari online. more=false: halaman pertama (fresh = melewati cache). more=true: halaman berikutnya. */
    private void runOnlineSearch(final boolean more, final boolean fresh) {
        if (!onlineMode) return;
        if (more && (onlineFetching || onlineExhausted || onlineResults.isEmpty())) return;
        final String q = query;
        if (!more) {
            onlinePages.clear();
            onlinePage = 0;
            onlineExhausted = false;
            onlineResults.clear();
            if (fresh && q.isEmpty()) trendBase++;       // Populer: tarik-untuk-muat pindah ke bagian chart lain
            searchSeq++;
            onlineError = null;
        }
        final int seq = searchSeq;
        final int idx = more ? onlinePage + 1 : 0;
        final int page = idx + (q.isEmpty() ? trendBase * 10 : 0);
        onlineQuery = q;
        onlineFetching = true;
        onlineLoading = !more;
        applyFilter();
        Online.fetch(this, q, page, fresh, (songs, err, done) -> {
            if (seq != searchSeq) return;               // sudah ada pencarian baru
            onlinePages.put(idx, songs);
            onlineError = err;
            if (done) {
                onlineFetching = false;
                onlineLoading = false;
                if (more) {
                    if (songs.isEmpty()) onlineExhausted = true;
                    else onlinePage = idx;
                }
            }
            rebuildOnlineList();
            if (onlineMode) applyFilter();
        });
    }

    /** Gabung semua halaman berurutan, tanpa lagu yang sama dua kali. */
    private void rebuildOnlineList() {
        onlineResults.clear();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<Integer> keys = new ArrayList<>(onlinePages.keySet());
        java.util.Collections.sort(keys);
        for (int k : keys) {
            for (Library.Song s : onlinePages.get(k)) {
                if (seen.add(s.id)) onlineResults.add(s);
            }
        }
    }

    private void applyOnline() {
        visible.clear();
        visible.addAll(onlineResults);
        listAdapter.setData(visible);
        gridAdapter.setData(visible);
        boolean waiting = onlineLoading && onlineResults.isEmpty();
        listAdapter.setPlaceholders(waiting ? 8 : 0);
        gridAdapter.setPlaceholders(waiting ? 8 : 0);
        if (waiting) {
            subtitle.setText("Mencari…");
            emptyText.setVisibility(View.GONE);
        } else if (onlineResults.isEmpty()) {
            subtitle.setText("Online");
            updateEmpty(onlineError != null ? onlineError + ".\nTarik ke bawah untuk mencoba lagi."
                    : query.isEmpty() ? "Ketik judul atau artis untuk mencari lagu online."
                    : "Lagu tidak ditemukan.");
        } else {
            subtitle.setText((query.isEmpty() ? "Populer · " : "") + visible.size() + " lagu online"
                    + (onlineLoading ? " · memuat sumber lain…" : ""));
            emptyText.setVisibility(View.GONE);
        }
        buildRecent();
        refreshUi();
    }

    private boolean matches(Library.Song s) {
        if (query.isEmpty()) return true;
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return has(s.title, q) || has(s.artist, q) || has(s.album, q);
    }

    private static boolean has(String v, String q) {
        return v != null && v.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchEdit != null) imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
        if (searchEdit != null) searchEdit.clearFocus();
        if (focusSink != null) focusSink.requestFocus();
    }

    /** Ketuk di luar kolom pencarian: lepas fokus dan tutup keyboard. Sentuhan tetap diteruskan. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                && searchEdit != null && searchBox != null && searchEdit.hasFocus()) {
            int[] loc = new int[2];
            searchBox.getLocationOnScreen(loc);
            float x = ev.getRawX(), y = ev.getRawY();
            boolean inside = x >= loc[0] && x <= loc[0] + searchBox.getWidth()
                    && y >= loc[1] && y <= loc[1] + searchBox.getHeight();
            if (!inside) hideKeyboard();
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyFilter() {
        if (onlineMode) {
            applyOnline();
            return;
        }
        visible.clear();
        for (Library.Song s : Library.songs) {
            if ((selectedFolder == null || selectedFolder.equals(s.folder)) && matches(s)) visible.add(s);
        }
        listAdapter.setData(visible);
        gridAdapter.setData(visible);

        List<String> shownFolders = new ArrayList<>();
        for (String f : folders) {
            if (query.isEmpty() || has(Library.folderName(f), query.toLowerCase(java.util.Locale.ROOT))) shownFolders.add(f);
        }
        folderAdapter.setData(shownFolders, folderCounts);

        if (storageMode) {
            subtitle.setText(shownFolders.size() + " folder");
            if (shownFolders.isEmpty()) updateEmpty(query.isEmpty() ? "Tidak ada lagu di perangkat." : "Folder tidak ditemukan.");
            else emptyText.setVisibility(View.GONE);
        } else {
            subtitle.setText(visible.size() + " lagu");
            if (Library.songs.isEmpty()) updateEmpty("Tidak ada lagu di perangkat.");
            else if (visible.isEmpty()) updateEmpty(query.isEmpty() ? "Folder ini kosong."
                    : "Lagu tidak ditemukan.\nTekan Cari di keyboard untuk mencari online.");
            else emptyText.setVisibility(View.GONE);
        }

        buildRecent();
        refreshUi();
    }

    /** Recent music: N lagu terbaru di folder yang sedang dipilih (N dari Pengaturan). */
    private void buildRecent() {
        recentRow.removeAllViews();
        // Sejarah dimatikan: seluruh area Recent music disembunyikan (label dan barisnya).
        boolean on = Prefs.showHistory();
        List<Library.Song> recent = on ? recentSongs() : new ArrayList<Library.Song>();
        int n = Math.min(Prefs.recentCount(), recent.size());
        int vis = on && n > 0 && !storageMode && !onlineMode && query.isEmpty() ? View.VISIBLE : View.GONE;
        recentLabel.setVisibility(vis);
        recentScroll.setVisibility(vis);
        for (int i = 0; i < n; i++) recentRow.addView(recentCard(recent.get(i)), recentParams());
    }

    /**
     * Daftar untuk Recent music. Sejarah aktif: lagu yang pernah diputar (terbaru di depan), sesuai folder
     * yang dipilih. Nonaktif: lagu yang terbaru ditambahkan ke perangkat.
     */
    private List<Library.Song> recentSongs() {
        List<Library.Song> out = new ArrayList<>();
        for (long id : History.ids(this)) {
            Library.Song s = Library.find(this, id);
            if (s == null) continue;
            if (s.isOnline()) {
                // Lagu online yang pernah diputar tampil di "Semua" saja
                if (selectedFolder == null) out.add(s);
                continue;
            }
            for (Library.Song v : visible) {
                if (v.id == id) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    private LinearLayout.LayoutParams recentParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(112), -2);
        p.rightMargin = Ui.dp(10);
        return p;
    }

    private View recentCard(final Library.Song s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        card.setBackground(Ui.ripple(0, 14));
        card.setClickable(true);

        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rect(Theme.SURFACE_HIGH, 14));
        Ui.round(art, 14);
        card.addView(art, Ui.lp(Ui.dp(104), Ui.dp(104)));

        TextView t = Ui.text(this, s.title, 12, Theme.ON, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t, Ui.lp(-1, -2, 0, Ui.dp(6), 0, 0));

        Drawable ph = Ui.tinted(this, R.drawable.ic_note, Theme.ON_VAR);
        Art.bind(art, s, ph, Ui.dp(24));
        card.setOnClickListener(v -> {
            if (s.isOnline()) {
                if (svc != null) svc.playQueue(java.util.Collections.singletonList(s), 0);
                return;
            }
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).id == s.id) {
                    playPosition(i);
                    return;
                }
            }
        });
        return card;
    }

    // ------------------------------------------------------------ data & izin

    private boolean hasAudio() {
        return checkSelfPermission(PERM_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasAudio()) need.add(PERM_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(PERM_NOTIF) != PackageManager.PERMISSION_GRANTED) {
            need.add(PERM_NOTIF);
        }
        return need.toArray(new String[0]);
    }

    private void loadLibrary() {
        if (Library.loaded) {
            applySongs();
            return;
        }
        subtitle.setText("Memuat lagu…");
        Library.load(this, songs -> applySongs());
    }

    /** Dipakai tarik-untuk-refresh dan Pengaturan: pindai ulang lalu hentikan indikator. */
    private void reloadLibrary() {
        if (onlineMode) {
            runOnlineSearch(false, true);
            swipe.setRefreshing(false);
            return;
        }
        if (!hasAudio()) {
            swipe.setRefreshing(false);
            showPermissionSheet();
            return;
        }
        Library.loaded = false;
        subtitle.setText("Memuat ulang…");
        Library.load(this, songs -> {
            applySongs();
            swipe.setRefreshing(false);
        });
    }

    private void applySongs() {
        rebuildFolders();
        applyFilter();
    }

    private void updateEmpty(String msg) {
        emptyText.setText(msg);
        emptyText.setVisibility(View.VISIBLE);
    }

    /** Bottom sheet izin saat startup. Tombol "Izinkan" memunculkan dialog sistem. */
    private void showPermissionSheet() {
        final String[] need = missingPermissions();
        if (need.length == 0) {
            loadLibrary();
            return;
        }
        final Sheet s = new Sheet(this, true);
        LinearLayout c = s.panel;

        ImageView badge = Ui.icon(this, R.drawable.ic_headphones, Theme.PRIMARY);
        badge.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
        badge.setBackground(Ui.rect(Theme.PRIM_CONT, 20));
        c.addView(badge, Ui.lp(Ui.dp(64), Ui.dp(64)));

        c.addView(Ui.text(this, "Izinkan akses musik", 22, Theme.ON, true),
                Ui.lp(-2, -2, 0, Ui.dp(16), 0, Ui.dp(8)));
        c.addView(Ui.text(this,
                        "Music perlu membaca file audio di perangkatmu untuk menampilkan daftar lagu. "
                                + "Izin notifikasi dipakai untuk kontrol pemutaran di bilah notifikasi.",
                        15, Theme.ON_VAR, false),
                Ui.lp(-1, -2, 0, 0, 0, Ui.dp(20)));
        c.addView(Ui.button(this, "Izinkan", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
                    s.dismiss();
                    requestPermissions(need, REQ_PERM);
                }),
                Ui.lp(-1, Ui.dp(52)));
        c.addView(Ui.button(this, "Nanti", 0, Theme.PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52), 0, Ui.dp(8), 0, 0));
        present(s);
    }

    /** Bottom sheet equalizer, dibuka dari tombol tune di bar atas. */
    private void showEqSheet() {
        Sheet s = new Sheet(this, true);
        s.panel.addView(Ui.text(this, "Equalizer", 22, Theme.ON, true), Ui.lp(-2, -2, 0, Ui.dp(8), 0, Ui.dp(12)));
        s.panel.addView(EqUi.build(this), Ui.lp(-1, -2));
        s.panel.addView(Ui.button(this, "Tutup", Theme.PRIMARY, Theme.ON_PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52), 0, Ui.dp(16), 0, 0));
        present(s);
    }

    private void showInfo() {
        Sheet s = new Sheet(this, true);
        String ver = "-";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }

        s.panel.addView(Ui.text(this, "Music", 22, Theme.ON, true), Ui.lp(-2, -2, 0, Ui.dp(8), 0, Ui.dp(12)));
        s.panel.addView(Ui.text(this, "Versi " + ver, 15, Theme.ON_VAR, false), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(6)));
        s.panel.addView(Ui.text(this, "Lagu di perangkat: " + Library.songs.size(), 15, Theme.ON_VAR, false),
                Ui.lp(-2, -2, 0, 0, 0, Ui.dp(6)));
        s.panel.addView(Ui.text(this,
                        "Memutar musik di perangkat dan streaming online (SoundCloud, Audius, YouTube) tanpa API key. Kontrol muncul di notifikasi saat musik diputar.",
                        14, Theme.ON_VAR, false),
                Ui.lp(-1, -2, 0, 0, 0, Ui.dp(16)));
        s.panel.addView(Ui.button(this, "Tutup", Theme.PRIMARY, Theme.ON_PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52)));
        present(s);
    }

    /** Tampilkan sheet dan catat sebagai sheet yang sedang terbuka. */
    private void present(final Sheet s) {
        openSheet = s;
        s.setOnDismiss(() -> {
            if (openSheet == s) openSheet = null;
        });
        s.show();
    }

    // ------------------------------------------------------------ playback

    private void playPosition(int pos) {
        if (svc == null) return;
        svc.playQueue(visible, pos);
        refreshUi();
    }

    /** Sinkronkan baris yang diputar dan panel pemutar dengan service. */
    private void refreshUi() {
        if (listAdapter == null) return;
        Library.Song s = svc == null ? null : svc.current();
        long id = s == null ? -1 : s.id;
        // Lagu berganti (termasuk otomatis dari service): perbarui Recent bila sejarah aktif.
        if (id != lastRecentPlaying) {
            lastRecentPlaying = id;
            if (Prefs.showHistory()) buildRecent();
        }
        listAdapter.setPlayingId(id);
        gridAdapter.setPlayingId(id);
        if (panel != null) panel.refresh();
    }
}
