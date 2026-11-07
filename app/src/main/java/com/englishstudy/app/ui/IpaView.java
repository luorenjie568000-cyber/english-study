package com.englishstudy.app.ui;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Ipa;

import java.util.List;

/** 音标：44 个音素，可点朗读。 */
public class IpaView {

    private final Activity a;

    public IpaView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "音标", 24, a.getColor(R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "44 个国际音标 · 点按听发音", 12, a.getColor(R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        Ipa.load(a);

        root.addView(section("元音", Ipa.vowels()));
        root.addView(U.spacer(a, 14));
        root.addView(section("辅音", Ipa.consonants()));

        sv.addView(root);
        return sv;
    }

    private View section(String title, List<Ipa.Phone> phones) {
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, title + " · " + phones.size(), 14, a.getColor(R.color.text_dim)));
        card.addView(U.spacer(a, 10));

        LinearLayout grid = U.col(a);
        LinearLayout row = null;
        for (int i = 0; i < phones.size(); i++) {
            if (i % 4 == 0) {
                row = U.row(a);
                grid.addView(row);
            }
            row.addView(phoneCell(phones.get(i)), U.weight(1));
        }
        card.addView(grid);
        return card;
    }

    private View phoneCell(Ipa.Phone ph) {
        LinearLayout box = U.col(a);
        box.setGravity(android.view.Gravity.CENTER);
        int p = U.px(a, 8);
        box.setPadding(p, p, p, p);

        TextView sym = U.bold(a, ph.sym, 22, a.getColor(R.color.primary));
        sym.setGravity(android.view.Gravity.CENTER);
        box.addView(sym);

        TextView ex = U.tv(a, ph.example, 10, a.getColor(R.color.text_dim2));
        ex.setGravity(android.view.Gravity.CENTER);
        ex.setMaxLines(1);
        box.addView(ex);

        box.setOnClickListener(v -> Tts.say(a, ph.example.isEmpty() ? ph.sym : ph.example));
        return box;
    }
}
