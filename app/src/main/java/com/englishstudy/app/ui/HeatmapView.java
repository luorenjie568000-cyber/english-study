package com.englishstudy.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Streak;

import java.util.List;

/**
 * 打卡热力图：最近 12 周，每格一天，颜色深浅表示当天复习量。
 *
 * 纯自绘 View，不依赖任何图表库。
 */
public class HeatmapView extends View {

    private final android.graphics.Paint paint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private List<Streak.Day> days;
    private int max;

    private static final int WEEKS = 8;
    private static final int[] LEVELS = {
            0xFF1D2635,   // 0 无记录
            0xFF2C4A6E,   // 1
            0xFF3563A8,   // 2
            0xFF4F7CFF,   // 3
            0xFF7FA3FF,   // 4 最多
    };

    public HeatmapView(Activity a) {
        super(a);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        reload();
    }

    public void reload() {
        days = Streak.recent(WEEKS * 7);
        max = 1;
        for (Streak.Day d : days) if (d.count > max) max = d.count;
        invalidate();
    }

    /** 格子固定小尺寸，不要把整行撑满——否则热力图会变成一面墙。 */
    private int cellSize() {
        int gap = U.px(getContext(), 2);
        int avail = getWidth() - gap * (WEEKS - 1);
        int byWidth = avail / WEEKS;
        int cap = U.px(getContext(), 13);
        return Math.max(1, Math.min(cap, byWidth));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int gap = U.px(getContext(), 2);
        int cell = U.px(getContext(), 13);
        int w = cell * WEEKS + gap * (WEEKS - 1);
        int h = cell * 7 + gap * 6;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (days == null || days.isEmpty()) return;
        float gap = U.px(getContext(), 2);
        float cell = cellSize();
        float r = cell * 0.24f;

        for (int i = 0; i < days.size(); i++) {
            int week = i / 7;
            int dow = i % 7;
            Streak.Day d = days.get(i);
            float x = week * (cell + gap);
            float y = dow * (cell + gap);
            paint.setColor(LEVELS[level(d.count)]);
            canvas.drawRoundRect(x, y, x + cell, y + cell, r, r, paint);
        }
    }

    private int level(int count) {
        if (count <= 0) return 0;
        double ratio = count / (double) max;
        if (ratio > 0.75) return 4;
        if (ratio > 0.5) return 3;
        if (ratio > 0.25) return 2;
        return 1;
    }

    /** 把「连续天数 + 最长 + 总计」做成一行大字。 */
    public static LinearLayout summary(Activity a, Runnable onRefresh) {
        LinearLayout box = U.col(a);

        LinearLayout top = U.row(a);
        LinearLayout left = U.col(a);
        TextView big = U.bold(a, String.valueOf(Streak.current()), 40,
                a.getColor(R.color.primary));
        left.addView(big);
        left.addView(U.tv(a, "天连续打卡", 12, a.getColor(R.color.text_dim)));
        top.addView(left, U.weight(1));

        LinearLayout right = U.col(a);
        right.setGravity(Gravity.END);
        right.addView(U.tv(a, "最长 " + Streak.longest() + " 天", 12,
                a.getColor(R.color.text_dim)));
        right.addView(U.spacer(a, 4));
        right.addView(U.tv(a, "累计复习 " + Streak.total() + " 次", 12,
                a.getColor(R.color.text_dim2)));
        top.addView(right);
        box.addView(top);

        box.addView(U.spacer(a, 12));
        HeatmapView hm = new HeatmapView(a);
        box.addView(hm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        box.addView(U.spacer(a, 6));
        LinearLayout legend = U.row(a);
        legend.setGravity(Gravity.END);
        legend.addView(U.tv(a, "少 ", 10, a.getColor(R.color.text_dim2)));
        for (int color : LEVELS) {
            View v = new View(a);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setColor(color);
            gd.setCornerRadius(U.px(a, 2));
            v.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    U.px(a, 9), U.px(a, 9));
            lp.leftMargin = U.px(a, 2);
            lp.rightMargin = U.px(a, 2);
            legend.addView(v, lp);
        }
        legend.addView(U.tv(a, " 多", 10, a.getColor(R.color.text_dim2)));
        box.addView(legend);
        return box;
    }
}
