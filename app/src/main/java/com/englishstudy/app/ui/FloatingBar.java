package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;

/**
 * 悬浮胶囊导航栏（仿 LSPosed 17 的底部悬浮条）。
 *
 * 和之前的「贴底通栏」不同，这条是**浮在内容之上**的圆角胶囊：
 *   - 左右留边距，下面留出安全区，看起来像浮起来的
 *   - 胶囊本体是玻璃：半透明底 + 边缘光 + 顶部高光 + 投影
 *   - 选中项是一个更亮的玻璃药丸，位置用动画滑过去
 *
 * 这样玻璃才有「浮在内容上」的层次感 —— 通栏贴底看起来就只是换了个背景色。
 */
public class FloatingBar extends LinearLayout {

    public interface OnTab {
        void onTab(int i);
    }

    private final Context ctx;
    private final TextView[] labels;
    private final View indicator;
    private int current = -1;
    private OnTab listener;
    /** 药丸当前位置（以「格」为单位，可以是小数，用于插值动画）。 */
    private float posFrom = -1;
    private float posTo = -1;
    private long animStart;
    private static final long ANIM_MS = 320;

    /** 胶囊高度。 */
    private final int barHeight;

    public FloatingBar(Context c, String[] names) {
        super(c);
        this.ctx = c;
        barHeight = U.px(c, 50);   // 更扁 → 椭圆端，不是圆头方块

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = U.px(c, 4);
        setPadding(padH, 0, padH, 0);

        // 胶囊本体：玻璃背景 + 投影（浮起来的关键）
        setBackground(new CapsuleBackground(c, barHeight / 2f));
        setWillNotDraw(false);   // 需要 onDraw 画选中药丸

        // 选中指示器（玻璃药丸），单独一层放在最底下
        indicator = new View(c);
        indicator.setBackground(Glass.pill(c, Theme.c(c, R.color.primary)));

        labels = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = new TextView(c);
            t.setText(names[i]);
            t.setTextSize(names.length > 6 ? 10 : 11);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Theme.c(c, R.color.text_dim2));
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

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
    }

    /** 切换选中项：药丸滑动 + 文字变色。 */
    public void select(int i) {
        if (i < 0 || i >= labels.length) return;
        if (i == current) return;
        animateTo(i);
        if (listener != null) listener.onTab(i);
    }

    /** 药丸从当前位置滑到第 i 格，同时切换文字颜色。 */
    private void animateTo(int i) {
        if (current >= 0) {
            labels[current].setTextColor(Theme.c(ctx, R.color.text_dim));
            labels[current].setTypeface(android.graphics.Typeface.DEFAULT);
        }
        posFrom = current < 0 ? i : currentPos();
        posTo = i;
        current = i;
        labels[i].setTextColor(Theme.c(ctx, R.color.primary));
        labels[i].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        animStart = System.currentTimeMillis();
        postInvalidateOnAnimation();
        postDelayed(this::finishAnim, ANIM_MS + 40);
    }

    private void finishAnim() {
        posFrom = posTo;
        invalidate();
    }

    /** 当前插值位置。 */
    private float currentPos() {
        if (posFrom < 0 || posTo < 0) return current;
        long dt = System.currentTimeMillis() - animStart;
        if (dt >= ANIM_MS) return posTo;
        float t = dt / (float) ANIM_MS;
        // ease-out cubic：起步快收尾慢，像 LSPosed 那种
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        return posFrom + (posTo - posFrom) * e;
    }

    /** 只更新高亮，不回调（供外部主动切换时用，避免递归）。 */
    public void markSelected(int i) {
        if (i < 0 || i >= labels.length) return;
        if (i == current) return;
        animateTo(i);
    }

    /** 选中态药丸：比胶囊底更亮一档的实心块（LSPosed 那种）。 */
    private android.graphics.drawable.Drawable selectedPill() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(U.px(ctx, 999));
        int level = Glass.level();
        if (Theme.light()) {
            g.setColor(level == Glass.OFF ? 0xFFEDEAE3 : 0xFFE4E0D6);
        } else {
            g.setColor(level == Glass.OFF ? 0xFF46536B
                    : level == Glass.STRONG ? 0xFF4E5E7A : 0xFF46536B);
        }
        if (level != Glass.OFF) {
            g.setStroke(Math.max(1, U.px(ctx, 1)), 0x33FFFFFF);
        }
        return g;
    }

    public int current() {
        return current;
    }

    private void moveIndicator() {
        if (current < 0 || labels[current].getWidth() == 0) return;
        View target = labels[current];
        float left = target.getLeft();
        float width = target.getWidth();

        indicator.setVisibility(VISIBLE);
        indicator.animate().cancel();
        if (indicator.getWidth() == 0) {
            // 首次：直接定位
            indicator.setX(left + U.px(ctx, 4));
            indicator.setLayoutParams(new LayoutParams((int) (width - U.px(ctx, 8)), barHeight));
        } else {
            indicator.animate()
                    .x(left + U.px(ctx, 4))
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // indicator 不是 child，需要单独画：改成在 dispatchDraw 里画
        super.onLayout(changed, l, t, r, b);
        if (current >= 0) moveIndicator();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (current >= 0 && labels[current].getWidth() > 0) {
            float pos = currentPos();
            int from = (int) Math.floor(pos);
            int to = (int) Math.ceil(pos);
            float frac = pos - from;
            if (from < 0) from = 0;
            if (to >= labels.length) to = labels.length - 1;
            if (from >= labels.length) from = labels.length - 1;

            float l1 = labels[from].getLeft(), r1 = labels[from].getRight();
            float l2 = labels[to].getLeft(), r2 = labels[to].getRight();
            float left = l1 + (l2 - l1) * frac;
            float right = r1 + (r2 - r1) * frac;

            int pad = U.px(ctx, 5);
            int top = U.px(ctx, 5);
            int bottom = getHeight() - U.px(ctx, 5);
            android.graphics.drawable.Drawable bg = selectedPill();
            bg.setBounds((int) left + pad, top, (int) right - pad, bottom);
            bg.draw(canvas);

            // 动画进行中：继续请求下一帧
            if (Math.abs(posTo - pos) > 0.001f) postInvalidateOnAnimation();
        }
        super.onDraw(canvas);
    }

    /**
     * 胶囊背景：圆角矩形 + 边缘光 + 顶部高光 + 外投影。
     *
     * 投影是「浮起来」的关键 —— LSPosed 那条明显有一圈柔和阴影把它从内容上托起来。
     */
    private static final class CapsuleBackground extends android.graphics.drawable.Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius;
        private final Context c;

        CapsuleBackground(Context c, float radius) {
            this.c = c;
            this.radius = radius;
            fill.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            sheen.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            r.set(b);
            int level = Glass.level();
            float d = c.getResources().getDisplayMetrics().density;

            // 底色：比卡片更透一点，透出下面滚动的内容
            // 比页面卡片明显亮一档，才有"浮起来"的对比
            int base = Theme.light() ? 0xFFFFFFFF : 0xFF2A3446;
            int alpha = level == Glass.OFF ? 0xFF
                    : level == Glass.STRONG ? 0xE8
                    : level == Glass.LIGHT ? 0xFA : 0xF2;
            fill.setColor(Glass.withAlpha(base, alpha));
            fill.clearShadowLayer();
            // 外投影：浮起来的关键，比卡片重一点
            if (Glass.level() != Glass.OFF && android.os.Build.VERSION.SDK_INT >= 28) {
                fill.setShadowLayer(20f * d, 0f, 8f * d, 0xCC000000);
            }

            // 边缘光：上亮下暗
            int top = level == Glass.OFF ? 0x18 : level == Glass.STRONG ? 0x70 : 0x4A;
            int bottom = level == Glass.OFF ? 0x18 : 0x12;
            rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    Glass.withAlpha(0xFFFFFFFF, top),
                    Glass.withAlpha(0xFFFFFFFF, bottom), Shader.TileMode.CLAMP));
            rim.setStrokeWidth(Math.max(1f, 1.2f * d));

            // 顶部高光：一条横向渐变，只在上半部
            sheen.setShader(new RadialGradient(
                    r.left + r.width() * 0.3f, r.top, Math.max(r.width() * 0.7f, 1f),
                    new int[]{Glass.withAlpha(0xFFFFFFFF,
                            level == Glass.OFF ? 0 : level == Glass.STRONG ? 0x2A : 0x18),
                            Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRoundRect(r, radius, radius, fill);
            if (Glass.level() != Glass.OFF) {
                canvas.drawRoundRect(r, radius, radius, sheen);
                RectF rr = new RectF(r);
                rr.inset(0.6f * c.getResources().getDisplayMetrics().density,
                        0.6f * c.getResources().getDisplayMetrics().density);
                canvas.drawRoundRect(rr, radius, radius, rim);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
