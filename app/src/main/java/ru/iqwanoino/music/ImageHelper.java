package ru.iqwanoino.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.util.TypedValue;
import android.graphics.Matrix;

public class ImageHelper {
	
	// Konversi dp ke px
	private static int dpToPx(Context context, float dp) {
		return (int) TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		dp,
		context.getResources().getDisplayMetrics()
		);
	}
	
	// Fungsi untuk memotong dari tengah dan memberi radius sudut
	public static Bitmap cropAndRoundCornerBitmap(Context context, Bitmap bitmap, float sizeDp, float radiusDp) {
		int sizePx = dpToPx(context, sizeDp);
		int radiusPx = dpToPx(context, radiusDp);
		
		Bitmap scaledBitmap;
		
		// Skala dan crop gambar agar pas di tengah (center crop style)
		float scale;
		float dx = 0, dy = 0;
		
		int bWidth = bitmap.getWidth();
		int bHeight = bitmap.getHeight();
		
		float scaleX = (float) sizePx / bWidth;
		float scaleY = (float) sizePx / bHeight;
		scale = Math.max(scaleX, scaleY);
		
		Matrix matrix = new Matrix();
		matrix.setScale(scale, scale);
		
		// Hitung offset (center crop)
		if (scaleX < scaleY) {
			dx = (sizePx - bWidth * scale) / 2f;
		} else {
			dy = (sizePx - bHeight * scale) / 2f;
		}
		matrix.postTranslate(Math.round(dx), Math.round(dy));
		
		Bitmap output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(output);
		
		// Round rect
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		RectF rectF = new RectF(0, 0, sizePx, sizePx);
		canvas.drawRoundRect(rectF, radiusPx, radiusPx, paint);
		
		// Masking pakai Xfermode
		paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
		canvas.drawBitmap(bitmap, matrix, paint);
		
		return output;
	}
}
