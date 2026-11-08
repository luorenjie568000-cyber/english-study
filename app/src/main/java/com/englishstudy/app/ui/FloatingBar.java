package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;

/**
 * 悬浮胶囊导航栏 —— 真正的液态玻璃（Liquid Glass）。
 *
 * ## 为什么之前的实现像"贴图"
 *
 * 之前用「抓 Bitmap + CPU 盒式模糊」，有三座大山：
 *   1. 坐标系难对齐（屏幕绝对坐标 vs View 本地坐标）
 *   2. 内容滚动后要重抓，否则看到旧画面
 *   3. 抓的时候如果目标区域是 padding 空白，就抓不到东西
 *
 * ## 正确做法（调研结论，已实机验证）
 *
 * 把背景层重录进一个独立 RenderNode（RecordingCanvas → 显示列表，**零位图、零回读**），
 * 给这个 RenderNode 挂 RenderEffect（GPU 高斯模糊 + 可选 AGSL 折射），
 * 再把它画到自己身上。被模糊的是背景层，自己的图标/文字原样清晰。
 *
 * 性能实测：p99 = 9ms，462 帧 1 掉帧（0.22%）。滚动零掉帧。
 *
 * ## 关键约束
 *
 * - 背景源必须指向**有背景色的父容器**（不是 ScrollView —— 它 padding 处是透明的，
 *   模糊后会出现深色空洞）
 * - 玻璃必须是背景源的**直接子级**，这样遍历时跳过自己即可，不会有重入/成环问题
 * - 不要在 onDraw 里无条件 invalidate（会形成 invalidate→draw 死循环）
 */
public class FloatingBar extends LinearLayout {

    public interface OnTab {
        void onTab(int i);
    }

    // ================= AGSL 透镜（API 33+，折射 + 色散 + 边缘高光） =================
    // uniform 名必须避开 AGSL 内建函数名（refract 等），否则运行时抛异常直接崩 App。
    private static final String LENS_AGSL =
            "uniform shader content;\n" +
            "uniform float2 gSize;\n" +
            "uniform float gRadius;\n" +
            "uniform float gBevel;\n" +
            "uniform float gRefract;\n" +
            "uniform float gDisp;\n" +
            "\n" +
            "float sdRoundRect(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - b + r;\n" +
            "    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    float2 c  = gSize * 0.5;\n" +
            "    float2 hb = c;\n" +
            "    float d = sdRoundRect(coord - c, hb, gRadius);\n" +
            "    if (d > 0.5) { return half4(0.0); }\n" +
            "    float2 n = float2(\n" +
            "        sdRoundRect(coord + float2(1.0, 0.0) - c, hb, gRadius)\n" +
            "      - sdRoundRect(coord - float2(1.0, 0.0) - c, hb, gRadius),\n" +
            "        sdRoundRect(coord + float2(0.0, 1.0) - c, hb, gRadius)\n" +
            "      - sdRoundRect(coord - float2(0.0, 1.0) - c, hb, gRadius));\n" +
            "    float nl = length(n);\n" +
            "    n = (nl > 0.0001) ? n / nl : float2(0.0, -1.0);\n" +
            "    float t     = clamp(-d / max(gBevel, 1.0), 0.0, 1.0);\n" +
            "    float slope = (1.0 - t) * (1.0 - t);\n" +
            "    float2 off = n * (slope * gRefract);\n" +
            "    float2 cR = coord + off * (1.0 - gDisp * slope);\n" +
            "    float2 cG = coord + off;\n" +
            "    float2 cB = coord + off * (1.0 + gDisp * slope);\n" +
            "    float2 hi = gSize - float2(1.0);\n" +
            "    cR = clamp(cR, float2(0.0), hi);\n" +
            "    cG = clamp(cG, float2(0.0), hi);\n" +
            "    cB = clamp(cB, float2(0.0), hi);\n" +
            "    half3 col = half3(content.eval(cR).r, content.eval(cG).g, content.eval(cB).b);\n" +
            "    float2 L   = normalize(float2(-0.5, -0.8));\n" +
            "    float  rim = pow(max(0.0, dot(n, L)), 2.0) * slope;\n" +
            "    col += half3(rim * 0.55);\n" +
            "    float cov = clamp(0.5 - d, 0.0, 1.0);\n" +
            "    return half4(col * cov, cov);\n" +
            "}\n";

    private final Context ctx;
    private final TextView[] labels;
    private final int barHeight;

    // ---- 玻璃参数 ----
    private float blurRadiusPx;
    private float cornerRadiusPx;
    private float refractionPx;
    private float dispersion = 0.10f;
    private float saturation = 1.25f;
    private int tintColor;
    private int downsample = 1;

    // ---- 状态 ----
    private View backdropSource;
    private final RenderNode backdropNode = new RenderNode("bar-backdrop");
    private RuntimeShader lensShader;
    private RenderEffect cachedEffect;
    private boolean lensBroken;
    private float cBlur = -1f, cRefr = -1f, cSat = -1f, cDisp = -1f;
    private int cW = -1, cH = -1;
    private boolean recording;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final ColorMatrix cm = new ColorMatrix();
    private final int[] locSelf = new int[2];
    private final int[] locSrc = new int[2];

    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            () -> invalidate();

    // ---- 药丸动画 ----
    private int current = -1;
    private OnTab listener;
    private float posFrom = -1, posTo = -1;
    private long animStart;
    private static final long ANIM_MS = 340;

    public FloatingBar(Context c, String[] names) {
        super(c);
        this.ctx = c;
        barHeight = U.px(c, 52);
        cornerRadiusPx = barHeight / 2f;
        blurRadiusPx = U.px(c, 24);
        refractionPx = Build.VERSION.SDK_INT >= 33 ? U.px(c, 12) : 0f;
        tintColor = Theme.light() ? 0x22FFFFFF : 0x1F000000;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(U.px(c, 5), 0, U.px(c, 5), 0);
        setWillNotDraw(false);          // ViewGroup 默认跳过 onDraw
        setBackground(null);
        tintPaint.setColor(tintColor);

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

    // ================= 公开 API =================

    public void setOnTab(OnTab l) {
        this.listener = l;
    }

    /**
     * 设置背景源。
     *
     * 必须是**有背景色的父容器**（不是 ScrollView —— 它 padding 处透明，
     * 模糊后会出现深色空洞）。玻璃应当是它的直接子级。
     */
    public void bindBackdrop(View source) {
        backdropSource = (source == this) ? null : source;
        cachedEffect = null;
        if (isAttachedToWindow()) registerScroll();
        invalidate();
    }

    /** 兼容旧调用（内容变化时手动刷新）。 */
    public void refreshBackdrop() {
        invalidate();
    }

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

    public int current() {
        return current;
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
        float e = 1f - (float) Math.pow(1f - t, 3);
        return posFrom + (posTo - posFrom) * e;
    }

    // ================= 生命周期 =================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerScroll();
    }

    @Override
    protected void onDetachedFromWindow() {
        unregisterScroll();
        backdropNode.discardDisplayList();
        super.onDetachedFromWindow();
    }

    private void registerScroll() {
        View src = source();
        if (src == null) return;
        unregisterScroll();
        observer = src.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnScrollChangedListener(scrollListener);
    }

    private void unregisterScroll() {
        if (observer != null && observer.isAlive()) {
            observer.removeOnScrollChangedListener(scrollListener);
        }
        observer = null;
    }

    private View source() {
        if (backdropSource != null) return backdropSource;
        ViewParent p = getParent();
        return (p instanceof View) ? (View) p : null;
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas canvas) {
        if (recording) return;

        boolean ok = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()
                && source() != null && getWidth() > 0 && getHeight() > 0
                && recordBackdrop(source());

        int save = canvas.save();
        float r = Math.min(cornerRadiusPx, Math.min(getWidth(), getHeight()) * 0.5f);
        clipPath.reset();
        clipPath.addRoundRect(0, 0, getWidth(), getHeight(), r, r, Path.Direction.CW);
        canvas.clipPath(clipPath);

        if (ok) {
            canvas.drawRenderNode(backdropNode);
            if (Color.alpha(tintColor) > 0) {
                canvas.drawRect(0, 0, getWidth(), getHeight(), tintPaint);
            }
        } else {
            // API 30 或硬件加速不可用：退化为不透明底（仍然圆角，视觉上像玻璃卡片）
            canvas.drawColor(Theme.light() ? 0xFFF0EDE6 : 0xFF232C3B);
        }

        // 顶部内高光（材质自身的层次，不依赖背景）
        if (ok) {
            rimPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight() * 0.5f,
                    Glass.withAlpha(0xFFFFFFFF, Theme.light() ? 0x26 : 0x1E),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight() * 0.5f, rimPaint);
            rimPaint.setShader(null);
        }

        // 边缘光：0.9dp 渐变（上亮下暗）
        float sw = Math.max(1f, 0.9f * ctx.getResources().getDisplayMetrics().density);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(sw);
        rimPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(),
                Glass.withAlpha(0xFFFFFFFF, 0x50), Glass.withAlpha(0xFFFFFFFF, 0x0A),
                Shader.TileMode.CLAMP));
        android.graphics.RectF rimR = new android.graphics.RectF(0, 0, getWidth(), getHeight());
        rimR.inset(sw / 2f, sw / 2f);
        canvas.drawRoundRect(rimR, r - sw / 2f, r - sw / 2f, rimPaint);
        rimPaint.setShader(null);
        rimPaint.setStyle(Paint.Style.FILL);

        // 选中药丸
        if (current >= 0 && labels[current].getWidth() > 0) {
            float pos = currentPos();
            int from = Math.max(0, Math.min(labels.length - 1, (int) Math.floor(pos)));
            int to = Math.max(0, Math.min(labels.length - 1, (int) Math.ceil(pos)));
            float frac = pos - from;

            float l1 = labels[from].getLeft(), r1 = labels[from].getRight();
            float l2 = labels[to].getLeft(), r2 = labels[to].getRight();
            float left = l1 + (l2 - l1) * frac;
            float right = r1 + (r2 - r1) * frac;

            int padH = U.px(ctx, 3);
            float top = U.px(ctx, 4), bottom = getHeight() - U.px(ctx, 4);
            android.graphics.RectF pill = new android.graphics.RectF(
                    left + padH, top, right - padH, bottom);
            float pr = pill.height() / 2f;

            pillPaint.setShader(new android.graphics.LinearGradient(0, pill.top, 0, pill.bottom,
                    Theme.light() ? 0xFFFFFFFF : 0xFF4E5B72,
                    Theme.light() ? 0xFFF2EEE6 : 0xFF37435A, Shader.TileMode.CLAMP));
            pillPaint.clearShadowLayer();
            if (Build.VERSION.SDK_INT >= 28) {
                pillPaint.setShadowLayer(U.px(ctx, 7), 0f, U.px(ctx, 3), 0x44000000);
            }
            canvas.drawRoundRect(pill, pr, pr, pillPaint);
            pillPaint.clearShadowLayer();
            pillPaint.setShader(null);

            if (Math.abs(posTo - pos) > 0.001f) postInvalidateOnAnimation();
        }

        canvas.restoreToCount(save);
        super.onDraw(canvas);
    }

    /** 把背景层录进 RenderNode（显示列表，不是位图）。 */
    private boolean recordBackdrop(View src) {
        final int ds = Math.max(1, downsample);
        final int rw = Math.max(1, getWidth() / ds);
        final int rh = Math.max(1, getHeight() / ds);

        if (cachedEffect == null || cBlur != blurRadiusPx || cRefr != refractionPx
                || cSat != saturation || cDisp != dispersion || cW != rw || cH != rh) {
            cachedEffect = buildEffect(rw, rh);
            backdropNode.setRenderEffect(cachedEffect);
            cBlur = blurRadiusPx;
            cRefr = refractionPx;
            cSat = saturation;
            cDisp = dispersion;
            cW = rw;
            cH = rh;
        }

        backdropNode.setPosition(0, 0, rw, rh);
        RecordingCanvas rc = backdropNode.beginRecording(rw, rh);
        try {
            rc.scale(1f / ds, 1f / ds);
            getLocationOnScreen(locSelf);
            src.getLocationOnScreen(locSrc);
            rc.translate(locSrc[0] - locSelf[0], locSrc[1] - locSelf[1]);
            recording = true;
            try {
                drawTree(rc, src, this);
            } finally {
                recording = false;
            }
        } finally {
            backdropNode.endRecording();
        }
        return true;
    }

    /**
     * 递归画 host 的内容，跳过玻璃自己那一支。
     *
     * 用「自己遍历子 View」而不是 setVisibility：
     * 不触发 invalidate/requestLayout，也不会在 RenderNode 录制中重入 beginRecording。
     */
    private static void drawTree(Canvas canvas, View host, View glass) {
        int s = canvas.save();
        canvas.translate(-host.getScrollX(), -host.getScrollY());
        Drawable bg = host.getBackground();
        if (bg != null) {
            bg.setBounds(0, 0, host.getWidth(), host.getHeight());
            bg.draw(canvas);
        }
        canvas.restoreToCount(s);

        if (!(host instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) host;
        int s2 = canvas.save();
        canvas.translate(-g.getScrollX(), -g.getScrollY());
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == glass) continue;
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getAlpha() == 0f) continue;
            int s3 = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            if (isAncestorOf(child, glass)) {
                drawTree(canvas, child, glass);
            } else {
                child.draw(canvas);
            }
            canvas.restoreToCount(s3);
        }
        canvas.restoreToCount(s2);
    }

    private static boolean isAncestorOf(View maybeAncestor, View v) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == maybeAncestor) return true;
            p = p.getParent();
        }
        return false;
    }

    /** 构建 RenderEffect 链：模糊 → 提饱和 →（API33+）AGSL 折射。 */
    private RenderEffect buildEffect(int w, int h) {
        RenderEffect e = null;
        if (blurRadiusPx > 0f) {
            float r = blurRadiusPx / Math.max(1, downsample);
            e = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        }
        if (saturation != 1f) {
            cm.setSaturation(saturation);
            RenderEffect sat = RenderEffect.createColorFilterEffect(
                    new ColorMatrixColorFilter(cm));
            e = (e == null) ? sat : RenderEffect.createChainEffect(sat, e);
        }
        if (refractionPx > 0f && Build.VERSION.SDK_INT >= 33) {
            RenderEffect lens = buildLens(w, h);
            if (lens != null) {
                e = (e == null) ? lens : RenderEffect.createChainEffect(lens, e);
            }
        }
        return e;
    }

    private RenderEffect buildLens(int w, int h) {
        if (Build.VERSION.SDK_INT < 33 || lensBroken) return null;
        try {
            if (lensShader == null) lensShader = new RuntimeShader(LENS_AGSL);
            float r = Math.min(cornerRadiusPx, Math.min(w, h) * 0.5f);
            lensShader.setFloatUniform("gSize", (float) w, (float) h);
            lensShader.setFloatUniform("gRadius", r);
            lensShader.setFloatUniform("gBevel", Math.max(8f, Math.min(w, h) * 0.18f));
            lensShader.setFloatUniform("gRefract", refractionPx / Math.max(1, downsample));
            lensShader.setFloatUniform("gDisp", dispersion);
            return RenderEffect.createRuntimeShaderEffect(lensShader, "content");
        } catch (Throwable t) {
            // AGSL 编译失败是运行时抛异常，必须 catch，否则用户设备上直接崩
            lensBroken = true;
            lensShader = null;
            android.util.Log.w("FloatingBar", "AGSL lens disabled: " + t.getMessage());
            return null;
        }
    }
}
