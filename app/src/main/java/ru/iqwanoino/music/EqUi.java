package ru.iqwanoino.music;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Isi equalizer: preset + slider vertikal per band. Dipakai di bottom sheet dan di Activity. */
final class EqUi {

    private EqUi() { }

    static LinearLayout build(final Activity a) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        final TextView status = Ui.text(a, "", 13, Theme.ON_VAR, false);
        col.addView(status, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(12)));

        // Band: label dB di atas, slider, label frekuensi di bawah
        final LinearLayout bands = new LinearLayout(a);
        bands.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] dbLabels = new TextView[Eq.count()];
        final BandView[] sliders = new BandView[dbLabels.length];
        for (int i = 0; i < dbLabels.length; i++) {
            final int band = i;
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            dbLabels[i] = Ui.text(a, "", 12, Theme.ON_VAR, false);
            dbLabels[i].setGravity(android.view.Gravity.CENTER);
            cell.addView(dbLabels[i], Ui.lp(-1, -2, 0, 0, 0, Ui.dp(6)));

            sliders[i] = new BandView(a, () -> { }, band);
            cell.addView(sliders[i], Ui.lp(-1, Ui.dp(200)));

            TextView hz = Ui.text(a, hzLabel(Eq.hz(i)), 11, Theme.ON_VAR, false);
            hz.setGravity(android.view.Gravity.CENTER);
            cell.addView(hz, Ui.lp(-1, -2, 0, Ui.dp(8), 0, 0));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            bands.addView(cell, lp);
        }
        col.addView(bands, Ui.lp(-1, -2));

        // Sinkronkan slider dengan nilai tersimpan (dipanggil juga setelah preset)
        final Runnable sync = () -> {
            for (int i = 0; i < sliders.length; i++) {
                int v = Eq.level(a, i);
                sliders[i].setValue(v, Eq.min(), Eq.max());
                dbLabels[i].setText(dbText(v));
            }
            status.setText(Eq.active()
                    ? "Aktif. Geser slider untuk mengatur frekuensi."
                    : "Belum aktif. Putar lagu dulu, perubahan akan langsung terasa setelah itu.");
        };
        for (int i = 0; i < sliders.length; i++) {
            final int band = i;
            sliders[i].setListener(mB -> {
                Eq.setLevel(a, band, mB);
                dbLabels[band].setText(dbText(Eq.level(a, band)));
            });
        }
        sync.run();

        // Preset
        LinearLayout presets = new LinearLayout(a);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Eq.PRESET_NAMES.length; i++) {
            final int idx = i;
            TextView b = Ui.button(a, Eq.PRESET_NAMES[i], Theme.SURFACE, Theme.ON, () -> {
                Eq.applyPreset(a, idx);
                sync.run();
            });
            b.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(44), 1f);
            lp.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            presets.addView(b, lp);
        }
        col.addView(presets, Ui.lp(-1, -2, 0, Ui.dp(20), 0, 0));
        return col;
    }

    private static String dbText(int mB) {
        float db = mB / 100f;
        return (db > 0 ? "+" : "") + (Math.round(db * 10) / 10f) + " dB";
    }

    private static String hzLabel(int hz) {
        return hz >= 1000 ? (hz / 1000) + " kHz" : hz + " Hz";
    }

    /** Slider vertikal satu band. Nilai mengikuti jari secara realtime. */
    static final class BandView extends View {
        interface Listener { void onLevel(int mB); }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int pad;
        private Listener listener;
        private int value, min = -1500, max = 1500;

        BandView(Context c, Runnable unused, int band) {
            super(c);
            pad = Ui.dp(12);
            track.setColor(0x33000000 | (Theme.ON_VAR & 0x00FFFFFF));
            fill.setColor(Theme.PRIMARY);
            thumb.setColor(Theme.PRIMARY);
            setClickable(true);
        }

        void setListener(Listener l) { listener = l; }

        void setValue(int mB, int lo, int hi) {
            min = lo;
            max = hi;
            value = Math.max(lo, Math.min(hi, mB));
            invalidate();
        }

        private float yFor(int v) {
            float h = getHeight() - 2 * pad;
            float f = (v - min) / (float) (max - min);
            return pad + h * (1f - f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float tw = Ui.dp(6);
            float top = pad, bottom = getHeight() - pad;
            rect.set(cx - tw / 2, top, cx + tw / 2, bottom);
            c.drawRoundRect(rect, tw / 2, tw / 2, track);
            float y = yFor(value);
            // Isi dari bawah sampai nilai saat ini
            rect.set(cx - tw / 2, y, cx + tw / 2, bottom);
            c.drawRoundRect(rect, tw / 2, tw / 2, fill);
            c.drawCircle(cx, y, Ui.dp(10), thumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    // fallthrough
                case MotionEvent.ACTION_MOVE: {
                    float h = getHeight() - 2 * pad;
                    float f = 1f - (e.getY() - pad) / Math.max(1f, h);
                    f = Math.max(0f, Math.min(1f, f));
                    int v = Math.round(min + f * (max - min));
                    v = Math.round(v / 50f) * 50;               // langkah 0.5 dB
                    value = Math.max(min, Math.min(max, v));
                    invalidate();
                    if (listener != null) listener.onLevel(value);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
}
