package ru.iqwanoino.music;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Ikon digambar langsung di Canvas (viewbox 24x24), menggantikan drawable XML. */
final class Icons extends Drawable {
    static final int MENU = 0, PLAY = 1, PAUSE = 2, NEXT = 3, PREV = 4, SHUFFLE = 5,
            REPEAT = 6, REPEAT_ONE = 7, NOTE = 8, INFO = 9, RELOAD = 10,
            MOON = 11, SUN = 12, HEADPHONE = 13;

    private final int type;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    Icons(int type, int color) {
        this.type = type;
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        if (s <= 0) return;
        int save = c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        draw24(c);
        c.restoreToCount(save);
    }

    private void draw24(Canvas c) {
        switch (type) {
            case MENU:
                rr(c, 3, 5.5f, 21, 7.5f, 1);
                rr(c, 3, 11.25f, 21, 13.25f, 1);
                rr(c, 3, 17, 21, 19, 1);
                break;
            case PLAY:
                poly(c, 8, 5, 19, 12, 8, 19);
                break;
            case PAUSE:
                rr(c, 6, 5, 10, 19, 1);
                rr(c, 14, 5, 18, 19, 1);
                break;
            case NEXT:
                poly(c, 5, 5, 15, 12, 5, 19);
                rr(c, 16, 5, 19, 19, 1);
                break;
            case PREV:
                rr(c, 5, 5, 8, 19, 1);
                poly(c, 19, 5, 9, 12, 19, 19);
                break;
            case SHUFFLE:
                line(c, 3, 7, 7, 7, 17, 17, 21, 17);
                line(c, 3, 17, 7, 17, 17, 7, 21, 7);
                poly(c, 17.5f, 4.5f, 21.5f, 7, 17.5f, 9.5f);
                poly(c, 17.5f, 14.5f, 21.5f, 17, 17.5f, 19.5f);
                break;
            case REPEAT:
            case REPEAT_ONE:
                rect.set(3, 6, 21, 18);
                c.drawRoundRect(rect, 4, 4, stroke);
                poly(c, 16, 2.5f, 22, 6, 16, 9.5f);
                poly(c, 8, 14.5f, 2, 18, 8, 21.5f);
                if (type == REPEAT_ONE) {
                    fill.setTextSize(9);
                    fill.setTextAlign(Paint.Align.CENTER);
                    c.drawText("1", 12, 15, fill);
                    fill.setTextAlign(Paint.Align.LEFT);
                }
                break;
            case NOTE:
                rect.set(5, 14, 11.5f, 20);
                c.drawOval(rect, fill);
                rr(c, 9.5f, 4, 11.5f, 17, 0.5f);
                poly(c, 11.5f, 4, 19, 6.5f, 19, 11, 11.5f, 8.5f);
                break;
            case INFO:
                c.drawCircle(12, 12, 9.5f, stroke);
                c.drawCircle(12, 7.6f, 1.4f, fill);
                c.drawLine(12, 11, 12, 17, stroke);
                break;
            case RELOAD:
                rect.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(rect, 30, 290, false, stroke);
                poly(c, 13.5f, 4.5f, 19.5f, 4.5f, 19.5f, 10.5f);
                break;
            case MOON:
                path.reset();
                path.addCircle(12, 12, 9, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(16, 9, 7, Path.Direction.CW);
                path.op(cut, Path.Op.DIFFERENCE);
                c.drawPath(path, fill);
                break;
            case SUN:
                c.drawCircle(12, 12, 4.5f, fill);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(12 + 7.2f * cos, 12 + 7.2f * sin, 12 + 9.6f * cos, 12 + 9.6f * sin, stroke);
                }
                break;
            case HEADPHONE:
                rect.set(4, 4, 20, 20);
                c.drawArc(rect, 180, 180, false, stroke);
                rr(c, 3, 12, 7.5f, 19, 1.2f);
                rr(c, 16.5f, 12, 21, 19, 1.2f);
                break;
            default:
                break;
        }
    }

    private void rr(Canvas c, float l, float t, float r, float b, float rad) {
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, rad, rad, fill);
    }

    private void poly(Canvas c, float... p) {
        path.reset();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        path.close();
        c.drawPath(path, fill);
    }

    private void line(Canvas c, float... p) {
        path.reset();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        c.drawPath(path, stroke);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); stroke.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
