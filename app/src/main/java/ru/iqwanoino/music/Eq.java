package ru.iqwanoino.music;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;

/**
 * Efek audio sistem yang dipasang ke sesi audio pemutar:
 *  - Equalizer per band (millibel)
 *  - Bass Booster (kekuatan 0..1000)
 *  - Volume lewat LoudnessEnhancer (penguatan 0..1000 mB, yaitu 0..10 dB)
 * Nilai disimpan di SharedPreferences dan diterapkan ulang setiap MediaPlayer baru dibuat.
 * Efek yang tidak didukung perangkat dilewati; UI tetap bisa dipakai.
 */
final class Eq {
    static final int FALLBACK_BANDS = 5;
    private static final int[] FALLBACK_HZ = {60, 230, 910, 3600, 14000};
    private static final short FALLBACK_MIN = -1500, FALLBACK_MAX = 1500;
    static final int BASS_MAX = 1000;
    static final int LOUD_MAX = 1000;

    private static Equalizer eq;
    private static BassBoost bass;
    private static LoudnessEnhancer loud;
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
        attachEffects(c, sessionId);
    }

    private static void attachEffects(Context c, int sessionId) {
        try {
            bass = new BassBoost(0, sessionId);
            bass.setStrength((short) bassStrength(c));
            bass.setEnabled(true);
        } catch (RuntimeException e) {
            bass = null;
        }
        try {
            loud = new LoudnessEnhancer(sessionId);
            loud.setTargetGain(loudGain(c));
            loud.setEnabled(true);
        } catch (RuntimeException e) {
            loud = null;
        }
    }

    private static synchronized void release() {
        if (eq != null) {
            try { eq.release(); } catch (RuntimeException ignored) { }
            eq = null;
        }
        if (bass != null) {
            try { bass.release(); } catch (RuntimeException ignored) { }
            bass = null;
        }
        if (loud != null) {
            try { loud.release(); } catch (RuntimeException ignored) { }
            loud = null;
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

    // ------------------------------------------------------------ Bass Booster

    static int bassStrength(Context c) {
        return Prefs.prefs(c).getInt("eq_bass", 0);
    }

    static void setBassStrength(Context c, int v) {
        int s = Math.max(0, Math.min(BASS_MAX, v));
        Prefs.prefs(c).edit().putInt("eq_bass", s).apply();
        BassBoost b = bass;
        if (b != null) {
            try { b.setStrength((short) s); } catch (RuntimeException ignored) { }
        }
    }

    /** Apakah knob Bass Booster ditampilkan di UI (hanya tampilan, efek tetap berjalan). */
    static boolean bassVisible(Context c) {
        return Prefs.prefs(c).getBoolean("eq_bass_show", true);
    }

    static void setBassVisible(Context c, boolean on) {
        Prefs.prefs(c).edit().putBoolean("eq_bass_show", on).apply();
    }

    // ------------------------------------------------------------ Volume (LoudnessEnhancer)

    static int loudGain(Context c) {
        return Prefs.prefs(c).getInt("eq_loud", 0);
    }

    static void setLoudGain(Context c, int mB) {
        int g = Math.max(0, Math.min(LOUD_MAX, mB));
        Prefs.prefs(c).edit().putInt("eq_loud", g).apply();
        LoudnessEnhancer l = loud;
        if (l != null) {
            try { l.setTargetGain(g); } catch (RuntimeException ignored) { }
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
