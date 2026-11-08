package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Readings;

import java.util.List;

/** 阅读：分级短文列表。 */
public class ReadView {

    private final Activity a;

    public ReadView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "阅读", 24, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "分级短文 · 点词查义 · 点句朗读", 12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        Readings.load(a);
        List<Readings.Passage> list = Readings.all();
        if (list.isEmpty()) {
            root.addView(U.tv(a, "还没有文章", 13, Theme.c(a, R.color.text_dim)));
        } else {
            String lastLevel = "";
            for (Readings.Passage ps : list) {
                if (!ps.level.equals(lastLevel)) {
                    lastLevel = ps.level;
                    root.addView(U.spacer(a, 6));
                    root.addView(U.bold(a, ps.level, 13, Theme.c(a, R.color.primary)));
                    root.addView(U.spacer(a, 8));
                }
                root.addView(card(ps));
                root.addView(U.spacer(a, 10));
            }
        }

        sv.addView(root);
        return sv;
    }

    private View card(Readings.Passage ps) {
        LinearLayout card = U.card(a);
        LinearLayout top = U.row(a);
        top.addView(U.bold(a, ps.title, 16, Theme.c(a, R.color.text)), U.weight(1));
        top.addView(U.pill(a, ps.words() + " 词", Theme.c(a, R.color.text_dim2)));
        card.addView(top);

        if (!ps.titleZh.isEmpty()) {
            card.addView(U.tv(a, ps.titleZh, 12, Theme.c(a, R.color.text_dim)));
        }
        if (!ps.blurb.isEmpty()) {
            TextView b = U.tv(a, ps.blurb, 12, Theme.c(a, R.color.text_dim2));
            b.setMaxLines(2);
            b.setPadding(0, U.px(a, 4), 0, 0);
            card.addView(b);
        }
        card.setOnClickListener(v -> {
            Intent i = new Intent(a, ReaderActivity.class);
            i.putExtra("id", ps.id);
            a.startActivity(i);
        });
        return card;
    }
}
