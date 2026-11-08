package com.englishstudy.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.engine.Card;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 单词详情：音标、释义、例句、词形变化、学习状态、跟读。 */
public class WordActivity extends Activity {

    private String word;
    private TextView star;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        word = getIntent().getStringExtra("word");
        if (word == null) {
            finish();
            return;
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
        top.addView(U.tv(this, "单词详情", 14, getColor(R.color.text_dim)), U.weight(1));
        star = U.tv(this, "☆", 22, getColor(R.color.warn));
        star.setPadding(U.px(this, 8), 0, U.px(this, 4), 0);
        star.setOnClickListener(v -> toggle());
        top.addView(star);
        root.addView(top);
        root.addView(U.spacer(this, 10));

        Dict.Entry e = Dict.lookup(word);

        // 词头
        LinearLayout head = U.card(this);
        LinearLayout wl = U.row(this);
        wl.addView(U.bold(this, e == null ? word : e.word, 30, getColor(R.color.text)),
                U.weight(1));
        TextView spk = U.tv(this, "🔊", 20, getColor(R.color.primary));
        spk.setPadding(U.px(this, 8), 0, 0, 0);
        spk.setOnClickListener(v -> Tts.say(this, e == null ? word : e.word));
        wl.addView(spk);
        head.addView(wl);

        // 音标 + 标签
        if (e != null) {
            String ipa = e.displayIpa();
            if (!ipa.isEmpty()) {
                LinearLayout ipaRow = U.row(this);
                ipaRow.addView(U.tv(this, "/" + ipa + "/", 15, getColor(R.color.text_dim)));
                if (e.frq > 0) {
                    ipaRow.addView(U.hspacer(this, 10));
                    ipaRow.addView(tag(pillLabel(e.frq), R.color.primary));
                }
                if (e.collins > 0) {
                    ipaRow.addView(U.hspacer(this, 6));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < e.collins; i++) sb.append('★');
                    ipaRow.addView(tag(sb.toString(), R.color.warn));
                }
                if (e.oxford) {
                    ipaRow.addView(U.hspacer(this, 6));
                    ipaRow.addView(tag("牛津3000", R.color.accent));
                }
                head.addView(ipaRow);
            }

            if (!e.tag.isEmpty()) {
                LinearLayout tagRow = U.row(this);
                tagRow.setPadding(0, U.px(this, 6), 0, 0);
                for (String t : e.tag.split(" ")) {
                    if (t.isEmpty()) continue;
                    if (tagRow.getChildCount() > 0) tagRow.addView(U.hspacer(this, 6));
                    tagRow.addView(tag(t.toUpperCase(Locale.US), R.color.text_dim));
                }
                head.addView(tagRow);
            }

            head.addView(U.spacer(this, 10));
            head.addView(U.tv(this, e.def.isEmpty() ? "（无释义）" : e.def, 15,
                    getColor(R.color.text)));

            if (!e.defin.isEmpty()) {
                head.addView(U.spacer(this, 8));
                TextView en = U.tv(this, e.defin, 13, getColor(R.color.text_dim));
                en.setLineSpacing(U.dp(this, 3), 1f);
                head.addView(en);
            }

            if (!e.exchange.isEmpty()) {
                String ex = exchangeText(e.exchange);
                if (!ex.isEmpty()) {
                    head.addView(U.spacer(this, 8));
                    head.addView(U.tv(this, "变化 · " + ex, 12, getColor(R.color.text_dim2)));
                }
            }
        } else {
            head.addView(U.spacer(this, 10));
            head.addView(U.tv(this, "（词典未收录）", 15, getColor(R.color.text_dim)));
        }
        root.addView(head);

        // 例句
        Sentences.load(this);
        List<Sentences.Item> ex = new ArrayList<>(Sentences.items(e == null ? word : e.word));
        if (!ex.isEmpty()) {
            root.addView(U.spacer(this, 12));
            LinearLayout exCard = U.card(this);

            LinearLayout exHead = U.row(this);
            exHead.addView(U.bold(this, "例句", 13, getColor(R.color.text_dim)), U.weight(1));
            // 中文默认隐藏，点一下全展开
            final boolean[] zhOn = {Store.getInt("ex.zh", 0) == 1};
            TextView zhBtn = U.tv(this, zhOn[0] ? "隐藏中文" : "看中文", 12,
                    getColor(R.color.primary));
            zhBtn.setPadding(U.px(this, 8), U.px(this, 2), U.px(this, 8), U.px(this, 2));
            zhBtn.setBackground(U.pillBg(this));
            exHead.addView(zhBtn);
            exCard.addView(exHead);
            exCard.addView(U.tv(this, ex.size() + " 句 · 点句子朗读", 11,
                    getColor(R.color.text_dim2)));

            LinearLayout exBody = U.col(this);
            exCard.addView(exBody);

            final int[] count = {ex.size()};
            final Runnable rebuild = () -> {
                exBody.removeAllViews();
                int idx = 0;
                for (Sentences.Item it : ex) {
                    idx++;
                    LinearLayout line = U.row(this);
                    line.setGravity(Gravity.TOP);
                    line.setPadding(0, U.px(this, 10), 0, 0);
                    TextView num = U.tv(this, String.valueOf(idx), 11, getColor(R.color.primary));
                    num.setPadding(0, U.px(this, 2), U.px(this, 8), 0);
                    line.addView(num);

                    LinearLayout col = U.col(this);
                    TextView t = U.tv(this, it.en, 14, getColor(R.color.text));
                    t.setLineSpacing(U.dp(this, 4), 1f);
                    t.setOnClickListener(v -> Tts.say(this, it.en));
                    col.addView(t);
                    if (zhOn[0]) {
                        TextView z = U.tv(this, it.hasZh() ? it.zh : "（暂无译文）", 12,
                                getColor(it.hasZh() ? R.color.text_dim : R.color.text_dim2));
                        z.setLineSpacing(U.dp(this, 3), 1f);
                        z.setPadding(0, U.px(this, 3), 0, 0);
                        col.addView(z);
                    }
                    line.addView(col, U.weight(1));
                    exBody.addView(line);
                }
            };
            rebuild.run();

            zhBtn.setOnClickListener(v -> {
                zhOn[0] = !zhOn[0];
                Store.putInt("ex.zh", zhOn[0] ? 1 : 0);
                zhBtn.setText(zhOn[0] ? "隐藏中文" : "看中文");
                rebuild.run();
            });
            root.addView(exCard);
        }

        // 学习状态
        Card c = Vocab.get(word);
        root.addView(U.spacer(this, 12));
        root.addView(statusCard(c));

        // 跟读
        root.addView(U.spacer(this, 12));
        String repeatTarget = e == null ? word : e.word;
        if (!ex.isEmpty()) repeatTarget = ex.get(0).en;
        root.addView(RepeatCard.build(this, repeatTarget));

        sv.addView(root);
        setContentView(Glass.wrapWithBackdrop(this, sv));
        refreshStar();
    }

    private View statusCard(Card c) {
        LinearLayout card = U.card(this);
        card.addView(U.bold(this, "学习状态", 13, getColor(R.color.text_dim)));
        card.addView(U.spacer(this, 10));
        if (c == null) {
            card.addView(U.tv(this, "还没开始学这个词", 13, getColor(R.color.text_dim)));
            return card;
        }
        card.addView(kv("复习次数", String.valueOf(c.reps)));
        card.addView(kv("答对 / 答错", c.right + " / " + c.wrong));
        card.addView(kv("稳定度", String.format(Locale.US, "%.1f 天", c.s)));
        card.addView(kv("难度", String.format(Locale.US, "%.1f / 10", c.d)));
        card.addView(kv("下次复习", nextText(c)));
        card.addView(kv("状态", statusText(c.status)));
        return card;
    }

    private View kv(String k, String v) {
        LinearLayout row = U.row(this);
        row.setPadding(0, U.px(this, 5), 0, U.px(this, 5));
        row.addView(U.tv(this, k, 13, getColor(R.color.text_dim2)), U.weight(1));
        row.addView(U.tv(this, v, 13, getColor(R.color.text)));
        return row;
    }

    private void toggle() {
        Card c = Vocab.get(word);
        if (c == null) {
            c = Vocab.getOrCreate(word);
            Vocab.flush();
            toast("已加入生词本");
        } else {
            Vocab.remove(word);
            Vocab.flush();
            toast("已移出生词本");
        }
        refreshStar();
    }

    private void refreshStar() {
        if (star != null) star.setText(Vocab.get(word) == null ? "☆" : "★");
    }

    private String pillLabel(int frq) {
        if (frq <= 1000) return "最高频";
        if (frq <= 3000) return "高频";
        if (frq <= 8000) return "常用";
        if (frq <= 20000) return "进阶";
        return "低频";
    }

    private TextView tag(String text, int colorRes) {
        TextView t = U.tv(this, text, 10, Theme.c(this, colorRes));
        t.setPadding(U.px(this, 7), U.px(this, 2), U.px(this, 7), U.px(this, 2));
        t.setBackground(U.pillBg(this));
        return t;
    }

    private String exchangeText(String ex) {
        java.util.Map<String, String> label = new java.util.HashMap<>();
        label.put("p", "过去式");
        label.put("d", "过去分词");
        label.put("i", "现在分词");
        label.put("3", "三单");
        label.put("s", "复数");
        label.put("r", "比较级");
        label.put("t", "最高级");
        StringBuilder sb = new StringBuilder();
        for (String seg : ex.split("/")) {
            int idx = seg.indexOf(':');
            if (idx <= 0) continue;
            String lb = label.get(seg.substring(0, idx));
            if (lb == null) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(lb).append(' ').append(seg.substring(idx + 1));
        }
        return sb.toString();
    }

    private String nextText(Card c) {
        long now = System.currentTimeMillis();
        long diff = c.next - now;
        if (diff <= 0) return "现在";
        long min = diff / 60000;
        if (min < 60) return min + " 分钟后";
        long h = min / 60;
        if (h < 24) return h + " 小时后";
        return (h / 24) + " 天后";
    }

    private String statusText(int status) {
        switch (status) {
            case Card.NEW: return "新词";
            case Card.LEARNING: return "学习中";
            case Card.MASTERED: return "已掌握";
            case Card.LEECH: return "顽固词";
            default: return "已忽略";
        }
    }

    private void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Tts.stop();
        com.englishstudy.app.voice.OfflineAsrEngine.stopListening();
    }
}
