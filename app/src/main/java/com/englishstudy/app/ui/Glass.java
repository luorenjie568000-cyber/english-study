package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import com.englishstudy.app.data.Store;

/**
 * 液态玻璃（Liquid Glass）样式工具。
 *
 * 分层思路（模仿 Apple 2025 的做法，但只用 SDK 自带能力）：
 *   1. 底色层：半透明填充，让下层内容透出来
 *   2. 模糊层：API 31+ 用 RenderEffect 真模糊；更低版本用渐变近似
 *   3. 边缘光：1px 上亮下暗的描边，模拟折射边
 *   4. 高光：左上角一层白色线性渐变，强度可调
 *
 * 强度分 4 档，存 Store("glass")，0=关 1=轻 2=标准 3=强。
 * 关掉时完全退回原来的纯色卡片（0 成本，滚动也快）。
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

    /** 是否能用 RenderEffect 做真模糊（Android 12+）。 */
    public static boolean canBlur() {
        return Build.VERSION.SDK_INT >= 31;
    }

    // ---------------------------------------------------------------- 参数

    /** 卡片底色 alpha（越强越透）。 */
    private static int fillAlpha() {
        switch (level()) {
            case LIGHT: return 0xF0;
            case STRONG: return 0xC0;
            default: return 0xDC;
        }
    }

    /** 边缘光强度。 */
    private static int edgeAlpha() {
        switch (level()) {
            case LIGHT: return 0x18;
            case STRONG: return 0x44;
            default: return 0x2C;
        }
    }

    /** 左上高光强度。 */
    private static int sheenAlpha() {
        switch (level()) {
            case LIGHT: return 0x10;
            case STRONG: return 0x30;
            default: return 0x20;
        }
    }

    public static int radius(Context c) {
        return U.px(c, level() == OFF ? 16 : 18);
    }

    // ---------------------------------------------------------------- 背景

    /**
     * 给任意 View 套上玻璃背景。
     *
     * 用 LayerDrawable 叠：底色 + 顶部高光渐变 + 描边。
     * 描边用 GradientDrawable 的 stroke，颜色没法做渐变，
     * 所以「上亮下暗」靠再叠一层极淡的顶部渐变近似。
     */
    public static void apply(View v, int baseColor, Context c) {
        if (level() == OFF) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(baseColor);
            g.setCornerRadius(radius(c));
            g.setStroke(Math.max(1, U.px(c, 1)), blend(baseColor, 0x33FFFFFF));
            v.setBackground(g);
            return;
        }

        int r = radius(c);
        int stroke = Math.max(1, U.px(c, 1));

        // 1) 主体：半透明填充 + 描边
        GradientDrawable body = new GradientDrawable();
        body.setShape(GradientDrawable.RECTANGLE);
        body.setCornerRadius(r);
        body.setColor(withAlpha(baseColor, fillAlpha()));
        body.setStroke(stroke, withAlpha(0xFFFFFFFF, edgeAlpha()));

        // 2) 顶部高光：从左上到右下的白色渐变，只覆盖上半部分
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(0xFFFFFFFF, sheenAlpha()),
                        withAlpha(0xFFFFFFFF, sheenAlpha() / 4),
                        Color.TRANSPARENT});
        sheen.setShape(GradientDrawable.RECTANGLE);
        sheen.setCornerRadius(r);
        sheen.setGradientType(GradientDrawable.LINEAR_GRADIENT);

        v.setBackground(new LayerDrawable(new Drawable[]{body, sheen}));
        // 注意：不要用 setRenderEffect —— 它模糊的是 View 自己的内容（文字会糊），
        // 不是背后。玻璃感靠「半透明 + 渐变高光 + 边缘光」在静态背景上实现。
    }

    // ---------------------------------------------------------------- 顶部/底部栏

    /**
     * 顶栏与底栏的玻璃背景：比卡片更透，因为要透出下面滚动的内容。
     */
    public static void applyBar(View v, Context c) {
        if (level() == OFF) {
            v.setBackgroundColor(Theme.light() ? 0xFFFBF9F4 : 0xFF111721);
            return;
        }
        int barBase = Theme.light() ? 0xFFFBF9F4 : 0xFF111721;
        GradientDrawable body = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(barBase, 0xF0), withAlpha(barBase, 0xD8)});
        body.setShape(GradientDrawable.RECTANGLE);

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(0xFFFFFFFF, sheenAlpha() / 2), Color.TRANSPARENT});
        sheen.setShape(GradientDrawable.RECTANGLE);

        v.setBackground(new LayerDrawable(new Drawable[]{body, sheen}));
    }

    /**
     * 选中态的玻璃药丸（底栏当前标签）。
     */
    public static Drawable pill(Context c, int accent) {
        int r = U.px(c, 14);
        GradientDrawable body = new GradientDrawable();
        body.setShape(GradientDrawable.RECTANGLE);
        body.setCornerRadius(r);
        body.setColor(withAlpha(accent, level() == OFF ? 0x22 : 0x30));
        body.setStroke(Math.max(1, U.px(c, 1)), withAlpha(accent, 0x66));

        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(0xFFFFFFFF, sheenAlpha()), Color.TRANSPARENT});
        sheen.setShape(GradientDrawable.RECTANGLE);
        sheen.setCornerRadius(r);

        return new LayerDrawable(new Drawable[]{body, sheen});
    }

    // ---------------------------------------------------------------- 颜色工具

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blend(int color, int overlay) {
        int a = (overlay >>> 24) & 0xFF;
        int r = ((color >> 16) & 0xFF) * (255 - a) / 255 + ((overlay >> 16) & 0xFF) * a / 255;
        int g = ((color >> 8) & 0xFF) * (255 - a) / 255 + ((overlay >> 8) & 0xFF) * a / 255;
        int b = (color & 0xFF) * (255 - a) / 255 + (overlay & 0xFF) * a / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- 背景装饰

    /**
     * 全屏背景：深色底 + 两团彩色光晕。
     *
     * 玻璃要好看，底下必须有点东西可透——纯色背景上的玻璃看不出质感。
     * 光晕用 RadialGradient 画，成本很低。
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
            if (level() == OFF) return;

            int strength = level() == STRONG ? 0x46 : level() == LIGHT ? 0x1A : 0x2E;

            p.setShader(new android.graphics.RadialGradient(
                    w * 0.18f, h * 0.12f, w * 0.85f,
                    new int[]{withAlpha(accent, strength), Color.TRANSPARENT},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);

            p.setShader(new android.graphics.RadialGradient(
                    w * 0.92f, h * 0.78f, w * 0.80f,
                    new int[]{withAlpha(accent2, strength / 2), Color.TRANSPARENT},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);

            p.setShader(null);
        }
    }
}
