package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.data.Books;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.engine.Card;

import java.util.ArrayList;
import java.util.List;

/** 词汇：搜索 + 词书切换（含教材分册）+ 生词本分组。 */
public class VocabView {

    private final Activity a;
    private LinearLayout listBox;

    public VocabView(Activity a) {
        this.a = a;
    }

    public View build() {
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, 0);

        LinearLayout head = U.row(a);
        head.addView(U.bold(a, "词汇", 24, a.getColor(R.color.text)), U.weight(1));
        String pillName = Store.get("book.unit", "");
        if (pillName.isEmpty()) pillName = currentBookName();
        LinearLayout book = U.pill(a, pillName, a.getColor(R.color.primary));
        book.setOnClickListener(v -> pickBook());
        head.addView(book);
        root.addView(head);

        EditText search = new EditText(a);
        search.setHint("搜索单词或中文释义");
        search.setHintTextColor(a.getColor(R.color.text_dim2));
        search.setTextColor(a.getColor(R.color.text));
        search.setTextSize(14);
        search.setBackgroundResource(R.drawable.bg_tile);
        search.setPadding(U.px(a, 14), U.px(a, 10), U.px(a, 14), U.px(a, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = U.px(a, 12);
        root.addView(search, slp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int i, int i1, int i2) { }
            @Override public void onTextChanged(CharSequence s, int i, int i1, int i2) { }
            @Override public void afterTextChanged(Editable e) { renderList(e.toString().trim()); }
        });

        ScrollView sv = new ScrollView(a);
        listBox = U.col(a);
        listBox.setPadding(0, U.px(a, 12), 0, U.px(a, 24));
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        renderList("");
        return root;
    }

    private String currentBookName() {
        String id = Store.get("book", "gk");
        if ("gk".equals(id)) return "高考";
        if ("zk".equals(id)) return "中考";
        if ("cet4".equals(id)) return "四级";
        if ("cet6".equals(id)) return "六级";
        if ("toefl".equals(id)) return "托福";
        return "高考";
    }

    private void renderList(String query) {
        listBox.removeAllViews();
        if (!query.isEmpty()) {
            List<Dict.Entry> hits = Dict.search(query, 60);
            if (hits.isEmpty()) {
                listBox.addView(U.tv(a, "没有找到「" + query + "」", 13,
                        a.getColor(R.color.text_dim)));
                return;
            }
            for (Dict.Entry e : hits) listBox.addView(dictRow(e));
            return;
        }

        // 生词本分组
        int[] statuses = {Card.NEW, Card.LEARNING, Card.MASTERED, Card.LEECH};
        String[] names = {"未学", "学习中", "已掌握", "顽固词"};
        int[] colors = {R.color.text_dim, R.color.warn, R.color.accent, R.color.danger};
        for (int i = 0; i < statuses.length; i++) {
            List<Card> group = Vocab.byStatus(statuses[i], 40);
            if (group.isEmpty()) continue;
            LinearLayout section = U.row(a);
            section.setPadding(0, U.px(a, 8), 0, U.px(a, 8));
            section.addView(U.bold(a, names[i], 14, a.getColor(colors[i])), U.weight(1));
            section.addView(U.tv(a, String.valueOf(group.size()), 12,
                    a.getColor(R.color.text_dim2)));
            listBox.addView(section);
            for (Card c : group) {
                Dict.Entry e = Dict.get(c.w);
                listBox.addView(wordRow(c.w, e == null ? "" : e.def, names[i], colors[i]));
            }
        }

        if (Vocab.size() == 0) {
            listBox.addView(U.spacer(a, 30));
            LinearLayout tip = U.card(a);
            tip.setGravity(Gravity.CENTER_HORIZONTAL);
            tip.addView(U.tv(a, "还没有生词", 15, a.getColor(R.color.text_dim)));
            tip.addView(U.spacer(a, 6));
            tip.addView(U.tv(a, "去「学习」开始，或在上面搜索任意单词", 12,
                    a.getColor(R.color.text_dim2)));
            listBox.addView(tip);
        }
    }

    private View wordRow(String word, String def, String tag, int colorRes) {
        LinearLayout row = U.col(a);
        row.setBackgroundResource(R.drawable.bg_tile);
        int p = U.px(a, 14);
        row.setPadding(p, p, p, p);

        LinearLayout top = U.row(a);
        top.addView(U.bold(a, word, 16, a.getColor(R.color.text)), U.weight(1));
        top.addView(U.pill(a, tag, a.getColor(colorRes)));
        row.addView(top);

        if (def != null && !def.isEmpty()) {
            TextView d = U.tv(a, def, 12, a.getColor(R.color.text_dim2));
            d.setMaxLines(2);
            d.setPadding(0, U.px(a, 4), 0, 0);
            row.addView(d);
        }
        row.setOnClickListener(v -> ((MainActivity) a).openWord(word));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = U.px(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private View dictRow(Dict.Entry e) {
        LinearLayout row = U.col(a);
        row.setBackgroundResource(R.drawable.bg_tile);
        int p = U.px(a, 14);
        row.setPadding(p, p, p, p);

        LinearLayout top = U.row(a);
        top.addView(U.bold(a, e.word, 16, a.getColor(R.color.text)), U.weight(1));
        String ipa = e.displayIpa();
        if (!ipa.isEmpty()) {
            top.addView(U.tv(a, "/" + ipa + "/", 12, a.getColor(R.color.text_dim2)));
        }
        row.addView(top);

        if (!e.def.isEmpty()) {
            TextView d = U.tv(a, e.def, 12, a.getColor(R.color.text_dim));
            d.setMaxLines(2);
            d.setPadding(0, U.px(a, 4), 0, 0);
            row.addView(d);
        }
        row.setOnClickListener(v -> ((MainActivity) a).openWord(e.word));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = U.px(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    // ---------------------------------------------------------------- 词书

    private void pickBook() {
        List<Books.Book> gk = Books.all();
        List<String> names = new ArrayList<>();
        names.add("高考 3500");
        names.add("中考 1600");
        names.add("CET-4");
        names.add("CET-6");
        names.add("TOEFL");
        for (Books.Book b : gk) {
            names.add("📖 " + b.name + " · " + b.count + " 词");
        }
        names.add("生词本（全部）");

        new AlertDialog.Builder(a)
                .setTitle("选择词书")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) setTag("gk", "高考");
                    else if (which == 1) setTag("zk", "中考");
                    else if (which == 2) setTag("cet4", "CET-4");
                    else if (which == 3) setTag("cet6", "CET-6");
                    else if (which == 4) setTag("toefl", "TOEFL");
                    else if (which < 5 + gk.size()) {
                        openBookUnits(gk.get(which - 5));
                        return;
                    } else {
                        Store.put("book.unit", "生词本");
                        Store.put("book.words", "");
                        ((MainActivity) a).refresh();
                        return;
                    }
                    ((MainActivity) a).refresh();
                })
                .show();
    }

    private void setTag(String tag, String label) {
        Store.put("book", tag);
        Store.put("book.unit", "");
        Store.put("book.words", "");
    }

    private void openBookUnits(Books.Book b) {
        List<String> unitNames = new ArrayList<>(b.units.keySet());
        String[] items = new String[unitNames.size() + 1];
        items[0] = "整册学习 · " + b.count + " 词";
        for (int i = 0; i < unitNames.size(); i++) {
            items[i + 1] = unitNames.get(i) + " · " + b.units.get(unitNames.get(i)).size() + " 词";
        }
        new AlertDialog.Builder(a)
                .setTitle(b.name)
                .setItems(items, (d, which) -> {
                    List<String> words;
                    String unit;
                    if (which == 0) {
                        words = Books.allWords(b);
                        unit = "整册";
                    } else {
                        unit = unitNames.get(which - 1);
                        words = b.units.get(unit);
                    }
                    startUnit(b.name, unit, words);
                })
                .show();
    }

    private void startUnit(String bookName, String unitName, List<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(w);
        }
        Store.put("book.words", sb.toString());
        Store.put("book.unit", bookName + " " + unitName);
        Intent i = new Intent(a, StudyActivity.class);
        i.putExtra("mode", StudyActivity.MODE_NEW);
        a.startActivity(i);
    }
}
