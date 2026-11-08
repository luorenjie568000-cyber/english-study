package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Books;

import java.util.ArrayList;
import java.util.List;

/** 教材词书总览：选册 → 选单元 → 开始学。 */
public class BookView extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Books.load(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = U.col(this);
        root.setBackgroundColor(getColor(R.color.bg));
        int p = U.px(this, 16);
        root.setPadding(p, p, p, U.px(this, 24));

        LinearLayout top = U.row(this);
        TextView back = U.tv(this, "‹", 26, getColor(R.color.text_dim));
        back.setPadding(U.px(this, 4), 0, U.px(this, 12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.tv(this, "教材词书", 15, getColor(R.color.text_dim)), U.weight(1));
        top.addView(U.pill(this, Books.totalWords() + " 词", getColor(R.color.primary)));
        root.addView(top);
        root.addView(U.spacer(this, 14));

        root.addView(U.tv(this, "人教版高中英语 · 按册与单元学习", 12,
                getColor(R.color.text_dim2)));
        root.addView(U.spacer(this, 12));

        for (Books.Book bk : Books.all()) {
            root.addView(bookCard(bk));
            root.addView(U.spacer(this, 10));
        }

        sv.addView(root);
        setContentView(Glass.wrapWithBackdrop(this, sv));
    }

    private View bookCard(Books.Book bk) {
        LinearLayout card = U.card(this);
        LinearLayout top = U.row(this);
        top.addView(U.bold(this, bk.name, 16, getColor(R.color.text)), U.weight(1));
        top.addView(U.tv(this, bk.count + " 词 ›", 12, getColor(R.color.text_dim2)));
        card.addView(top);

        StringBuilder units = new StringBuilder();
        for (String u : bk.units.keySet()) {
            if (units.length() > 0) units.append(" · ");
            units.append(u.replace("Unit ", "U"));
        }
        TextView u = U.tv(this, units.toString(), 11, getColor(R.color.text_dim2));
        u.setPadding(0, U.px(this, 5), 0, 0);
        card.addView(u);

        card.setOnClickListener(v -> openUnits(bk));
        return card;
    }

    private void openUnits(Books.Book bk) {
        List<String> unitNames = new ArrayList<>(bk.units.keySet());
        String[] items = new String[unitNames.size() + 1];
        items[0] = "整册学习 · " + bk.count + " 词";
        for (int i = 0; i < unitNames.size(); i++) {
            items[i + 1] = unitNames.get(i) + " · " + bk.units.get(unitNames.get(i)).size() + " 词";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(bk.name)
                .setItems(items, (d, which) -> {
                    List<String> words;
                    String unit;
                    if (which == 0) {
                        words = Books.allWords(bk);
                        unit = "整册";
                    } else {
                        unit = unitNames.get(which - 1);
                        words = bk.units.get(unit);
                    }
                    startUnit(bk.name, unit, words);
                })
                .show();
    }

    private void startUnit(String bookName, String unitName, List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(w);
        }
        com.englishstudy.app.data.Store.put("book.words", sb.toString());
        com.englishstudy.app.data.Store.put("book.unit", bookName + " " + unitName);

        Intent i = new Intent(this, StudyActivity.class);
        i.putExtra("mode", StudyActivity.MODE_NEW);
        startActivity(i);
    }
}
