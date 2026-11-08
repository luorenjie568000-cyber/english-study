package com.englishstudy.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;

/** 纯代码构建 UI 的工具集，避免大量 XML 样板。 */
public final class U {

    private U() {
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static int px(Context c, float dp) {
        return Math.round(dp(c, dp));
    }

    public static TextView tv(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView bold(Context c, String text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 圆角卡片容器（玻璃质感由 Glass 决定，关闭时退回纯色）。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        Glass.apply(l, Theme.cardColor(c), c);
        int p = px(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 次级卡片：比主卡片更深一点，用于嵌套信息块。 */
    public static LinearLayout cardAlt(Context c) {
        LinearLayout l = col(c);
        Glass.apply(l, Glass.withAlpha(Theme.cardColor(c), 0xF0), c);
        int p = px(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 小圆角标签。 */
    public static LinearLayout pill(Context c, String text, int color) {
        LinearLayout box = row(c);
        box.setBackground(pillBg(c));
        int px = px(c, 10), py = px(c, 4);
        box.setPadding(px, py, px, py);
        box.addView(tv(c, text, 11, color));
        return box;
    }

    public static View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(c, dp)));
        return v;
    }

    public static View hspacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(c, dp), 1));
        return v;
    }

    /** 1dp 分隔线。 */
    public static View divider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(c, 1))));
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams weight(float wgt) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.weight = wgt;
        return p;
    }

    /** 圆角胶囊背景（跟随主题）。 */
    public static android.graphics.drawable.GradientDrawable pillBg(Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(c, 999));
        g.setColor(Glass.withAlpha(Theme.cardColor(c), 0xE0));
        g.setStroke(Math.max(1, px(c, 1)), Theme.c(c, R.color.line));
        return g;
    }

    /** 圆角方块背景（跟随主题）。 */
    public static android.graphics.drawable.GradientDrawable tileBg(Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(c, 14));
        g.setColor(Glass.withAlpha(Theme.cardColor(c), 0xF0));
        g.setStroke(Math.max(1, px(c, 1)), Theme.c(c, R.color.line));
        return g;
    }

    /** 主题色圆角背景（主按钮 / 自己发的消息气泡）。 */
    public static android.graphics.drawable.GradientDrawable primaryBg(Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(c, 14));
        g.setColor(Theme.c(c, R.color.primary));
        return g;
    }

    /** 圆角气泡背景（对方的消息）。 */
    public static android.graphics.drawable.GradientDrawable bubbleBg(Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(c, 14));
        g.setColor(Glass.withAlpha(Theme.cardColor(c), 0xE8));
        g.setStroke(Math.max(1, px(c, 1)), Theme.c(c, R.color.line));
        return g;
    }

    /** 主按钮：主题色底 + 白字。 */
    public static TextView primaryButton(Context c, String text) {
        TextView t = bold(c, text, 15, Theme.c(c, R.color.white));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(px(c, 14));
        g.setColor(Theme.c(c, R.color.primary));
        t.setBackground(g);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(c, 13), 0, px(c, 13));
        return t;
    }

    /** 次级按钮：描边。 */
    public static TextView ghostButton(Context c, String text, int color) {
        TextView t = tv(c, text, 14, color);
        t.setBackground(tileBg(c));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(c, 12), 0, px(c, 12));
        return t;
    }
}
