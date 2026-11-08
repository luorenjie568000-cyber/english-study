package com.englishstudy.app.ui;

import android.content.Context;
import com.englishstudy.app.R;

/**
 * 主题：运行时切换配色（对应网页版的 5 套主题）。
 *
 * 实现方式：不改 XML 资源（改不了），而是维护一份「当前主题」的颜色表，
 * 所有 UI 取色统一走 Theme.c(R.color.xxx)。
 *
 * 注意：已有代码里大量 a.getColor(R.color.xxx)，为了不全部改一遍，
 * 这里用 attachBaseContext 包装 Resources 太重；改用 Activity 重建 ——
 * 切换主题后调 recreate()，并在 MainActivity 里把 Theme 应用到根 View 上。
 * 颜色表本身是静态的，所以 getColor 的返回值在重建后就生效。
 */
public final class Theme {

    public static final int NIGHT = 0;   // 深蓝（默认）
    public static final int FOREST = 1;  // 森林
    public static final int SUNSET = 2;  // 日落
    public static final int PAPER = 3;   // 纸墨（浅色）
    public static final int MIDNIGHT = 4;// 纯黑（OLED）

    private Theme() {
    }

    /** 一套配色的全部色值。 */
    private static final class Palette {
        final int bg, bg2, card, card2, line, text, textDim, textDim2, primary, accent;

        Palette(int bg, int bg2, int card, int card2, int line,
                int text, int textDim, int textDim2, int primary, int accent) {
            this.bg = bg;
            this.bg2 = bg2;
            this.card = card;
            this.card2 = card2;
            this.line = line;
            this.text = text;
            this.textDim = textDim;
            this.textDim2 = textDim2;
            this.primary = primary;
            this.accent = accent;
        }
    }

    private static final Palette[] PALETTES = {
            // NIGHT
            new Palette(0xFF0B0F14, 0xFF111721, 0xFF161E2A, 0xFF1D2635, 0xFF25303F,
                    0xFFE8EEF7, 0xFF8A9AB0, 0xFF5F7085, 0xFF4F7CFF, 0xFF34D399),
            // FOREST
            new Palette(0xFF0D1512, 0xFF131F1A, 0xFF17241F, 0xFF1D2E27, 0xFF24382E,
                    0xFFE4EFE8, 0xFF8AA899, 0xFF5E7A6B, 0xFF3ECF8E, 0xFF7BD9AF),
            // SUNSET
            new Palette(0xFF160F14, 0xFF211620, 0xFF271A26, 0xFF32202F, 0xFF3D2A39,
                    0xFFF3E8EE, 0xFFB394A8, 0xFF7E6473, 0xFFFF7A59, 0xFFFFA07A),
            // PAPER（浅色）
            new Palette(0xFFF4F1EA, 0xFFFBF9F4, 0xFFFDFBF6, 0xFFF0EBE1, 0xFFDDD5C6,
                    0xFF2B2620, 0xFF6B6257, 0xFF8D8377, 0xFF8A6D3B, 0xFF4E8A6A),
            // MIDNIGHT（纯黑 OLED）
            new Palette(0xFF000000, 0xFF08090B, 0xFF0E1013, 0xFF14171B, 0xFF1E2228,
                    0xFFE6EAF0, 0xFF8B939F, 0xFF5A6270, 0xFF5B8DFF, 0xFF3DDBA4),
    };

    private static final String[] NAMES = {"深蓝", "森林", "日落", "纸墨", "纯黑"};

    public static int current() {
        return Math.max(0, Math.min(4, com.englishstudy.app.data.Store.getInt("theme", NIGHT)));
    }

    public static void set(int id) {
        com.englishstudy.app.data.Store.putInt("theme", Math.max(0, Math.min(4, id)));
    }

    public static String name(int id) {
        return NAMES[Math.max(0, Math.min(4, id))];
    }

    public static String[] names() {
        return NAMES.clone();
    }

    /** 浅色主题需要把文字反色。 */
    public static boolean light() {
        return current() == PAPER;
    }

    // ---------------------------------------------------------------- 取色

    /**
     * 取当前主题下的颜色。
     *
     * @param colorRes 现有的 R.color.* 资源 id
     */
    public static int c(Context ctx, int colorRes) {
        Palette p = PALETTES[current()];
        int id = R.color.bg;
        try {
            id = colorRes;
        } catch (Throwable ignored) {
        }

        if (id == R.color.bg) return p.bg;
        if (id == R.color.bg2) return p.bg2;
        if (id == R.color.card) return p.card;
        if (id == R.color.card2) return p.card2;
        if (id == R.color.card_alt) return p.card2;
        if (id == R.color.line) return p.line;
        if (id == R.color.text) return p.text;
        if (id == R.color.text_dim) return p.textDim;
        if (id == R.color.text_dim2) return p.textDim2;
        if (id == R.color.primary) return p.primary;
        if (id == R.color.primary_dark) return darken(p.primary, 0.75f);
        if (id == R.color.primary_soft) return Glass.withAlpha(p.primary, 0x33);
        if (id == R.color.accent || id == R.color.ok) return p.accent;
        if (id == R.color.accent_soft) return Glass.withAlpha(p.accent, 0x55);
        if (id == R.color.purple) return p.primary;
        return 0xFF888888;
    }

    private static int darken(int c, float f) {
        int r = (int) (((c >> 16) & 0xFF) * f);
        int g = (int) (((c >> 8) & 0xFF) * f);
        int b = (int) ((c & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 当前主题的主色，给 Glass.Backdrop 用。 */
    public static int accentColor(Context ctx) {
        return PALETTES[current()].primary;
    }

    public static int accent2(Context ctx) {
        return PALETTES[current()].accent;
    }

    public static int bgColor(Context ctx) {
        return PALETTES[current()].bg;
    }

    public static int cardColor(Context ctx) {
        return PALETTES[current()].card;
    }
}
