package ru.iqwanoino.music;

import android.content.Context;
import android.media.audiofx.Equalizer;

/**
 * Equalizer sistem (android.media.audiofx) yang dipasang ke sesi audio pemutar.
 * Nilai band disimpan di SharedPreferences (millibel) dan diterapkan ulang setiap
 * MediaPlayer baru dibuat. Saat belum ada sesi audio, UI tetap bisa dipakai.
 */
final class Eq {
    static final int FALLBACK_BANDS = 5;
    private static final int[] FALLBACK_HZ = {60, 230, 910, 3600, 14000};
    private static final short FALLBACK_MIN = -1500, FALLBACK_MAX = 1500;

    private static Equalizer eq;
    private static int session = -1;

    private Eq() { }

    /** Dipanggil saat MediaPlayer baru dibuat. */
    static synchronized void attach(Context c, int sessionId) {
        if (sessionId == session && eq != null) return;
        release();
        session = sessionId;
        try {
            eq = new Equalizer(0, sessionId);
            short n = eq.getNumberOfBands();
            for (short i = 0; i < n; i++) eq.setBandLevel(i, clamp(level(c, i)));
            eq.setEnabled(true);
        } catch (RuntimeException e) {
            eq = null;
        }
    }

    private static synchronized void release() {
        if (eq != null) {
            try { eq.release(); } catch (RuntimeException ignored) { }
            eq = null;
        }
    }

    static int count() {
        Equalizer e = eq;
        return e != null ? e.getNumberOfBands() : FALLBACK_BANDS;
    }

    static int min() {
        Equalizer e = eq;
        return e != null ? e.getBandLevelRange()[0] : FALLBACK_MIN;
    }

    static int max() {
        Equalizer e = eq;
        return e != null ? e.getBandLevelRange()[1] : FALLBACK_MAX;
    }

    static int hz(int band) {
        Equalizer e = eq;
        if (e != null && band < e.getNumberOfBands()) return e.getCenterFreq((short) band) / 1000;
        return band < FALLBACK_HZ.length ? FALLBACK_HZ[band] : 0;
    }

    static boolean active() {
        return eq != null;
    }

    static int level(Context c, int band) {
        return Prefs.prefs(c).getInt("eq_band_" + band, 0);
    }

    /** Simpan dan terapkan level satu band (millibel). */
    static void setLevel(Context c, int band, int mB) {
        int v = Math.max(min(), Math.min(max(), mB));
        Prefs.prefs(c).edit().putInt("eq_band_" + band, v).apply();
        Equalizer e = eq;
        if (e != null && band < e.getNumberOfBands()) {
            try { e.setBandLevel((short) band, (short) v); } catch (RuntimeException ignored) { }
        }
    }

    /** Preset berupa fraksi dari batas maksimum (-1..1) untuk setiap band. */
    static final String[] PRESET_NAMES = {"Datar", "Bass", "Treble", "Vokal"};
    private static final float[][] PRESETS = {
            {0, 0, 0, 0, 0},
            {0.7f, 0.5f, 0.1f, 0f, 0f},
            {0f, 0f, 0.1f, 0.5f, 0.7f},
            {-0.2f, 0.1f, 0.5f, 0.3f, -0.1f},
    };

    static void applyPreset(Context c, int idx) {
        float[] p = PRESETS[idx];
        int top = max();
        int n = count();
        for (int i = 0; i < n; i++) {
            float f = p[Math.min(i, p.length - 1)];
            setLevel(c, i, Math.round(f * top));
        }
    }

    private static short clamp(int v) {
        return (short) Math.max(min(), Math.min(max(), v));
    }
}
