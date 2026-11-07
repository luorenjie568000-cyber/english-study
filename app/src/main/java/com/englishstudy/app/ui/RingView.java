package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** 圆形进度环：中央大字 + 副标题。 */
public class RingView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float progress;
    private String label = "0";
    private String caption = "";

    public RingView(Context c) {
        this(c, null);
    }

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        bg.setStyle(Paint.Style.STROKE);
        bg.setStrokeWidth(dp(9));
        bg.setColor(0xFF25303F);
        bg.setStrokeCap(Paint.Cap.ROUND);

        fg.setStyle(Paint.Style.STROKE);
        fg.setStrokeWidth(dp(9));
        fg.setColor(0xFF4F7CFF);
        fg.setStrokeCap(Paint.Cap.ROUND);

        txt.setColor(0xFFE8EEF7);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        sub.setColor(0xFF8A9AB0);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setProgress(float p, String label, String caption) {
        this.progress = Math.max(0, Math.min(1, p));
        this.label = label;
        this.caption = caption;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float pad = dp(10);
        oval.set(pad, pad, w - pad, h - pad);

        cv.drawArc(oval, 0, 360, false, bg);
        if (progress > 0) cv.drawArc(oval, -90, 360 * progress, false, fg);

        txt.setTextSize(w * 0.26f);
        sub.setTextSize(w * 0.075f);
        float cy = h / 2f - (txt.descent() + txt.ascent()) / 2f;
        cv.drawText(label, w / 2f, cy, txt);
        if (!caption.isEmpty()) cv.drawText(caption, w / 2f, h / 2f + w * 0.20f, sub);
    }
}
