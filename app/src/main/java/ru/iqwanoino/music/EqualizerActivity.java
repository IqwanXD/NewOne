package ru.iqwanoino.music;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Equalizer versi halaman penuh (dibuka dari Pengaturan). */
public class EqualizerActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Theme.load(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.iconButton(this, R.drawable.ic_back, Theme.PRIMARY, 48, this::finish),
                Ui.lp(Ui.dp(48), Ui.dp(48)));
        header.addView(Ui.text(this, "Equalizer", 22, Theme.ON, true),
                Ui.lp(-2, -2, Ui.dp(8), 0, 0, 0));
        col.addView(header, Ui.lp(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(24));
        content.addView(EqUi.build(this), Ui.lp(-1, -2));
        sv.addView(content);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        Ui.setupEdge(this);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            col.setPadding(0, Ui.insetTop, 0, Ui.insetBottom);
            return ins;
        });
    }
}
