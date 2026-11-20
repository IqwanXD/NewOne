package ru.iqwanoino.music;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Adapter ringan untuk mode list maupun grid. View didaur ulang, artwork lazy. */
final class SongAdapter extends BaseAdapter {

    private final Context ctx;
    private final boolean grid;
    private int tile;   // lebar kolom grid (px)
    private int art;    // sisi kotak artwork (px)
    private final List<Library.Song> data = new ArrayList<>();
    private final Drawable placeholder;
    private long playingId = -1;
    private int placeholders;

    SongAdapter(Context c, boolean grid, int tilePx) {
        ctx = c;
        this.grid = grid;
        tile = tilePx;
        art = Math.max(0, tilePx - Ui.dp(12));
        placeholder = Ui.tinted(c, R.drawable.ic_note, Theme.ON_VAR);
    }

    void setData(List<Library.Song> list) {
        data.clear();
        data.addAll(list);
        placeholders = 0;
        notifyDataSetChanged();
    }

    /** Tampilkan n placeholder shimmer (0 = matikan). Dipakai saat hasil online sedang dimuat. */
    void setPlaceholders(int n) {
        if (placeholders == n) return;
        placeholders = n;
        notifyDataSetChanged();
    }

    /** Ubah lebar kolom dan sisi artwork grid. Adapter hanya digambar ulang bila ukurannya berubah. */
    void setTile(int tilePx, int artPx) {
        if (tilePx == tile && artPx == art) return;
        tile = tilePx;
        art = artPx;
        notifyDataSetChanged();
    }

    void setPlayingId(long id) {
        if (id != playingId) {
            playingId = id;
            notifyDataSetChanged();
        }
    }

    @Override public int getCount() { return placeholders > 0 ? placeholders : data.size(); }

    @Override public Library.Song getItem(int p) { return p >= 0 && p < data.size() ? data.get(p) : null; }

    @Override public long getItemId(int p) { return p >= 0 && p < data.size() ? data.get(p).id : p; }

    @Override
    public View getView(int p, View convert, ViewGroup parent) {
        if (placeholders > 0) return Shimmer.item(ctx, grid, tile, art);
        Holder h;
        // View dari placeholder shimmer (atau view kosong) tidak punya Holder: buat baru
        if (convert == null || !(convert.getTag() instanceof Holder)) {
            h = new Holder(ctx, grid, art);
            convert = h.root;
            convert.setTag(h);
        } else {
            h = (Holder) convert.getTag();
        }

        if (grid) {
            if (h.art.getLayoutParams().width != art) h.art.setLayoutParams(Ui.lp(art, art));
            h.root.setMinimumWidth(tile);
        }

        Library.Song s = data.get(p);
        boolean playing = s.id == playingId;
        h.title.setText(s.title);
        h.title.setTextColor(playing ? Theme.PRIMARY : Theme.ON);
        h.sub.setText(s.artist + "  ·  " + Ui.time(s.durationMs) + (s.isOnline() ? "  ·  " + s.sourceName() : ""));
        h.root.setBackground(playing ? h.selBg : h.normalBg);
        Art.bind(h.art, s, placeholder, Ui.dp(grid ? 28 : 12));
        return convert;
    }

    private static final class Holder {
        final LinearLayout root;
        final ImageView art;
        final TextView title, sub;
        final Drawable normalBg, selBg;

        Holder(Context c, boolean grid, int artPx) {
            normalBg = Ui.ripple(0, 16);
            selBg = Ui.ripple(Theme.ROW_SEL, 16);

            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Ui.rect(Theme.SURFACE_HIGH, 14));
            Ui.round(art, 14);

            title = Ui.text(c, "", grid ? 14 : 16, Theme.ON, false);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = Ui.text(c, "", grid ? 12 : 13, Theme.ON_VAR, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);

            if (grid) {
                root = new LinearLayout(c);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8));
                root.setGravity(Gravity.CENTER_HORIZONTAL);
                title.setGravity(Gravity.CENTER);
                sub.setGravity(Gravity.CENTER);
                root.addView(art, Ui.lp(artPx, artPx));
                root.addView(title, Ui.lp(-1, -2, 0, Ui.dp(8), 0, 0));
                root.addView(sub, Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
            } else {
                root = new LinearLayout(c);
                root.setOrientation(LinearLayout.HORIZONTAL);
                root.setGravity(Gravity.CENTER_VERTICAL);
                root.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
                root.addView(art, Ui.lp(Ui.dp(52), Ui.dp(52)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.addView(title, Ui.lp(-1, -2));
                col.addView(sub, Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
                cl.leftMargin = Ui.dp(14);
                root.addView(col, cl);
            }
        }
    }
}
