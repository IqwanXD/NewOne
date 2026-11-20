package ru.iqwanoino.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.GridView;

/**
 * Scrollbar custom untuk ListView/GridView. Warna mengikuti palet aplikasi (M3.primary), jadi ikut
 * berubah saat dynamic color, App color, atau tema terang/gelap diganti. Muncul saat scroll,
 * menghilang sendiri, dan bisa diseret untuk lompat cepat.
 *
 * Pasang dengan ScrollThumb.attach(list, host); host adalah FrameLayout yang langsung memuat list.
 */
final class ScrollThumb extends View implements AbsListView.OnScrollListener {

    private final AbsListView lv;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final int minThumb, touchW, barW, margin;
    private boolean dragging;
    private float dragOffset, dragF;
    private boolean shown;

    private final Runnable hide = new Runnable() {
        @Override public void run() {
            if (dragging) return;
            shown = false;
            animate().alpha(0f).setDuration(250).start();
        }
    };

    static ScrollThumb attach(AbsListView lv, FrameLayout host) {
        lv.setVerticalScrollBarEnabled(false);
        ScrollThumb t = new ScrollThumb(lv.getContext(), lv);
        host.addView(t, new FrameLayout.LayoutParams(-1, -1));
        return t;
    }

    private ScrollThumb(Context c, AbsListView list) {
        super(c);
        lv = list;
        minThumb = M3.dp(48);
        touchW = M3.dp(32);
        barW = M3.dp(4);
        margin = M3.dp(3);
        setAlpha(0f);
        lv.setOnScrollListener(this);
    }

    // ------------------------------------------------------------ hitung posisi

    private int perRow() {
        return lv instanceof GridView ? Math.max(1, ((GridView) lv).getNumColumns()) : 1;
    }

    private int totalRows() {
        return (lv.getCount() + perRow() - 1) / perRow();
    }

    private int visRows() {
        return (lv.getChildCount() + perRow() - 1) / perRow();
    }

    private float trackTop() { return lv.getTop() + lv.getPaddingTop(); }

    private float trackH() { return Math.max(1, lv.getHeight() - lv.getPaddingTop() - lv.getPaddingBottom()); }

    private boolean scrollable() {
        return lv.getVisibility() == VISIBLE && lv.getChildCount() > 0 && totalRows() - visRows() > 0;
    }

    private float thumbH() {
        float r = (float) visRows() / Math.max(1, totalRows());
        return Math.max(minThumb, Math.min(trackH(), trackH() * r));
    }

    /** Posisi scroll 0..1 dari baris pertama yang terlihat plus pecahan yang sudah tergulung. */
    private float fraction() {
        View c0 = lv.getChildAt(0);
        float frac = 0f;
        if (c0 != null && c0.getHeight() > 0) frac = Math.max(0f, Math.min(1f, -c0.getTop() / (float) c0.getHeight()));
        float pos = lv.getFirstVisiblePosition() / perRow() + frac;
        float max = totalRows() - visRows();
        return max <= 0 ? 0f : Math.max(0f, Math.min(1f, pos / max));
    }

    private float thumbTop(float f) {
        return trackTop() + f * (trackH() - thumbH());
    }

    // ------------------------------------------------------------ scroll listener

    @Override public void onScrollStateChanged(AbsListView v, int state) {
        if (state == SCROLL_STATE_IDLE) scheduleHide();
        else reveal();
    }

    @Override public void onScroll(AbsListView v, int first, int vis, int total) {
        if (!scrollable()) return;
        reveal();
        invalidate();
    }

    private void reveal() {
        if (!scrollable()) return;
        h.removeCallbacks(hide);
        shown = true;
        animate().cancel();
        setAlpha(1f);
        invalidate();
        scheduleHide();
    }

    private void scheduleHide() {
        h.removeCallbacks(hide);
        h.postDelayed(hide, 900);
    }

    // ------------------------------------------------------------ gambar & sentuh

    @Override
    protected void onDraw(Canvas c) {
        if (!scrollable()) return;
        float f = dragging ? dragF : fraction();
        float top = thumbTop(f), th = thumbH();
        float w = dragging ? barW + M3.dp(2) : barW;
        paint.setColor(M3.primary);
        float right = getWidth() - margin;
        rect.set(right - w, top, right, top + th);
        c.drawRoundRect(rect, w / 2f, w / 2f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!shown || !scrollable() || getAlpha() < 0.1f) return false;
                if (e.getX() < getWidth() - touchW) return false;
                float top = thumbTop(fraction()), th = thumbH();
                if (e.getY() < top - M3.dp(12) || e.getY() > top + th + M3.dp(12)) return false;
                dragging = true;
                dragOffset = e.getY() - top;
                dragF = fraction();
                h.removeCallbacks(hide);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float range = trackH() - thumbH();
                dragF = range <= 0 ? 0f : Math.max(0f, Math.min(1f, (e.getY() - dragOffset - trackTop()) / range));
                int row = Math.round(dragF * (totalRows() - visRows()));
                lv.setSelection(row * perRow());
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                invalidate();
                scheduleHide();
                return true;
            }
            default:
                return dragging;
        }
    }
}
