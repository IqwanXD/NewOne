package ru.iqwanoino.music;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Placeholder berkilau untuk daftar yang sedang dimuat. Animasi hanya berjalan saat view tampil. */
final class Shimmer {

    private static final int SWEEP_DP = 140;

    private Shimmer() { }

    /** Satu baris (mode list) atau satu kotak (mode grid). */
    static View item(Context c, boolean grid, int tile, int art) {
        if (grid) {
            int tw = Math.round(tile * 0.7f);
            int sw = Math.round(tile * 0.45f);
            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(8));
            root.setMinimumWidth(tile);
            root.addView(block(c, 14), Ui.lp(art, art));
            root.addView(block(c, 6), Ui.lp(tw, Ui.dp(14), 0, Ui.dp(10), 0, 0));
            root.addView(block(c, 6), Ui.lp(sw, Ui.dp(12), 0, Ui.dp(6), 0, 0));
            return root;
        }
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        row.addView(block(c, 14), Ui.lp(Ui.dp(52), Ui.dp(52)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(block(c, 6), Ui.lp(-1, Ui.dp(14)));
        col.addView(block(c, 6), Ui.lp(Ui.dp(110), Ui.dp(12), 0, Ui.dp(8), 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = Ui.dp(14);
        row.addView(col, cl);
        return row;
    }

    /** Kotak abu-abu dengan kilatan yang bergerak dari kiri ke kanan. */
    static FrameLayout block(final Context c, float radiusDp) {
        final FrameLayout b = new FrameLayout(c);
        b.setBackground(Ui.rect(Theme.SURFACE_HIGH, radiusDp));
        Ui.round(b, radiusDp);

        View sweep = new View(c);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{
                0x00000000, (0x1F << 24) | (Theme.ON & 0x00FFFFFF), 0x00000000}));
        b.addView(sweep, new FrameLayout.LayoutParams(Ui.dp(SWEEP_DP), -1));

        final int screen = c.getResources().getDisplayMetrics().widthPixels;
        final ObjectAnimator anim = ObjectAnimator.ofFloat(sweep, View.TRANSLATION_X, -Ui.dp(SWEEP_DP), screen);
        anim.setDuration(1200);
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        b.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                anim.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                anim.cancel();
            }
        });
        return b;
    }
}
