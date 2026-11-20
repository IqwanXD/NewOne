package ru.iqwanoino.music;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bottom nav mengambang gaya pill: empat tab. Tab aktif memakai pil tonal (PRIM_CONT). */
final class BottomNav extends LinearLayout {

    interface Listener {
        void onTab(int index);
    }

    static final int HOME = 0, SEARCH = 1, LIBRARY = 2, YOU = 3;

    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_search,
            R.drawable.ic_library, R.drawable.ic_person};
    private static final String[] LABELS = {"Beranda", "Cari", "Pustaka", "Kamu"};

    private final ImageView[] icons = new ImageView[4];
    private final FrameLayout[] pills = new FrameLayout[4];
    private final TextView[] labels = new TextView[4];
    private int selected = -1;

    BottomNav(Context c, final Listener l) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        setBackground(Ui.rect(M3.alpha(Theme.SURFACE_HIGH, 0.94f), 32));
        setElevation(Ui.dp(8));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);

            FrameLayout pill = new FrameLayout(c);
            ImageView ic = new ImageView(c);
            ic.setScaleType(ImageView.ScaleType.CENTER);
            pill.addView(ic, new FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER));
            item.addView(pill, Ui.lp(Ui.dp(56), Ui.dp(30)));

            TextView t = Ui.text(c, LABELS[i], 12, Theme.ON_VAR, false);
            t.setGravity(Gravity.CENTER);
            item.addView(t, Ui.lp(-2, -2, 0, Ui.dp(2), 0, 0));

            icons[i] = ic;
            pills[i] = pill;
            labels[i] = t;
            item.setOnClickListener(v -> {
                select(idx);
                if (l != null) l.onTab(idx);
            });
            addView(item, new LayoutParams(0, -2, 1f));
        }
        select(HOME);
    }

    /** Sorot tab aktif tanpa memanggil listener. */
    void select(int idx) {
        selected = idx;
        for (int i = 0; i < 4; i++) {
            boolean on = i == idx;
            pills[i].setBackground(on ? Ui.rect(Theme.PRIM_CONT, 16) : null);
            icons[i].setImageDrawable(Ui.tinted(getContext(), ICONS[i], on ? Theme.PRIMARY : Theme.ON_VAR));
            labels[i].setTextColor(on ? Theme.PRIMARY : Theme.ON_VAR);
            labels[i].setTypeface(labels[i].getTypeface(), on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    int selected() {
        return selected;
    }
}
