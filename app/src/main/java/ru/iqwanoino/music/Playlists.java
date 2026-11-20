package ru.iqwanoino.music;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Playlist disimpan di SharedPreferences sebagai JSON: id, nama, dan daftar id lagu. */
final class Playlists {

    static final class Playlist {
        final long id;
        String name;
        final List<Long> songs;

        Playlist(long id, String name, List<Long> songs) {
            this.id = id;
            this.name = name;
            this.songs = songs;
        }
    }

    private static final String KEY = "playlists";

    private Playlists() { }

    static synchronized List<Playlist> all(Context c) {
        List<Playlist> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(Theme.prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                List<Long> ids = new ArrayList<>();
                JSONArray s = o.getJSONArray("songs");
                for (int j = 0; j < s.length(); j++) ids.add(s.getLong(j));
                out.add(new Playlist(o.getLong("id"), o.getString("name"), ids));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void saveAll(Context c, List<Playlist> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Playlist p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                JSONArray s = new JSONArray();
                for (Long id : p.songs) s.put(id);
                o.put("songs", s);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        Theme.prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    static Playlist find(Context c, long id) {
        for (Playlist p : all(c)) if (p.id == id) return p;
        return null;
    }

    static Playlist create(Context c, String name) {
        List<Playlist> list = all(c);
        Playlist p = new Playlist(System.currentTimeMillis(), name, new ArrayList<Long>());
        list.add(p);
        saveAll(c, list);
        return p;
    }

    static void delete(Context c, long id) {
        List<Playlist> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id == id) list.remove(i);
        saveAll(c, list);
    }

    static void addSong(Context c, long playlistId, long songId) {
        List<Playlist> list = all(c);
        for (Playlist p : list) {
            if (p.id == playlistId && !p.songs.contains(songId)) p.songs.add(songId);
        }
        saveAll(c, list);
    }

    static void removeSong(Context c, long playlistId, long songId) {
        List<Playlist> list = all(c);
        for (Playlist p : list) {
            if (p.id == playlistId) p.songs.remove(Long.valueOf(songId));
        }
        saveAll(c, list);
    }

    // ------------------------------------------------------------ sheet

    /** Sheet "Tambah ke playlist" untuk satu lagu. */
    static void pickSheet(final Activity a, final Library.Song song) {
        OnlineStore.put(a, song);      // lagu online disimpan agar playlist bisa menampilkannya lagi
        final Sheet s = new Sheet(a, true);
        LinearLayout c = s.panel;
        c.addView(Ui.text(a, "Tambah ke playlist", 20, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(12)));
        for (final Playlist p : all(a)) {
            c.addView(row(a, p.name, p.songs.size() + " lagu", () -> {
                addSong(a, p.id, song.id);
                toast(a, "Ditambahkan ke " + p.name);
                s.dismiss();
            }));
        }
        if (song.isOnline()) {
            c.addView(row(a, "Unduh ke Download/Music", "Simpan file audio ke penyimpanan", () -> {
                s.dismiss();
                Downloader.save(a, song);
            }));
        }
        c.addView(row(a, "Playlist baru", "Buat playlist dan tambahkan lagu ini", () -> {
            s.dismiss();
            createSheet(a, song, null);
        }));
        s.show();
    }

    /** Sheet buat playlist. song boleh null (hanya membuat). */
    static void createSheet(final Activity a, final Library.Song song, final Runnable after) {
        OnlineStore.put(a, song);
        final Sheet s = new Sheet(a, true);
        LinearLayout c = s.panel;
        c.addView(Ui.text(a, "Playlist baru", 20, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(12)));

        final InoInputLayout input = new InoInputLayout(a);
        input.setLabelText("Nama playlist");
        input.setColors(Theme.PRIMARY, Theme.ON_VAR);
        input.setTextColor(Theme.ON);
        c.addView(input, Ui.lp(-1, -2));

        c.addView(Ui.button(a, "Buat", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
            String name = input.getText().trim();
            if (TextUtils.isEmpty(name)) {
                toast(a, "Nama tidak boleh kosong");
                return;
            }
            Playlist p = create(a, name);
            if (song != null) addSong(a, p.id, song.id);
            toast(a, "Playlist \"" + name + "\" dibuat");
            s.dismiss();
            if (after != null) after.run();
        }), Ui.lp(-1, Ui.dp(52), 0, Ui.dp(16), 0, 0));
        s.show();
    }

    /** Konfirmasi hapus playlist. */
    static void deleteSheet(final Activity a, final Playlist p, final Runnable after) {
        final Sheet s = new Sheet(a, true);
        LinearLayout c = s.panel;
        c.addView(Ui.text(a, "Hapus playlist?", 20, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(8)));
        c.addView(Ui.text(a, "\"" + p.name + "\" akan dihapus. Lagu di perangkat tidak ikut terhapus.",
                15, Theme.ON_VAR, false), Ui.lp(-1, -2, 0, 0, 0, Ui.dp(16)));
        c.addView(Ui.button(a, "Hapus", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
            delete(a, p.id);
            s.dismiss();
            if (after != null) after.run();
        }), Ui.lp(-1, Ui.dp(52)));
        c.addView(Ui.button(a, "Batal", 0, Theme.PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52), 0, Ui.dp(8), 0, 0));
        s.show();
    }

    private static View row(Activity a, String title, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(4), Ui.dp(12), Ui.dp(4), Ui.dp(12));
        row.setBackground(Ui.ripple(0, 14));
        row.setClickable(true);
        row.addView(Ui.text(a, title, 16, Theme.ON, false), Ui.lp(-1, -2));
        row.addView(Ui.text(a, sub, 13, Theme.ON_VAR, false), Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private static void toast(Context c, String msg) {
        M3.toast(c, msg);
    }
}
