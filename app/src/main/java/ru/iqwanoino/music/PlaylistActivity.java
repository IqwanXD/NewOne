package ru.iqwanoino.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Halaman Playlist. Tanpa extra = daftar playlist. Dengan EXTRA_ID = isi satu playlist.
 * Pemutar tampil sebagai overlay di bawah, sama seperti di Home.
 */
public class PlaylistActivity extends Activity {

    static final String EXTRA_ID = "playlist_id";

    private long openId = -1;
    private String shown = "";
    private LinearLayout header;
    private TextView titleTv, subTv, emptyTv;
    private ImageView actA, actB;
    private ListView list;
    private PlayerPanel panel;
    private MusicService svc;
    private boolean bound;
    private final List<Library.Song> songs = new ArrayList<>();
    private SongAdapter songAdapter;
    private PlaylistAdapter plAdapter;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((MusicService.LocalBinder) binder).get();
            if (panel != null) panel.attach(svc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            if (panel != null) panel.attach(null);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Theme.load(this);
        shown = M3.paletteKey;
        openId = getIntent().getLongExtra(EXTRA_ID, -1);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(this, R.drawable.ic_back, Theme.PRIMARY, 48, this::finish),
                Ui.lp(Ui.dp(48), Ui.dp(48)));

        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        titleTv = Ui.text(this, "Playlist", 22, Theme.ON, true);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subTv = Ui.text(this, "", 13, Theme.ON_VAR, false);
        tt.addView(titleTv, Ui.lp(-1, -2));
        tt.addView(subTv, Ui.lp(-1, -2));
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(0, -2, 1f);
        ttl.leftMargin = Ui.dp(8);
        header.addView(tt, ttl);

        actB = Ui.iconButton(this, R.drawable.ic_delete, Theme.ON, 48, null);
        actA = Ui.iconButton(this, R.drawable.ic_add, Theme.ON, 48, null);
        header.addView(actB, Ui.lp(Ui.dp(48), Ui.dp(48)));
        header.addView(actA, Ui.lp(Ui.dp(48), Ui.dp(48)));
        col.addView(header, Ui.lp(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        Ui.fade(list);
        FrameLayout listHost = new FrameLayout(this);
        listHost.addView(list, new FrameLayout.LayoutParams(-1, -1));
        ScrollThumb.attach(list, listHost);
        col.addView(listHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        emptyTv = Ui.text(this, "", 15, Theme.ON_VAR, false);
        emptyTv.setGravity(Gravity.CENTER);
        emptyTv.setPadding(Ui.dp(32), 0, Ui.dp(32), 0);
        emptyTv.setVisibility(View.GONE);
        root.addView(emptyTv, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        // Pemutar sebagai overlay di atas daftar, sama seperti di Home
        panel = new PlayerPanel(this);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        Ui.setupEdge(this);   // setelah setContentView, sama seperti Pinpic
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            applyInsets();
            return ins;
        });
        applyInsets();

        startService(new Intent(this, MusicService.class));
        bound = bindService(new Intent(this, MusicService.class), conn, Context.BIND_AUTO_CREATE);

        if (openId < 0) showList();
        else if (Library.loaded) showDetail();
        else Library.load(this, l -> showDetail());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!M3.paletteKey.equals(shown)) {
            recreate();
            return;
        }
        if (openId < 0) showList();
        else if (Library.loaded) showDetail();
    }

    @Override
    protected void onDestroy() {
        if (bound) unbindService(conn);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panel != null && panel.backStep()) return;
        super.onBackPressed();
    }

    private void applyInsets() {
        header.setPadding(Ui.dp(8), Ui.insetTop + Ui.dp(8), Ui.dp(8), Ui.dp(8));
        list.setPadding(Ui.dp(12), 0, Ui.dp(12), Ui.insetBottom + Ui.dp(PlayerPanel.MINI_DP) + Ui.dp(16));
        if (panel != null) panel.applyInsets();
    }

    // ------------------------------------------------------------ daftar playlist

    private void showList() {
        titleTv.setText("Playlist");
        actB.setVisibility(View.GONE);
        actA.setVisibility(View.VISIBLE);
        actA.setImageDrawable(Ui.tinted(this, R.drawable.ic_add, Theme.PRIMARY));
        actA.setOnClickListener(v -> Playlists.createSheet(this, null, this::showList));

        if (plAdapter == null) plAdapter = new PlaylistAdapter();
        List<Playlists.Playlist> all = Playlists.all(this);
        plAdapter.setData(all);
        list.setAdapter(plAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> startActivity(
                new Intent(this, PlaylistActivity.class).putExtra(EXTRA_ID, plAdapter.getItem(pos).id)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Playlists.deleteSheet(this, plAdapter.getItem(pos), this::showList);
            return true;
        });

        subTv.setText(all.size() + " playlist");
        emptyTv.setText("Belum ada playlist.\nKetuk + untuk membuat.");
        emptyTv.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ isi satu playlist

    private void showDetail() {
        final Playlists.Playlist p = Playlists.find(this, openId);
        if (p == null) {
            finish();
            return;
        }
        titleTv.setText(p.name);

        songs.clear();
        for (Long id : p.songs) {
            Library.Song s = Library.find(this, id);      // lokal atau online
            if (s != null) songs.add(s);
        }
        subTv.setText(songs.size() + " lagu");

        if (songAdapter == null) songAdapter = new SongAdapter(this, false, 0);
        songAdapter.setData(songs);
        list.setAdapter(songAdapter);
        list.setOnItemClickListener((pa, v, pos, id) -> playAt(pos));
        list.setOnItemLongClickListener((pa, v, pos, id) -> {
            Library.Song s = songs.get(pos);
            Playlists.removeSong(this, p.id, s.id);
            M3.toast(this, "Dihapus dari playlist");
            showDetail();
            return true;
        });

        actB.setVisibility(View.VISIBLE);
        actB.setImageDrawable(Ui.tinted(this, R.drawable.ic_delete, Theme.PRIMARY));
        actB.setOnClickListener(v -> Playlists.deleteSheet(this, p, this::finish));
        actA.setVisibility(View.VISIBLE);
        actA.setImageDrawable(Ui.tinted(this, R.drawable.ic_play, Theme.PRIMARY));
        actA.setOnClickListener(v -> playAt(0));

        emptyTv.setText("Playlist kosong.\nTekan lama lagu di daftar utama untuk menambah.");
        emptyTv.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void playAt(int pos) {
        if (svc == null || songs.isEmpty()) return;
        svc.playQueue(songs, pos);
    }

    // ------------------------------------------------------------ adapter daftar playlist

    private final class PlaylistAdapter extends BaseAdapter {
        private final List<Playlists.Playlist> data = new ArrayList<>();

        void setData(List<Playlists.Playlist> d) {
            data.clear();
            data.addAll(d);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }

        @Override public Playlists.Playlist getItem(int p) { return data.get(p); }

        @Override public long getItemId(int p) { return data.get(p).id; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            Playlists.Playlist pl = data.get(p);
            LinearLayout row = new LinearLayout(PlaylistActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            row.setBackground(Ui.ripple(0, 16));

            ImageView icon = new ImageView(PlaylistActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
            icon.setBackground(Ui.rect(Theme.SURFACE_HIGH, 14));
            icon.setImageDrawable(Ui.tinted(PlaylistActivity.this, R.drawable.ic_queue_music, Theme.ON_VAR));
            row.addView(icon, Ui.lp(Ui.dp(52), Ui.dp(52)));

            LinearLayout texts = new LinearLayout(PlaylistActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(PlaylistActivity.this, pl.name, 16, Theme.ON, false), Ui.lp(-1, -2));
            texts.addView(Ui.text(PlaylistActivity.this, pl.songs.size() + " lagu", 13, Theme.ON_VAR, false),
                    Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.leftMargin = Ui.dp(14);
            row.addView(texts, tl);
            return row;
        }
    }
}
