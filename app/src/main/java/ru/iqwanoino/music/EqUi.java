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

        // Efek tambahan: Bass Booster dan Volume, masing-masing knob bulat
        LinearLayout fx = new LinearLayout(a);
        fx.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout bassCol = new LinearLayout(a);
        bassCol.setOrientation(LinearLayout.VERTICAL);
        bassCol.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout bassHead = new LinearLayout(a);
        bassHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bassHead.addView(Ui.text(a, "Bass Booster", 14, Theme.ON, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final MaterialSwitch bassSwitch = new MaterialSwitch(a);
        bassSwitch.setActiveColor(Theme.PRIMARY);
        bassSwitch.setCheckedImmediate(Eq.bassVisible(a));
        bassHead.addView(bassSwitch, Ui.lp(-2, -2));
        bassCol.addView(bassHead, Ui.lp(-1, Ui.dp(48)));

        final KnobView bassKnob = new KnobView(a, 0, Eq.BASS_MAX, 10);
        bassKnob.setValue(Eq.bassStrength(a));
        bassKnob.setCenterText(percentText(Eq.bassStrength(a)));
        bassKnob.setListener(v -> {
            Eq.setBassStrength(a, v);
            bassKnob.setCenterText(percentText(v));
        });
        bassKnob.setVisibility(Eq.bassVisible(a) ? View.VISIBLE : View.GONE);
        bassCol.addView(bassKnob, Ui.lp(Ui.dp(120), Ui.dp(120), 0, Ui.dp(12), 0, 0));
        bassSwitch.setOnCheckedChangeListener((b, on) -> {
            Eq.setBassVisible(a, on);
            bassKnob.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        fx.addView(bassCol, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout volCol = new LinearLayout(a);
        volCol.setOrientation(LinearLayout.VERTICAL);
        volCol.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        TextView volLabel = Ui.text(a, "Volume", 14, Theme.ON, true);
        volLabel.setGravity(android.view.Gravity.CENTER);
        volCol.addView(volLabel, Ui.lp(-1, Ui.dp(48)));

        final KnobView volKnob = new KnobView(a, 0, Eq.LOUD_MAX, 10);
        volKnob.setValue(Eq.loudGain(a));
        volKnob.setCenterText(dbText(Eq.loudGain(a)));
        volKnob.setListener(v -> {
            Eq.setLoudGain(a, v);
            volKnob.setCenterText(dbText(v));
        });
        volCol.addView(volKnob, Ui.lp(Ui.dp(120), Ui.dp(120), 0, Ui.dp(12), 0, 0));
        fx.addView(volCol, new LinearLayout.LayoutParams(0, -2, 1f));

        col.addView(fx, Ui.lp(-1, -2, 0, Ui.dp(24), 0, 0));
        return col;
    }

    private static String dbText(int mB) {
        float db = mB / 100f;
        return (db > 0 ? "+" : "") + (Math.round(db * 10) / 10f) + " dB";
    }

    private static String percentText(int v) {
        return Math.round(v * 100f / Eq.BASS_MAX) + "%";
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

    /** Knob bulat (busur 270 derajat) untuk satu nilai, dipakai Bass Booster dan Volume. */
    static final class KnobView extends View {
        interface Listener { void onValue(int v); }

        // Derajat searah jarum jam dari arah kanan: mulai kiri-bawah, busur melewati atas
        private static final float START = 135f, SWEEP = 270f;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int min, max, step, stroke;
        private Listener listener;
        private int value;
        private String center = "";

        KnobView(Context c, int min, int max, int step) {
            super(c);
            this.min = min;
            this.max = max;
            this.step = step;
            stroke = Ui.dp(8);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(M3.alpha(Theme.ON_VAR, 0.25f));
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(stroke);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setColor(Theme.PRIMARY);
            thumb.setStyle(Paint.Style.FILL);
            thumb.setColor(Theme.PRIMARY);
            text.setColor(Theme.ON);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Ui.dp(15));
            setClickable(true);
        }

        void setListener(Listener l) { listener = l; }

        void setValue(int v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }

        void setCenterText(String s) {
            center = s;
            invalidate();
        }

        private float frac() {
            return (value - min) / (float) (max - min);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - stroke - Ui.dp(2);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rect, START, SWEEP, false, track);
            float f = frac();
            if (f > 0f) c.drawArc(rect, START, SWEEP * f, false, fill);
            double a = Math.toRadians(START + SWEEP * f);
            c.drawCircle(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), Ui.dp(7), thumb);
            float base = cy - (text.descent() + text.ascent()) / 2f;
            c.drawText(center, cx, base, text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    // fallthrough
                case MotionEvent.ACTION_MOVE: {
                    double deg = Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2.0, e.getX() - getWidth() / 2.0));
                    double rel = deg - START;
                    while (rel < 0) rel += 360;
                    while (rel >= 360) rel -= 360;
                    float f;
                    if (rel <= SWEEP) f = (float) (rel / SWEEP);
                    else f = rel < SWEEP + (360 - SWEEP) / 2.0 ? 1f : 0f;   // celah bawah: ambil ujung terdekat
                    int v = min + Math.round(f * (max - min));
                    v = Math.round(v / (float) step) * step;
                    v = Math.max(min, Math.min(max, v));
                    if (v != value) {
                        value = v;
                        invalidate();
                        if (listener != null) listener.onValue(v);
                    }
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
