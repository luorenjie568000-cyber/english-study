package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Readings;
import com.englishstudy.app.data.Vocab;

import java.util.List;

/** 阅读详情：句子可点朗读，生词高亮可点查词。 */
public class ReaderActivity extends Activity {

    private Readings.Passage ps;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra("id");
        Readings.load(this);
        for (Readings.Passage p : Readings.all()) {
            if (p.id.equals(id)) {
                ps = p;
                break;
            }
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout root = U.col(this);
        root.setBackgroundColor(getColor(R.color.bg));
        int p = U.px(this, 16);
        root.setPadding(p, p, p, U.px(this, 24));

        // 顶栏
        LinearLayout top = U.row(this);
        TextView back = U.tv(this, "‹", 26, getColor(R.color.text_dim));
        back.setPadding(U.px(this, 4), 0, U.px(this, 12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.tv(this, ps == null ? "阅读" : ps.title, 15,
                getColor(R.color.text_dim)), U.weight(1));
        root.addView(top);
        root.addView(U.spacer(this, 12));

        if (ps == null) {
            root.addView(U.tv(this, "文章不存在", 14, getColor(R.color.text_dim)));
        } else {
            root.addView(U.bold(this, ps.title, 22, getColor(R.color.text)));
            if (!ps.titleZh.isEmpty()) {
                root.addView(U.spacer(this, 4));
                root.addView(U.tv(this, ps.titleZh + " · " + ps.words() + " 词", 12,
                        getColor(R.color.text_dim2)));
            }
            root.addView(U.spacer(this, 14));

            LinearLayout tools = U.row(this);
            TextView all = U.ghostButton(this, "🔊 通读一遍", getColor(R.color.primary));
            all.setOnClickListener(v -> Tts.say(this, ps.text));
            tools.addView(all, U.weight(1));
            root.addView(tools);
            root.addView(U.spacer(this, 12));

            LinearLayout card = U.card(this);
            for (String s : ps.sentences()) {
                String t = s.trim();
                if (t.isEmpty()) continue;
                final String sentence = t;

                LinearLayout line = U.row(this);
                line.setGravity(android.view.Gravity.TOP);
                line.setPadding(0, U.px(this, 8), 0, U.px(this, 8));

                TextView spk = U.tv(this, "🔊", 14, getColor(R.color.text_dim2));
                spk.setPadding(0, U.px(this, 3), U.px(this, 8), 0);
                spk.setOnClickListener(v -> Tts.say(this, sentence));
                line.addView(spk);

                TextView tv = new TextView(this);
                tv.setTextSize(16);
                tv.setLineSpacing(U.dp(this, 7), 1f);
                tv.setTextColor(getColor(R.color.text));
                tv.setText(buildSpans(t));
                tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                line.addView(tv, U.weight(1));

                card.addView(line);
            }
            root.addView(card);
        }

        sv.addView(root);
        setContentView(sv);
    }

    private Spannable buildSpans(String text) {
        SpannableString sp = new SpannableString(text);
        int i = 0, n = text.length();
        while (i < n) {
            if (!Character.isLetter(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '\'')) j++;
            final String w = text.substring(i, j).toLowerCase();

            Dict.Entry e = Dict.lookup(w);
            if (e != null) {
                boolean known = Vocab.get(w) != null;
                int color = known ? R.color.accent : R.color.primary;
                sp.setSpan(new ForegroundColorSpan(getColor(color)), i, j,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                final String word = e.word;
                sp.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View v) {
                        Intent it = new Intent(ReaderActivity.this, WordActivity.class);
                        it.putExtra("word", word);
                        startActivity(it);
                    }
                }, i, j, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return sp;
    }
}
