package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Store;

/**
 * 液态玻璃（Liquid Glass）。
 *
 * 依据 docs/liquid-glass-spec.md 的落地清单 P0 部分实现。核心认知：
 *
 *   玻璃质感来自「边缘光 + 高光 + 内阴影」三层叠加，不是模糊。
 *   只做模糊 = 2020 年的毛玻璃，看起来很廉价。
 *
 *   而且 Android 上 View.setRenderEffect 模糊的是 View 自己画出来的内容，
 *   不是它背后 —— 给卡片加它等于把卡片里的文字糊掉，所以整个方案不用它。
 *
 * 层序（下 → 上）：
 *   1. 填充（暗色，不是白色！白色叠加在深色底上会发灰去饱和）
 *   2. 底部内阴影（做厚度）
 *   3. 边缘光（TL→BR 渐变：光从左上来，上边缘比下边缘亮）
 *   4. 镜面高光（左上角径向，峰值在宽度 24% 处）
 *
 * 4 档强度：关闭 / 轻 / 标准 / 强，存 Store("glass")。
 * 关闭档完全退回纯色 GradientDrawable，0 成本。
 */
public final class Glass {

    public static final int OFF = 0;
    public static final int LIGHT = 1;
    public static final int NORMAL = 2;
    public static final int STRONG = 3;

    private Glass() {
    }

    // ---------------------------------------------------------------- 强度

    public static int level() {
        return Math.max(0, Math.min(3, Store.getInt("glass", NORMAL)));
    }

    public static void setLevel(int v) {
        Store.putInt("glass", Math.max(0, Math.min(3, v)));
    }

    public static String levelName() {
        switch (level()) {
            case OFF: return "关闭";
            case LIGHT: return "轻";
            case STRONG: return "强";
            default: return "标准";
        }
    }

    public static boolean canBlur() {
        return Build.VERSION.SDK_INT >= 31;
    }

    // ---------------------------------------------------------------- 参数表

    /**
     * 一档玻璃的全部数值参数（深色专用，来自规范 §4.1 的实测表）。
     * 浅色主题会把 fillAlpha 抬高自适应（见 drawingContext）。
     */
    static final class Spec {
        int fillAlpha;
        int rimTopAlpha;
        int rimBottomAlpha;
        float rimWidthDp;
        int sheenAlpha;
        int innerShadeAlpha;
        int glowAlpha;
        float glowRadiusDp;
        float glowDyDp;
        float radiusDp;
    }

    static Spec spec(int tier) {
        Spec s = new Spec();
        switch (tier) {
            case LIGHT:
                s.fillAlpha = 0xF0;
                s.rimTopAlpha = 0x38;
                s.rimBottomAlpha = 0x0E;
                s.rimWidthDp = 1f;
                s.sheenAlpha = 0x14;
                s.innerShadeAlpha = 0x0C;
                s.glowAlpha = 0x00;
                s.glowRadiusDp = 0f;
                s.glowDyDp = 0f;
                s.radiusDp = 18f;
                break;
            case STRONG:
                s.fillAlpha = 0x9E;
                s.rimTopAlpha = 0x80;
                s.rimBottomAlpha = 0x1F;
                s.rimWidthDp = 2f;
                s.sheenAlpha = 0x2E;
                s.innerShadeAlpha = 0x1C;
                s.glowAlpha = 0x33;
                s.glowRadiusDp = 8f;
                s.glowDyDp = 3f;
                s.radiusDp = 20f;
                break;
            case NORMAL:
                s.fillAlpha = 0xC8;
                s.rimTopAlpha = 0x59;
                s.rimBottomAlpha = 0x14;
                s.rimWidthDp = 1.5f;
                s.sheenAlpha = 0x1F;
                s.innerShadeAlpha = 0x14;
                s.glowAlpha = 0x26;
                s.glowRadiusDp = 6f;
                s.glowDyDp = 2f;
                s.radiusDp = 18f;
                break;
            default: // OFF
                s.fillAlpha = 0xFF;
                s.rimTopAlpha = 0x26;
                s.rimBottomAlpha = 0x26;
                s.rimWidthDp = 1f;
                s.sheenAlpha = 0;
                s.innerShadeAlpha = 0;
                s.glowAlpha = 0;
                s.glowRadiusDp = 0f;
                s.glowDyDp = 0f;
                s.radiusDp = 16f;
                break;
        }
        return s;
    }

    // ---------------------------------------------------------------- 应用

    /**
     * 给任意 View 套上玻璃背景。
     *
     * 外发光会被 View 自身边界裁掉，所以给四周补 padding 作为"留白给阴影"，
     * Drawable 再把内容区 inset 回来 —— 视觉尺寸不变，多余空间留给光晕。
     */
    public static void apply(View v, int baseColor, Context c) {
        if (level() == OFF) {
            v.setBackground(flat(c, baseColor, U.px(c, 16)));
            return;
        }
        Spec s = spec(level());
        // 只留够画光晕的最小边距；留太多会把卡片内容挤窄
        int room = s.glowAlpha > 0 ? Math.round(s.glowDyDp * dens(c)) : 0;
        if (room > 0) {
            v.setPadding(v.getPaddingLeft() + room, v.getPaddingTop() + room,
                    v.getPaddingRight() + room, v.getPaddingBottom() + room);
        }
        v.setBackground(new GlassDrawable(c, s, baseColor, room,
                Theme.c(c, R.color.primary)));
    }

    /** 顶栏 / 底栏：通栏不投影、不做圆角。 */
    public static void applyBar(View v, Context c) {
        if (level() == OFF) {
            v.setBackgroundColor(Theme.light() ? 0xFFFBF9F4 : 0xFF111721);
            return;
        }
        Spec s = spec(level());
        s.fillAlpha = Math.min(0xFF, s.fillAlpha + 0x14);
        s.radiusDp = 0f;
        s.glowAlpha = 0;
        s.glowRadiusDp = 0;
        int barBase = Theme.light() ? 0xFFFBF9F4 : 0xFF111721;
        v.setBackground(new GlassDrawable(c, s, barBase, 0, Theme.c(c, R.color.primary)));
    }

    /** 选中态玻璃药丸。 */
    public static Drawable pill(Context c, int accent) {
        Spec s = spec(level());
        s.fillAlpha = level() == OFF ? 0x22 : 0x3A;
        s.rimTopAlpha = Math.min(0xFF, s.rimTopAlpha + 0x18);
        s.rimBottomAlpha = 0x10;
        s.glowAlpha = 0;
        s.glowRadiusDp = 0;
        s.radiusDp = 16f;
        return new GlassDrawable(c, s, accent, 0, accent);
    }

    private static GradientDrawable flat(Context c, int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radiusPx);
        g.setColor(color);
        g.setStroke(Math.max(1, U.px(c, 1)), Theme.c(c, R.color.line));
        return g;
    }

    private static float dens(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- 可选模糊

    /**
     * 抓一张「背后内容的模糊快照」，给玻璃当底图。
     *
     * 这是让玻璃"真的是玻璃"的关键：之前只是一个半透明贴图，
     * 内容滚动时底下什么也透不出来，看起来就像贴纸。
     *
     * 做法：把目标 View 画到 1/4 分辨率的 Bitmap 上，三遍盒式模糊（≈高斯），
     * 再让玻璃面板把这张图当背景画。成本实测 270×600 ≈ 2.9ms。
     *
     * @param source 要被模糊的内容层（不是玻璃自己）
     * @param outW 玻璃面板的宽
     * @param outH 玻璃面板的高
     * @param radiusPx 模糊半径（按 outW/源宽 的比例缩放）
     */
    public static android.graphics.Bitmap blurBehind(View source, int outW, int outH,
                                                     float radiusPx) {
        return blurBehind(source, null, outW, outH, radiusPx);
    }

    /**
     * 抓「source 上某个矩形区域」并模糊。
     *
     * 之前的 bug：整页 draw 之后缩放到玻璃尺寸 → 玻璃里显示的是整页的缩略图，
     * 完全对不上位置，看起来就是一块灰糊。必须按 region 精确裁剪。
     */
    public static android.graphics.Bitmap blurBehind(View source, android.graphics.Rect region,
                                                     int outW, int outH, float radiusPx) {
        if (source == null || outW <= 0 || outH <= 0) return null;
        int sw = source.getWidth(), sh = source.getHeight();
        if (sw <= 0 || sh <= 0) return null;
        try {
            if (region == null) region = new android.graphics.Rect(0, 0, sw, sh);

            // 1/4 分辨率抓整页，再从里面 cut 出对应区域（比直接 draw(region) 稳）
            float scale = 0.25f;
            int fw = Math.max(1, (int) (sw * scale));
            int fh = Math.max(1, (int) (sh * scale));
            android.graphics.Bitmap full = android.graphics.Bitmap.createBitmap(
                    fw, fh, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(full);
            cv.scale(scale, scale);
            source.draw(cv);

            int x = Math.max(0, Math.round(region.left * scale));
            int y = Math.max(0, Math.round(region.top * scale));
            int w = Math.max(1, Math.round(region.width() * scale));
            int h = Math.max(1, Math.round(region.height() * scale));
            if (x + w > fw) w = fw - x;
            if (y + h > fh) h = fh - y;
            if (w <= 0 || h <= 0) {
                full.recycle();
                return null;
            }

            android.graphics.Bitmap cut = android.graphics.Bitmap.createBitmap(
                    full, x, y, Math.max(1, w), Math.max(1, h));
            full.recycle();

            boxBlur(cut, Math.max(2, Math.round(radiusPx * scale)));
            return cut;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 三遍盒式模糊近似高斯：O(n) 滑动窗口，与半径无关。 */
    public static void boxBlur(android.graphics.Bitmap bmp, int radius) {
        if (radius < 1) return;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 2 || h < 2) return;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, w, h, radius);
            boxV(px, w, h, radius);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxH(int[] p, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = p[row + clamp(i, w)];
                sa += c >>> 24; sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                p[row + x] = ((int) (sa / div) << 24) | ((int) (sr / div) << 16)
                        | ((int) (sg / div) << 8) | (int) (sb / div);
                int out = p[row + clamp(x - r, w)];
                int in = p[row + clamp(x + r + 1, w)];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void boxV(int[] p, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            long sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = p[clamp(i, h) * w + x];
                sa += c >>> 24; sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                p[y * w + x] = ((int) (sa / div) << 24) | ((int) (sr / div) << 16)
                        | ((int) (sg / div) << 8) | (int) (sb / div);
                int out = p[clamp(y - r, h) * w + x];
                int in = p[clamp(y + r + 1, h) * w + x];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /**
     * 给「被玻璃盖住的内容层」加真模糊（API 31+）。
     *
     * 注意作用对象：不是玻璃面板，是它下面的内容层。
     * 全屏糊会掉帧，只适合固定条带（底栏高度那一条）。
     */
    public static boolean blurContent(View content, float radiusPx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        content.setRenderEffect(radiusPx <= 0f ? null
                : android.graphics.RenderEffect.createBlurEffect(
                radiusPx, radiusPx, Shader.TileMode.CLAMP));
        return true;
    }

    // ---------------------------------------------------------------- 绘制器

    /** 玻璃背景绘制器。5 个 Paint 复用，避免每帧 GC。全部 API 24+ 可用。 */
    public static final class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float density;
        private final Spec s;
        private final int padPx;
        private final int base;
        private final int accent;

        public GlassDrawable(Context c, Spec spec, int baseColor, int padPx, int accent) {
            this.density = c.getResources().getDisplayMetrics().density;
            this.s = spec;
            this.base = baseColor;
            this.padPx = padPx;
            this.accent = accent;
            fill.setStyle(Paint.Style.FILL);
            rim.setStyle(Paint.Style.STROKE);
            sheen.setStyle(Paint.Style.FILL);
            shade.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            r.set(bounds);
            if (padPx > 0) r.inset(padPx, padPx);
            rebuild();
        }

        private void rebuild() {
            float rad = s.radiusDp * density;

            // 1) 填充：暗色 + alpha。浅色主题不能太透，否则白底上发灰
            int alpha = s.fillAlpha;
            if (Theme.light()) alpha = Math.min(0xFF, alpha + 0x20);
            fill.setColor(withAlpha(base, alpha));
            fill.clearShadowLayer();

            // 外发光：用主色而不是黑色 —— 深色底上黑投影看不见还显脏
            if (s.glowAlpha > 0 && Build.VERSION.SDK_INT >= 28) {
                fill.setShadowLayer(s.glowRadiusDp * density, 0f, s.glowDyDp * density,
                        withAlpha(accent, s.glowAlpha));
            }

            // 2) 边缘光：TL→BR 渐变。光从左上来，上边缘亮下边缘暗。
            //    均匀白描边看起来是"白框"，一眼假。
            rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    withAlpha(0xFFFFFFFF, s.rimTopAlpha),
                    withAlpha(0xFFFFFFFF, s.rimBottomAlpha),
                    Shader.TileMode.CLAMP));
            rim.setStrokeWidth(Math.max(1f, s.rimWidthDp * density));

            // 3) 镜面高光：左上角径向，峰值在宽度 24% / 高度 10%
            float hx = r.left + r.width() * 0.24f;
            float hy = r.top + r.height() * 0.10f;
            float hr = Math.max(r.width() * 0.62f, 1f);
            sheen.setShader(new RadialGradient(hx, hy, hr,
                    new int[]{withAlpha(0xFFFFFFFF, s.sheenAlpha), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));

            // 4) 底部内阴影：让玻璃有厚度
            shade.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT,
                            withAlpha(0xFF000000, s.innerShadeAlpha)},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            if (r.isEmpty()) {
                r.set(getBounds());
                if (padPx > 0) r.inset(padPx, padPx);
                rebuild();
            }
            float rad = s.radiusDp * density;

            canvas.drawRoundRect(r, rad, rad, fill);

            if (s.innerShadeAlpha > 0) {
                canvas.drawRoundRect(r, rad, rad, shade);
            }

            RectF rr = new RectF(r);
            float inset = Math.max(1f, s.rimWidthDp * density * 0.5f);
            rr.inset(inset, inset);
            float rr2 = Math.max(0, rad - inset);
            canvas.drawRoundRect(rr, rr2, rr2, rim);

            if (s.sheenAlpha > 0) {
                int save = canvas.save();
                canvas.clipRect(r);
                canvas.drawRoundRect(r, rad, rad, sheen);
                canvas.restoreToCount(save);
            }
        }

        @Override
        public void setAlpha(int a) {
            fill.setAlpha(a);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ---------------------------------------------------------------- 背景装饰

    /**
     * 全屏背景：深色底 + 两团彩色光晕。
     *
     * 玻璃要好看，底下必须有东西可透 —— 纯色背景上的玻璃看不出质感。
     * 规范 §1.5：加两团极淡径向光晕（α 0x1A–0x46）成本几乎为零，效果提升最大。
     */
    public static class Backdrop extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accent = 0xFF4F7CFF;
        private int accent2 = 0xFF7B5CFF;

        public Backdrop(Context c) {
            super(c);
            p.setStyle(Paint.Style.FILL);
        }

        public void setAccents(int a, int b) {
            accent = a;
            accent2 = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            canvas.drawColor(Theme.bgColor(getContext()));

            // 光晕保留（关闭玻璃档也画，因为它是背景不是玻璃）
            int strength = level() == OFF ? 0x22
                    : level() == STRONG ? 0x46 : level() == LIGHT ? 0x1A : 0x2E;

            // 左上主光
            p.setShader(new RadialGradient(w * 0.16f, h * 0.10f, w * 0.95f,
                    new int[]{withAlpha(accent, strength), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);

            // 右下辅光
            p.setShader(new RadialGradient(w * 0.94f, h * 0.82f, w * 0.85f,
                    new int[]{withAlpha(accent2, strength * 2 / 3), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);

            // 中下第三团极淡，让长页面中段不空
            p.setShader(new RadialGradient(w * 0.5f, h * 0.62f, w * 0.75f,
                    new int[]{withAlpha(accent, strength / 4), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);

            p.setShader(null);

            // 顶部/底部各压一层极淡暗角，让内容层"浮"在中间（Linear/Craft 的常用手法）
            p.setShader(new LinearGradient(0, 0, 0, h * 0.18f,
                    withAlpha(0xFF000000, Theme.light() ? 0x0A : 0x28), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.18f, p);
            p.setShader(new LinearGradient(0, h * 0.86f, 0, h,
                    Color.TRANSPARENT, withAlpha(0xFF000000, Theme.light() ? 0x0E : 0x30),
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, h * 0.86f, w, h, p);
            p.setShader(null);
        }
    }

    /**
     * 给一个 Activity 的内容套上「全局背景」。
     *
     * 之前的问题：内容不足一屏时，下方是纯黑（Activity 默认背景），
     * 和上面带光晕的区域割裂。这里用 FrameLayout 包一层，
     * 背景层永远铺满屏幕，内容层在其上。
     */
    public static android.widget.FrameLayout wrapWithBackdrop(Context c, View content) {
        android.widget.FrameLayout shell = new android.widget.FrameLayout(c);
        Backdrop bd = new Backdrop(c);
        bd.setAccents(Theme.accentColor(c), Theme.accent2(c));
        shell.addView(bd, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        shell.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        return shell;
    }

    // ---------------------------------------------------------------- 工具

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blend(int color, int overlay, float t) {
        int ar = (color >> 16) & 0xFF, ag = (color >> 8) & 0xFF, ab = color & 0xFF;
        int br = (overlay >> 16) & 0xFF, bg = (overlay >> 8) & 0xFF, bb = overlay & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int b = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
