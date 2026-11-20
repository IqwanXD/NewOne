package ru.iqwanoino.music;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Tab Beranda: sapaan, streak, cepat putar (2 kolom), lanjutkan (carousel), dan andalanmu (3 kolom).
 * Isinya diambil dari riwayat putar; kalau belum ada riwayat, memakai daftar lagu di perangkat.
 */
final class HomePage extends ScrollView {

    interface Player {
        void play(List<Library.Song> songs, int position);
    }

    private final Activity act;
    private final Player player;
    private final LinearLayout col;

    HomePage(Activity a, Player p) {
        super(a);
        act = a;
        player = p;
        setVerticalScrollBarEnabled(false);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -2));
    }

    void refresh() {
        col.removeAllViews();
        col.setPadding(0, Ui.insetTop + Ui.dp(16), 0, Ui.dp(140));

        List<Library.Song> recent = recentSongs(16);
        if (recent.isEmpty()) recent = fallbackSongs(16);

        col.addView(Ui.text(act, greeting(), 28, Theme.ON, true), Ui.lp(-2, -2, Ui.dp(20), 0, Ui.dp(20), Ui.dp(14)));
        col.addView(streakCard(), Ui.lp(-1, -2, Ui.dp(20), 0, Ui.dp(20), 0));

        if (recent.isEmpty()) {
            TextView empty = Ui.text(act, "Belum ada lagu. Buka tab Pustaka untuk memuat musik.", 15, Theme.ON_VAR, false);
            empty.setPadding(Ui.dp(20), Ui.dp(32), Ui.dp(20), 0);
            col.addView(empty, Ui.lp(-1, -2));
            return;
        }

        // Cepat putar: enam lagu terakhir, dua kolom
        col.addView(section("Cepat putar"), Ui.lp(-1, -2));
        List<Library.Song> picks = new ArrayList<>(recent.subList(0, Math.min(6, recent.size())));
        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
        for (int r = 0; r < picks.size(); r += 2) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                int i = r + k;
                if (i < picks.size()) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                    lp.rightMargin = k == 0 ? Ui.dp(8) : 0;
                    row.addView(quickCard(picks, i), lp);
                } else {
                    row.addView(new View(act), new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            grid.addView(row, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(8)));
        }
        col.addView(grid, Ui.lp(-1, -2));

        // Lanjutkan: carousel kartu besar
        col.addView(section("Lanjutkan"), Ui.lp(-1, -2));
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(20), 0, Ui.dp(8), 0);
        for (int i = 0; i < Math.min(12, recent.size()); i++) {
            row.addView(bigCard(recent, i), Ui.lp(Ui.dp(148), -2, 0, 0, Ui.dp(12), 0));
        }
        hs.addView(row);
        col.addView(hs, Ui.lp(-1, -2));

        // Andalanmu: grid tiga kolom dari lagu berikutnya
        List<Library.Song> more = recent.size() > 6 ? recent.subList(6, recent.size()) : recent;
        if (!more.isEmpty()) {
            col.addView(section("Andalanmu"), Ui.lp(-1, -2));
            int tile = (act.getResources().getDisplayMetrics().widthPixels - Ui.dp(40) - Ui.dp(24)) / 3;
            LinearLayout g = new LinearLayout(act);
            g.setOrientation(LinearLayout.VERTICAL);
            g.setPadding(Ui.dp(20), 0, Ui.dp(20), 0);
            for (int r = 0; r < Math.min(9, more.size()); r += 3) {
                LinearLayout gr = new LinearLayout(act);
                gr.setOrientation(LinearLayout.HORIZONTAL);
                for (int k = 0; k < 3; k++) {
                    int i = r + k;
                    if (i < Math.min(9, more.size())) {
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, -2);
                        lp.rightMargin = k < 2 ? Ui.dp(12) : 0;
                        gr.addView(tileCard(more, i, tile), lp);
                    } else {
                        gr.addView(new View(act), new LinearLayout.LayoutParams(tile, 0));
                    }
                }
                g.addView(gr, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(14)));
            }
            col.addView(g, Ui.lp(-1, -2));
        }
    }

    // ------------------------------------------------------------ bagian

    private TextView section(String s) {
        TextView t = Ui.text(act, s, 20, Theme.ON, true);
        t.setPadding(Ui.dp(20), Ui.dp(26), Ui.dp(20), Ui.dp(12));
        return t;
    }

    private LinearLayout streakCard() {
        int days = Stats.streak(act);
        boolean today = Stats.playedToday(act);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
        card.setBackground(Ui.rect(Theme.SURFACE, 22));

        TextView num = Ui.text(act, String.valueOf(days), 30, Theme.PRIMARY, true);
        num.setPadding(0, 0, Ui.dp(14), 0);
        card.addView(num, Ui.lp(-2, -2));

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(act, days > 0 ? "hari berturut-turut" : "Mulai streakmu hari ini",
                15, Theme.ON, true), Ui.lp(-2, -2));
        texts.addView(Ui.text(act, today ? "Kamu sudah memutar musik hari ini. Pertahankan."
                : "Putar satu lagu hari ini agar streakmu lanjut.", 13, Theme.ON_VAR, false),
                Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    private LinearLayout quickCard(final List<Library.Song> list, final int idx) {
        Library.Song s = list.get(idx);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.rect(Theme.SURFACE, 14));

        ImageView art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(art, 12);
        Art.bind(art, s, Ui.tinted(act, R.drawable.ic_note, Theme.ON_VAR), Ui.dp(12));
        card.addView(art, Ui.lp(Ui.dp(56), Ui.dp(56)));

        TextView t = Ui.text(act, s.title, 14, Theme.ON, true);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Ui.dp(10);
        tl.rightMargin = Ui.dp(8);
        card.addView(t, tl);

        card.setOnClickListener(v -> player.play(list, idx));
        return card;
    }

    private LinearLayout bigCard(final List<Library.Song> list, final int idx) {
        Library.Song s = list.get(idx);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);

        ImageView art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(art, 16);
        Art.bind(art, s, Ui.tinted(act, R.drawable.ic_note, Theme.ON_VAR), Ui.dp(16));
        card.addView(art, Ui.lp(Ui.dp(148), Ui.dp(148)));

        TextView t = Ui.text(act, s.title, 14, Theme.ON, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t, Ui.lp(-1, -2, 0, Ui.dp(8), 0, 0));

        TextView a = Ui.text(act, s.artist, 12, Theme.ON_VAR, false);
        a.setSingleLine(true);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(a, Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));

        card.setOnClickListener(v -> player.play(list, idx));
        return card;
    }

    private LinearLayout tileCard(final List<Library.Song> list, final int idx, int tile) {
        Library.Song s = list.get(idx);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);

        ImageView art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.round(art, 14);
        Art.bind(art, s, Ui.tinted(act, R.drawable.ic_note, Theme.ON_VAR), Ui.dp(14));
        card.addView(art, Ui.lp(tile, tile));

        TextView t = Ui.text(act, s.title, 12, Theme.ON, true);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(t, Ui.lp(-1, -2, 0, Ui.dp(6), 0, 0));

        card.setOnClickListener(v -> player.play(list, idx));
        return card;
    }

    // ------------------------------------------------------------ data

    private List<Library.Song> recentSongs(int limit) {
        List<Library.Song> out = new ArrayList<>();
        for (long id : History.ids(act)) {
            Library.Song s = Library.find(act, id);
            if (s != null) out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static List<Library.Song> fallbackSongs(int limit) {
        List<Library.Song> out = new ArrayList<>();
        for (Library.Song s : Library.songs) {
            if (out.size() >= limit) break;
            out.add(s);
        }
        return out;
    }

    private static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 11) return "Selamat pagi";
        if (h < 15) return "Selamat siang";
        if (h < 18) return "Selamat sore";
        return "Selamat malam";
    }
}
