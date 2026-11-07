package com.englishstudy.app.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.List;

/**
 * 统计小图表（纯自绘，不引第三方库）。
 *
 * 只做最有信息量的两张：
 *   1. 未来 30 天到期柱状图 —— 判断自己会不会被压垮
 *   2. 稳定度分布 —— 看长期记忆的真实厚度
 */
public final class Charts {

    private Charts() {
    }

    /** 柱状图：values 里每根柱子的高度。 */
    public static class BarChart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] values = new float[0];
        private String[] labels = new String[0];
        private int barColor = 0xFF4F7CFF;
        private int axisColor = 0xFF25303F;

        public BarChart(Activity a) {
            super(a);
        }

        public void setData(float[] v, String[] lb) {
            values = v;
            labels = lb;
            invalidate();
        }

        public void setBarColor(int c) {
            barColor = c;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w),
                    U.px(getContext(), 120));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (values == null || values.length == 0) return;
            float w = getWidth(), h = getHeight();
            float padBottom = U.px(getContext(), 16);
            float padTop = U.px(getContext(), 6);
            float usable = h - padBottom - padTop;

            float max = 1;
            for (float v : values) if (v > max) max = v;

            float gap = Math.max(1, w / values.length * 0.28f);
            float bw = (w - gap * (values.length - 1)) / values.length;
            if (bw < 1) bw = 1;

            p.setColor(axisColor);
            canvas.drawRect(0, h - padBottom, w, h - padBottom + 1, p);

            p.setColor(barColor);
            float r = Math.min(bw / 2f, U.px(getContext(), 3));
            for (int i = 0; i < values.length; i++) {
                float bh = usable * (values[i] / max);
                if (values[i] > 0 && bh < U.px(getContext(), 2)) bh = U.px(getContext(), 2);
                float x = i * (bw + gap);
                float y = h - padBottom - bh;
                canvas.drawRoundRect(new RectF(x, y, x + bw, h - padBottom), r, r, p);
            }

            // 横轴标签（只画首尾，避免挤在一起）
            p.setColor(0xFF5F7085);
            p.setTextSize(U.px(getContext(), 9));
            if (labels.length > 0) {
                canvas.drawText(labels[0], 0, h - U.px(getContext(), 4), p);
                String last = labels[labels.length - 1];
                float tw = p.measureText(last);
                canvas.drawText(last, w - tw, h - U.px(getContext(), 4), p);
            }
        }
    }

    /** 折线图：稳定度分布曲线。 */
    public static class LineChart extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] values = new float[0];

        public LineChart(Activity a) {
            super(a);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(U.px(a, 2));
            p.setColor(0xFF34D399);
            p.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x3334D399);
        }

        public void setData(List<Float> data) {
            values = new float[data.size()];
            for (int i = 0; i < data.size(); i++) values[i] = data.get(i);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), U.px(getContext(), 90));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (values == null || values.length < 2) return;
            float w = getWidth(), h = getHeight();
            float pad = U.px(getContext(), 4);
            float max = 1;
            for (float v : values) if (v > max) max = v;

            Path line = new Path();
            Path area = new Path();
            for (int i = 0; i < values.length; i++) {
                float x = pad + (w - pad * 2) * i / (values.length - 1);
                float y = h - pad - (h - pad * 2) * (values[i] / max);
                if (i == 0) {
                    line.moveTo(x, y);
                    area.moveTo(x, h - pad);
                    area.lineTo(x, y);
                } else {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
            }
            area.lineTo(w - pad, h - pad);
            area.close();

            canvas.drawPath(area, fill);
            canvas.drawPath(line, p);
        }
    }
}
