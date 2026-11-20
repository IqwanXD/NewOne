package ru.iqwanoino.music;

import android.content.Context;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/** Hari-hari kamu memutar musik, untuk streak di Beranda. Disimpan di Prefs (tanpa data pribadi lain). */
final class Stats {

    private static final String KEY = "play_days";

    private Stats() { }

    /** Kunci hari, misalnya "2026-11-20". Unik untuk setiap tanggal. */
    private static String dayKey(Calendar c) {
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH);
    }

    static void touch(Context c) {
        Set<String> days = new HashSet<>(Prefs.prefs(c).getStringSet(KEY, new HashSet<String>()));
        if (days.add(dayKey(Calendar.getInstance()))) {
            Prefs.prefs(c).edit().putStringSet(KEY, days).apply();
        }
    }

    static boolean playedToday(Context c) {
        Set<String> days = Prefs.prefs(c).getStringSet(KEY, null);
        return days != null && days.contains(dayKey(Calendar.getInstance()));
    }

    /** Jumlah hari berturut-turut. Hari ini belum diputar: hitung dari kemarin (streak belum putus). */
    static int streak(Context c) {
        Set<String> days = Prefs.prefs(c).getStringSet(KEY, null);
        if (days == null || days.isEmpty()) return 0;
        Calendar cal = Calendar.getInstance();
        if (!days.contains(dayKey(cal))) cal.add(Calendar.DAY_OF_MONTH, -1);
        int n = 0;
        while (days.contains(dayKey(cal))) {
            n++;
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        return n;
    }
}
