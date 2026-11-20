package ru.iqwanoino.music;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Daftar folder untuk tab Storage: ikon folder, nama folder, jumlah lagu, dan path. */
final class FolderAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<String> data = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();

    FolderAdapter(Context c) {
        ctx = c;
    }

    void setData(List<String> folders, Map<String, Integer> songCounts) {
        data.clear();
        data.addAll(folders);
        counts.clear();
        counts.putAll(songCounts);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return data.size(); }

    @Override public String getItem(int p) { return data.get(p); }

    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int p, View convert, ViewGroup parent) {
        Holder h;
        if (convert == null) {
            h = new Holder(ctx);
            convert = h.root;
            convert.setTag(h);
        } else {
            h = (Holder) convert.getTag();
        }
        String f = data.get(p);
        Integer n = counts.get(f);
        h.name.setText(Library.folderName(f));
        h.sub.setText((n == null ? 0 : n) + " lagu  \u00b7  " + f);
        return convert;
    }

    private static final class Holder {
        final LinearLayout root;
        final TextView name, sub;

        Holder(Context c) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            root.setBackground(Ui.ripple(0, 16));

            ImageView icon = Ui.icon(c, R.drawable.ic_folder, Theme.PRIMARY);
            icon.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
            icon.setBackground(Ui.rect(Theme.PRIM_CONT, 14));
            root.addView(icon, Ui.lp(Ui.dp(52), Ui.dp(52)));

            name = Ui.text(c, "", 16, Theme.ON, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            sub = Ui.text(c, "", 13, Theme.ON_VAR, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(name, Ui.lp(-1, -2));
            col.addView(sub, Ui.lp(-1, -2, 0, Ui.dp(2), 0, 0));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.leftMargin = Ui.dp(14);
            root.addView(col, cl);
        }
    }
}
