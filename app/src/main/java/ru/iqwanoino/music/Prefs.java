package ru.iqwanoino.music;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferensi aplikasi (SharedPreferences "music"). Nama fungsi mengikuti Pinpic supaya M3 bisa dipakai langsung. */
final class Prefs {
    private static SharedPreferences sp;

    private Prefs() { }

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("music", Context.MODE_PRIVATE);
    }

    static SharedPreferences prefs(Context c) {
        init(c);
        return sp;
    }

    /** Tema gelap (default). false = terang. */
    static boolean darkTheme() { return sp == null || sp.getBoolean("dark", true); }

    static void setDarkTheme(boolean on) { if (sp != null) sp.edit().putBoolean("dark", on).apply(); }

    /** Warna dari wallpaper (Material You, Android 12+). Default mati supaya tema awal memakai hijau bawaan. */
    static boolean dynamicColor() { return sp != null && sp.getBoolean("dynamic_color", false); }

    static void setDynamicColor(boolean on) { if (sp != null) sp.edit().putBoolean("dynamic_color", on).apply(); }

    /** Warna aksen awal: hijau #8BC34A (sama dengan ikon aplikasi). */
    static final int DEFAULT_APP_COLOR = 0xFF22C3D6;

    /** Warna aksen pilihan sendiri (ARGB). Default hijau ikon aplikasi. */
    static int appColor() { return sp == null ? DEFAULT_APP_COLOR : sp.getInt("app_color", DEFAULT_APP_COLOR); }

    static void setAppColor(int argb) { if (sp != null) sp.edit().putInt("app_color", argb).apply(); }

    /** Path file font kustom di penyimpanan internal aplikasi. Kosong = font bawaan. */
    static String customFont() { return sp == null ? "" : sp.getString("custom_font", ""); }

    static String customFontName() { return sp == null ? "" : sp.getString("custom_font_name", ""); }

    static void setCustomFont(String path, String name) {
        if (sp != null) sp.edit().putString("custom_font", path).putString("custom_font_name", name).apply();
    }

    /** Mode grid di daftar lagu. */
    static boolean grid() { return sp != null && sp.getBoolean("grid", false); }

    static void setGrid(boolean on) { if (sp != null) sp.edit().putBoolean("grid", on).apply(); }

    /** Sembunyikan file audio kecil (di bawah Library.SMALL_BYTES). */
    static boolean hideSmall() { return sp != null && sp.getBoolean("hide_small", false); }

    static void setHideSmall(boolean on) { if (sp != null) sp.edit().putBoolean("hide_small", on).apply(); }

    /** Tampilkan tab Storage (daftar folder) di bar atas daftar lagu. */
    static boolean showStorageTab() { return sp != null && sp.getBoolean("show_storage", false); }

    static void setShowStorageTab(boolean on) { if (sp != null) sp.edit().putBoolean("show_storage", on).apply(); }

    /** Jumlah maksimum lagu di Recent music. */
    static final int RECENT_MAX = 21;

    /** Kolom grid (1-4). */
    static int gridCols() { return sp == null ? 2 : clamp(sp.getInt("grid_cols", 2), 1, 4); }

    static void setGridCols(int n) { if (sp != null) sp.edit().putInt("grid_cols", clamp(n, 1, 4)).apply(); }

    /** Jumlah lagu di Recent music (1-21). */
    static int recentCount() { return sp == null ? 10 : clamp(sp.getInt("recent_count", 10), 1, RECENT_MAX); }

    static void setRecentCount(int n) {
        if (sp != null) sp.edit().putInt("recent_count", clamp(n, 1, RECENT_MAX)).apply();
    }

    /** Catat dan tampilkan lagu yang terakhir diputar di Recent music. */
    static boolean showHistory() { return sp == null || sp.getBoolean("show_history", true); }

    static void setShowHistory(boolean on) { if (sp != null) sp.edit().putBoolean("show_history", on).apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Tampilkan tombol equalizer di bar atas daftar lagu. */
    static boolean eqEnabled() { return sp != null && sp.getBoolean("eq_enabled", false); }

    static void setEqEnabled(boolean on) { if (sp != null) sp.edit().putBoolean("eq_enabled", on).apply(); }
}
