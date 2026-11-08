package com.englishstudy.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;

/**
 * 悬浮胶囊导航栏（Liquid Glass）。
 *
 * 关键设计（依据 Apple HIG《Materials》+ WWDC25）：
 *
 *   1. 玻璃必须「真的透出并模糊背后的内容」，否则就是一张贴纸
 *      → 每次内容变化后，把下方那一条内容抓成低分辨率快照，盒式模糊，当底图画
 *   2. 描边要极细（0.5–1px）且**渐变**（上亮下暗），粗描边一眼假
 *   3. 深色下不要用白色低 alpha 叠——会发灰去饱和；用暗色底 + 亮度提升
 *   4. 投影要有但不能硬：大半径、低 alpha、向下偏移，才叫"浮"
 *   5. 背景要够透明（α 0x40–0x60），让内容真的能"peek through"
 */
public class FloatingBar extends LinearLayout {

    public interface OnTab {
        void onTab(int i);
    }

    private final android.content.Context ctx;
    private final TextView[] labels;
    private int current = -1;
    private OnTab listener;
    private final int barHeight;

    /** 药丸位置插值（单位：格，可带小数）。 */
    private float posFrom = -1, posTo = -1;
    private long animStart;
    private static final long ANIM_MS = 340;

    /** 背后内容的模糊快照。 */
    private View backdropSource;
    private Bitmap blurred;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    /** 玻璃层复用的 Paint。 */
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    public FloatingBar(android.content.Context c, String[] names) {
        super(c);
        this.ctx = c;
        barHeight = U.px(c, 56);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(U.px(c, 5), 0, U.px(c, 5), 0);
        setWillNotDraw(false);
        // 投影要画在 View 边界之外，必须关掉 clipToPadding 并留出绘制余量
        setClipToPadding(false);
        setClipChildren(false);
        // 关闭默认背景：全部在 onDraw 里画，才能保证「模糊底图 → 玻璃层 → 药丸 → 文字」的顺序
        setBackground(null);

        labels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(names[i]);
            t.setTextSize(names.length > 6 ? 11 : 12);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Theme.c(c, R.color.text_dim));
            t.setIncludeFontPadding(false);
            t.setOnClickListener(v -> select(idx));
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            t.setLayoutParams(lp);
            labels[i] = t;
            addView(t);
        }
    }

    public void setOnTab(OnTab l) {
        this.listener = l;
    }

    /** 告诉它「背后的内容 View 是谁」，用来抓模糊快照。 */
    public void bindBackdrop(View source) {
        this.backdropSource = source;
        refreshBackdrop();
    }

    /**
     * 重新抓快照。滚动结束时调用；不要每帧调用。
     * 快照只在「内容变了」时需要更新，滚动中复用旧图（人眼分辨不出）。
     */
    public void refreshBackdrop() {
        if (backdropSource == null || getWidth() == 0) return;
        post(() -> {
            if (backdropSource == null || getWidth() == 0) return;
            // 计算自己在 source 坐标系里的矩形（source 是 content FrameLayout）
            int[] myLoc = new int[2];
            int[] srcLoc = new int[2];
            getLocationOnScreen(myLoc);
            backdropSource.getLocationOnScreen(srcLoc);
            android.graphics.Rect region = new android.graphics.Rect(
                    myLoc[0] - srcLoc[0], myLoc[1] - srcLoc[1],
                    myLoc[0] - srcLoc[0] + getWidth(),
                    myLoc[1] - srcLoc[1] + getHeight());

            Bitmap old = blurred;
            Bitmap bmp = Glass.blurBehind(backdropSource, region,
                    getWidth(), getHeight(), U.px(ctx, 16));
            if (bmp != null) {
                blurred = bmp;
                if (old != null && !old.isRecycled()) old.recycle();
                invalidate();
            }
        });
    }

    // ---------------------------------------------------------------- 选中

    public void select(int i) {
        if (i < 0 || i >= labels.length || i == current) return;
        animateTo(i);
        if (listener != null) listener.onTab(i);
    }

    /** 只更新高亮，不回调。 */
    public void markSelected(int i) {
        if (i < 0 || i >= labels.length || i == current) return;
        animateTo(i);
    }

    private void animateTo(int i) {
        if (current >= 0) {
            labels[current].setTextColor(Theme.c(ctx, R.color.text_dim));
            labels[current].setTypeface(android.graphics.Typeface.DEFAULT);
        }
        posFrom = current < 0 ? i : currentPos();
        posTo = i;
        current = i;
        labels[i].setTextColor(Theme.c(ctx, R.color.text));
        labels[i].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        animStart = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    private float currentPos() {
        if (posFrom < 0 || posTo < 0) return Math.max(0, current);
        long dt = System.currentTimeMillis() - animStart;
        if (dt >= ANIM_MS) return posTo;
        float t = dt / (float) ANIM_MS;
        float e = 1f - (float) Math.pow(1f - t, 3);   // ease-out cubic
        return posFrom + (posTo - posFrom) * e;
    }

    public int current() {
        return current;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        barRect.set(0, 0, w, h);
        refreshBackdrop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = h / 2f;
        int level = Glass.level();
        boolean light = Theme.light();

        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(barRect, rad, rad, android.graphics.Path.Direction.CW);

        // ══ 1. 模糊底图（有内容在背后时才有意义）
        if (blurred != null && !blurred.isRecycled() && level != Glass.OFF) {
            srcRect.set(0, 0, blurred.getWidth(), blurred.getHeight());
            dstRect.set(0, 0, w, h);
            int save = canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(blurred, srcRect, dstRect, bmpPaint);
            canvas.restoreToCount(save);
        }

        // ══ 2. 材质本体：垂直渐变 + 透明度
        //    关键：即使背后是空的，也要靠自身渐变读出"这是一块玻璃"
        int topCol, botCol;
        if (light) {
            topCol = 0xF2FFFFFF;
            botCol = 0xD8EDE9E2;
        } else {
            topCol = 0xFF39445A;
            botCol = 0xFF232C3B;
        }
        if (level == Glass.STRONG) {
            topCol = Glass.withAlpha(topCol, 0xD0);
            botCol = Glass.withAlpha(botCol, 0xC8);
        } else if (level == Glass.LIGHT) {
            topCol = Glass.withAlpha(topCol, 0xF6);
            botCol = Glass.withAlpha(botCol, 0xF0);
        } else if (level == Glass.OFF) {
            topCol = Glass.withAlpha(topCol, 0xFF);
            botCol = Glass.withAlpha(botCol, 0xFF);
        } else {
            topCol = Glass.withAlpha(topCol, 0xE6);
            botCol = Glass.withAlpha(botCol, 0xDC);
        }

        fillPaint.setShader(new LinearGradient(0, 0, 0, h, topCol, botCol,
                Shader.TileMode.CLAMP));
        fillPaint.clearShadowLayer();
        float w2 = w, h2 = h;
        if (level != Glass.OFF && android.os.Build.VERSION.SDK_INT >= 28) {
            // 多层投影叠加才像真阴影：近处紧实 + 远处扩散
            fillPaint.setShadowLayer(U.px(ctx, 10), 0f, U.px(ctx, 4), 0x73000000);
            canvas.drawRoundRect(barRect, rad, rad, fillPaint);
            fillPaint.setShadowLayer(U.px(ctx, 28), 0f, U.px(ctx, 12), 0x40000000);
        }
        canvas.drawRoundRect(barRect, rad, rad, fillPaint);
        fillPaint.clearShadowLayer();
        fillPaint.setShader(null);

        // ══ 3. 顶部内高光：一条亮弧，模拟光从上方射到凸面
        int save2 = canvas.save();
        canvas.clipPath(clip);
        sheenPaint.setShader(new LinearGradient(0, 0, 0, h * 0.5f,
                Glass.withAlpha(0xFFFFFFFF, level == Glass.OFF ? 0x14 : 0x30),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h * 0.5f, sheenPaint);

        // ══ 4. 底部内阴影：做出厚度
        sheenPaint.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                Color.TRANSPARENT,
                Glass.withAlpha(0xFF000000, light ? 0x0C : 0x2A),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.55f, w, h, sheenPaint);
        sheenPaint.setShader(null);
        canvas.restoreToCount(save2);

        // ══ 5. 边缘光：上亮下暗的极细渐变（0.9dp）
        if (level != Glass.OFF) {
            float sw = Math.max(1f, 0.9f * ctx.getResources().getDisplayMetrics().density);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(sw);
            rimPaint.setShader(new LinearGradient(0, 0, 0, h,
                    Glass.withAlpha(0xFFFFFFFF, level == Glass.STRONG ? 0x66 : 0x46),
                    Glass.withAlpha(0xFFFFFFFF, 0x0A),
                    Shader.TileMode.CLAMP));
            RectF rimR = new RectF(barRect);
            rimR.inset(sw / 2f, sw / 2f);
            canvas.drawRoundRect(rimR, rad - sw / 2f, rad - sw / 2f, rimPaint);
            rimPaint.setShader(null);
        }

        // ══ 6. 选中药丸：比胶囊再亮一档，带自己的高光与细边
        if (current >= 0 && labels[current].getWidth() > 0) {
            float pos = currentPos();
            int from = (int) Math.floor(pos);
            int to = (int) Math.ceil(pos);
            float frac = pos - from;
            from = Math.max(0, Math.min(labels.length - 1, from));
            to = Math.max(0, Math.min(labels.length - 1, to));

            float l1 = labels[from].getLeft(), r1 = labels[from].getRight();
            float l2 = labels[to].getLeft(), r2 = labels[to].getRight();
            float left = l1 + (l2 - l1) * frac;
            float right = r1 + (r2 - r1) * frac;

            int padH = U.px(ctx, 3);
            float top = U.px(ctx, 4), bottom = h - U.px(ctx, 4);
            RectF pill = new RectF(left + padH, top, right - padH, bottom);
            float pr = pill.height() / 2f;

            // 药丸底色：明暗渐变
            int pTop = light ? 0xFFFFFFFF : 0xFF56637A;
            int pBot = light ? 0xFFF0ECE4 : 0xFF414D62;
            pillPaint.setShader(new LinearGradient(0, pill.top, 0, pill.bottom,
                    pTop, pBot, Shader.TileMode.CLAMP));
            pillPaint.clearShadowLayer();
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                pillPaint.setShadowLayer(U.px(ctx, 7), 0f, U.px(ctx, 3), 0x44000000);
            }
            canvas.drawRoundRect(pill, pr, pr, pillPaint);
            pillPaint.clearShadowLayer();
            pillPaint.setShader(null);

            // 药丸顶部高光
            int save3 = canvas.save();
            android.graphics.Path pc = new android.graphics.Path();
            pc.addRoundRect(pill, pr, pr, android.graphics.Path.Direction.CW);
            canvas.clipPath(pc);
            pillPaint.setShader(new LinearGradient(0, pill.top, 0,
                    pill.top + pill.height() * 0.55f,
                    Glass.withAlpha(0xFFFFFFFF, light ? 0x60 : 0x38),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(pill, pillPaint);
            pillPaint.setShader(null);
            canvas.restoreToCount(save3);

            // 药丸细边
            pillPaint.setStyle(Paint.Style.STROKE);
            pillPaint.setStrokeWidth(Math.max(1f,
                    0.8f * ctx.getResources().getDisplayMetrics().density));
            pillPaint.setColor(Glass.withAlpha(0xFFFFFFFF, light ? 0x90 : 0x30));
            RectF pRim = new RectF(pill);
            float i2 = pillPaint.getStrokeWidth() / 2f;
            pRim.inset(i2, i2);
            canvas.drawRoundRect(pRim, pr - i2, pr - i2, pillPaint);
            pillPaint.setStyle(Paint.Style.FILL);

            if (Math.abs(posTo - pos) > 0.001f) postInvalidateOnAnimation();
        }

        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (blurred != null && !blurred.isRecycled()) blurred.recycle();
        blurred = null;
        super.onDetachedFromWindow();
    }
}
