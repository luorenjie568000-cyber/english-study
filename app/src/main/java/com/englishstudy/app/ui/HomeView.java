package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.engine.Card;

import java.util.Calendar;
import java.util.List;

/** 首页：今日进度、待复习、快捷入口。 */
public class HomeView {

    private final Activity a;

    public HomeView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        long now = System.currentTimeMillis();
        long dayStart = dayStart();
        int due = Vocab.due(now, 9999).size();
        int goal = Store.getInt("goal", 30);
        int done = Vocab.reviewedToday(dayStart);
        int mastered = Vocab.countByStatus(Card.MASTERED);
        int leech = Vocab.countByStatus(Card.LEECH);

        // 顶部问候
        root.addView(U.bold(a, greet(), 24, a.getColor(R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, due > 0 ? "今天有 " + due + " 个词到期，趁热打铁"
                : "今日已清空，去学点新词吧", 13, a.getColor(R.color.text_dim)));
        root.addView(U.spacer(a, 18));

        // 今日进度环
        LinearLayout ringCard = U.card(a);
        LinearLayout ringRow = U.row(a);
        RingView ring = new RingView(a);
        float ratio = goal <= 0 ? 0 : Math.min(1f, done / (float) goal);
        ring.setProgress(ratio, String.valueOf(done), "今日");
        int size = U.px(a, 92);
        ringRow.addView(ring, new LinearLayout.LayoutParams(size, size));
        ringRow.addView(U.hspacer(a, 18));

        LinearLayout stats = U.col(a);
        stats.addView(statLine("目标", goal + " 词", R.color.primary));
        stats.addView(U.spacer(a, 8));
        stats.addView(statLine("待复习", String.valueOf(due), R.color.warn));
        stats.addView(U.spacer(a, 8));
        stats.addView(statLine("已掌握", String.valueOf(mastered), R.color.accent));
        if (leech > 0) {
            stats.addView(U.spacer(a, 8));
            stats.addView(statLine("顽固词", String.valueOf(leech), R.color.danger));
        }
        ringRow.addView(stats, U.weight(1));
        ringCard.addView(ringRow);
        root.addView(ringCard);

        root.addView(U.spacer(a, 14));

        // 主操作
        LinearLayout actions = U.row(a);
        TextView review = U.primaryButton(a, "开始复习" + (due > 0 ? " · " + due : ""));
        review.setOnClickListener(v -> startStudy(StudyActivity.MODE_REVIEW));
        actions.addView(review, U.weight(1));
        actions.addView(U.hspacer(a, 10));
        TextView learn = U.ghostButton(a, "学新词", a.getColor(R.color.primary));
        learn.setOnClickListener(v -> startStudy(StudyActivity.MODE_NEW));
        actions.addView(learn, U.weight(1));
        root.addView(actions);

        root.addView(U.spacer(a, 18));

        // 数据状态
        LinearLayout info = U.card(a);
        info.addView(U.bold(a, "词库状态", 13, a.getColor(R.color.text_dim)));
        info.addView(U.spacer(a, 10));
        info.addView(kv("内置词典", Dict.ready() ? Dict.size() + " 词" : "加载中…"));
        info.addView(U.spacer(a, 6));
        info.addView(kv("例句库", Sentences.ready()
                ? Sentences.wordCount() + " 词有例句" : "加载中…"));
        info.addView(U.spacer(a, 6));
        info.addView(kv("生词本", Vocab.size() + " 词"));
        root.addView(info);

        sv.addView(root);
        return sv;
    }

    private void startStudy(int mode) {
        Intent i = new Intent(a, StudyActivity.class);
        i.putExtra("mode", mode);
        a.startActivity(i);
    }

    private View statLine(String label, String value, int colorRes) {
        LinearLayout row = U.row(a);
        row.addView(U.tv(a, label, 13, a.getColor(R.color.text_dim)), U.weight(1));
        row.addView(U.bold(a, value, 15, a.getColor(colorRes)));
        return row;
    }

    private View kv(String k, String v) {
        LinearLayout row = U.row(a);
        row.addView(U.tv(a, k, 13, a.getColor(R.color.text_dim)), U.weight(1));
        row.addView(U.tv(a, v, 13, a.getColor(R.color.text)));
        return row;
    }

    private String greet() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "夜深了";
        if (h < 11) return "早上好";
        if (h < 13) return "中午好";
        if (h < 18) return "下午好";
        return "晚上好";
    }

    private long dayStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
