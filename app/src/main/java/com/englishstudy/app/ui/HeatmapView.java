package com.englishstudy.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Streak;

import java.util.List;

import android.os.Build;

/**
 * 打卡热力图：最近 12 周，每格一天，颜色深浅表示当天复习量。
 *
 * 纯自绘 View，不依赖任何图表库。
 */
public class HeatmapView extends View {

    private final android.graphics.Paint paint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private int[] levels;

    /** 按主题主色派生 5 级深浅，深色浅色主题都能看清。 */
    private int[] levels() {
        if (levels != null) return levels;
        int base = Theme.accentColor(getContext());
        boolean light = Theme.light();
        int[] lv = new int[5];
        lv[0] = light ? 0xFFE4E0D6 : 0xFF2A3442;
        for (int i = 1; i <= 4; i++) {
            float f = 0.35f + i * 0.16f;      // 0.51 / 0.67 / 0.83 / 0.99
            int r = (int) (((base >> 16) & 0xFF) * f);
            int g = (int) (((base >> 8) & 0xFF) * f);
            int b = (int) ((base & 0xFF) * f);
            if (light) {
                // 浅色主题：把颜色压暗一点，否则在白底上看不见
                r = Math.min(255, r + 40);
                g = Math.min(255, g + 40);
                b = Math.min(255, b + 40);
            }
            lv[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        levels = lv;
        return lv;
    }
    private List<Streak.Day> days;
    private int max;
    private int measuredCell;
    private int measuredGap;

    private static final int WEEKS = 20;
    private static final boolean DEBUG_LOG = false;


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


    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int full = MeasureSpec.getSize(wSpec);
        if (full <= 0) {
            full = getResources().getDisplayMetrics().widthPixels
                    - U.px(getContext(), 16) * 2 - U.px(getContext(), 16) * 2;
        }
        int gap = U.px(getContext(), 2);
        // 宽度铺满父容器；格子 = 可用宽 / 周数，不设上限
        int cell = Math.max(1, (full - gap * (WEEKS - 1)) / WEEKS);
        measuredCell = cell;
        measuredGap = gap;
        setMeasuredDimension(full, cell * 7 + gap * 6);
        if (DEBUG_LOG) {
            android.util.Log.d("Heatmap", "measure mode=" + MeasureSpec.getMode(wSpec)
                    + " size=" + MeasureSpec.getSize(wSpec) + " full=" + full
                    + " cell=" + cell + " weeks=" + WEEKS);
        }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        if (days == null || days.isEmpty()) return;
        float gap = measuredGap > 0 ? measuredGap : U.px(getContext(), 2);
        float cell = measuredCell > 0 ? measuredCell
                : Math.max(1, (getWidth() - gap * (WEEKS - 1)) / WEEKS);
        float r = cell * 0.24f;

        // days 是「最近 WEEKS*7 天，从旧到新」，最后一格是今天。
        // 从右往左排：最后一列放最后 7 天，这样热力图永远贴着右边生长。
        int n = days.size();
        for (int i = 0; i < n; i++) {
            int fromEnd = n - 1 - i;          // 0 = 今天
            int week = fromEnd / 7;           // 0 = 最右列
            int dow = fromEnd % 7;            // 0 = 今天所在行
            Streak.Day d = days.get(i);
            float x = (WEEKS - 1 - week) * (cell + gap);
            float y = (6 - dow) * (cell + gap);
            paint.setColor(levels()[level(d.count)]);
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
        TextView big = U.bold(a, String.valueOf(Streak.current()), 26,
                Theme.c(a, R.color.primary));
        top.addView(big);
        TextView unit = U.tv(a, " 天连续", 12, Theme.c(a, R.color.text_dim));
        unit.setPadding(0, U.px(a, 4), 0, 0);
        top.addView(unit);
        top.addView(U.hspacer(a, 14));
        top.addView(U.tv(a, "最长 " + Streak.longest() + " 天", 11,
                Theme.c(a, R.color.text_dim2)));
        top.addView(U.hspacer(a, 10));
        top.addView(U.tv(a, "累计 " + Streak.total() + " 次", 11,
                Theme.c(a, R.color.text_dim2)));
        box.addView(top);

        box.addView(U.spacer(a, 10));
        HeatmapView hm = new HeatmapView(a);
        // 必须显式 MATCH_PARENT：垂直线性布局里 wrap_content 会拿不到全宽
        box.addView(hm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        box.addView(U.spacer(a, 6));
        LinearLayout legend = U.row(a);
        legend.setGravity(Gravity.END);
        legend.addView(U.tv(a, "少 ", 10, Theme.c(a, R.color.text_dim2)));
        HeatmapView probe = new HeatmapView(a);
        for (int color : probe.levels()) {
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
        legend.addView(U.tv(a, " 多", 10, Theme.c(a, R.color.text_dim2)));
        box.addView(legend);
        return box;
    }
}
