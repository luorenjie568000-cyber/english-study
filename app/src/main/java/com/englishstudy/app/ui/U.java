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

    /** 圆角卡片容器。 */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackgroundResource(R.drawable.bg_card);
        int p = px(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 小圆角标签。 */
    public static LinearLayout pill(Context c, String text, int color) {
        LinearLayout box = row(c);
        box.setBackgroundResource(R.drawable.bg_pill);
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

    /** 主按钮：蓝底白字。 */
    public static TextView primaryButton(Context c, String text) {
        TextView t = bold(c, text, 15, 0xFFFFFFFF);
        t.setBackgroundResource(R.drawable.bg_primary);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(c, 13), 0, px(c, 13));
        return t;
    }

    /** 次级按钮：描边。 */
    public static TextView ghostButton(Context c, String text, int color) {
        TextView t = tv(c, text, 14, color);
        t.setBackgroundResource(R.drawable.bg_tile);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(c, 12), 0, px(c, 12));
        return t;
    }
}
