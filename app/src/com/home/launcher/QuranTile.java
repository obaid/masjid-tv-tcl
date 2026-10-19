package com.home.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The Quran tile: "القرآن" centred on its actual glyph bounds. The Quranic font's tall line
 * metrics push text off-centre inside a TextView, so it is drawn directly instead.
 */
public class QuranTile extends View {
    private static final String WORD = "القرآن";
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final float radius;

    public QuranTile(Context c, Typeface font) {
        super(c);
        radius = 12 * c.getResources().getDisplayMetrics().density;
        text.setColor(0xFFE9C880);
        if (font != null) text.setTypeface(font);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF2A7A5C, 0xFF0F3A2C, Shader.TileMode.CLAMP));
        text.setTextSize(h * 0.42f);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, bg);
        text.getTextBounds(WORD, 0, WORD.length(), bounds);
        float x = (w - bounds.width()) / 2f - bounds.left;
        float y = (h - bounds.height()) / 2f - bounds.top;
        c.drawText(WORD, x, y, text);
    }
}
