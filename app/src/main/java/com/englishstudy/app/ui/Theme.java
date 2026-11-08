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
            // NIGHT —— 类原生深色（Material 3 Dark）
            new Palette(0xFF0E1116, 0xFF161A20, 0xFF1C2026, 0xFF242931, 0xFF2E343D,
                    0xFFE6E8EB, 0xFFA8ADB5, 0xFF8A9099, 0xFF8AB4F8, 0xFF81C995),
            // FOREST —— 类原生绿（Material You 衍生）
            new Palette(0xFF0F1512, 0xFF141C18, 0xFF1A231E, 0xFF212C26, 0xFF2A3630,
                    0xFFE6EFE8, 0xFFA8BDB0, 0xFF88998F, 0xFF7BD9A5, 0xFFA5E8C4),
            // SUNSET —— 类原生暖橙
            new Palette(0xFF17110E, 0xFF221A16, 0xFF2A211C, 0xFF342924, 0xFF40332C,
                    0xFFF2E9E2, 0xFFC2B0A4, 0xFF9C8B80, 0xFFFFB59E, 0xFFFFD1B8),
            // PAPER —— 类原生浅色（Material 3 Light：纯白卡 + 中性灰底）
            new Palette(0xFFF6F7F9, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFF1F3F5, 0xFFDDE1E6,
                    0xFF1A1C1E, 0xFF44474B, 0xFF73777C, 0xFF1A73E8, 0xFF1E8E5A),
            // MIDNIGHT —— 纯黑 OLED（类原生 + 省电）
            new Palette(0xFF000000, 0xFF0A0C0F, 0xFF101316, 0xFF171A1F, 0xFF22262C,
                    0xFFE6E8EB, 0xFFA8ADB5, 0xFF7C828A, 0xFF8AB4F8, 0xFF81C995),
    };

    private static final String[] NAMES = {"深色", "森林", "暖橙", "浅色", "纯黑"};

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
        // R.color.white：深色主题就是白，浅色主题要变成深色（否则白底白字看不见）
        if (id == R.color.white) return light() ? 0xFF1A1F26 : 0xFFFFFFFF;
        if (id == R.color.black) return light() ? 0xFFFFFFFF : 0xFF000000;
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
