package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
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
    private String lang = "en";
    private boolean multi;
    private LinearLayout sentenceBox;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        String articleId = getIntent().getStringExtra("articleId");
        com.englishstudy.app.data.Articles.Item art =
                articleId == null ? null : com.englishstudy.app.data.Articles.byId(articleId);

        String id = getIntent().getStringExtra("id");
        Readings.load(this);
        for (Readings.Passage p : Readings.all()) {
            if (p.id.equals(id)) {
                ps = p;
                break;
            }
        }
        // 用户导入的文章：包成 Passage，这样点词查义/点句朗读都能复用
        if (ps == null && art != null) {
            ps = new Readings.Passage();
            ps.id = art.id;
            ps.level = "";
            ps.title = art.title;
            ps.titleZh = "我的文章";
            ps.text = art.text;
        }

        // 检测语言：不是英文就加载对应多语言词典（中文/日文/韩文）
        lang = com.englishstudy.app.data.MultiDict.detect(ps == null ? "" : ps.text);
        multi = !"en".equals(lang);
        diag("open article lang=" + lang + " multi=" + multi + " len="
                + (ps == null ? 0 : ps.text.length()));
        if (multi) {
            new Thread(() -> {
                final boolean ok = com.englishstudy.app.data.MultiDict.open(this, lang);
                runOnUiThread(() -> {
                    if (!isFinishing() && ok) rerender();
                });
            }, "dict-open").start();
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout root = U.col(this);
        root.setBackgroundColor(Theme.c(this, R.color.bg));
        int p = U.px(this, 16);
        root.setPadding(p, p, p, U.px(this, 24));

        // 顶栏
        LinearLayout top = U.row(this);
        TextView back = U.tv(this, "‹", 26, Theme.c(this, R.color.text_dim));
        back.setPadding(U.px(this, 4), 0, U.px(this, 12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.tv(this, ps == null ? "阅读" : ps.title, 15,
                Theme.c(this, R.color.text_dim)), U.weight(1));
        root.addView(top);
        root.addView(U.spacer(this, 12));

        if (ps == null) {
            root.addView(U.tv(this, "文章不存在", 14, Theme.c(this, R.color.text_dim)));
        } else {
            root.addView(U.bold(this, ps.title, 22, Theme.c(this, R.color.text)));
            if (!ps.titleZh.isEmpty()) {
                root.addView(U.spacer(this, 4));
                root.addView(U.tv(this, ps.titleZh + " · " + ps.words() + " 词", 12,
                        Theme.c(this, R.color.text_dim2)));
            }
            root.addView(U.spacer(this, 14));

            LinearLayout tools = U.row(this);
            TextView all = U.ghostButton(this, "🔊 通读一遍", Theme.c(this, R.color.primary));
            all.setOnClickListener(v -> Tts.say(this, ps.text));
            tools.addView(all, U.weight(1));
            tools.addView(U.hspacer(this, 8));
            TextView tr = U.ghostButton(this, "全文翻译", Theme.c(this, R.color.accent));
            tr.setOnClickListener(v -> showFullTranslation());
            tools.addView(tr, U.weight(1));
            root.addView(tools);
            root.addView(U.spacer(this, 6));
            TextView tip = U.tv(this, "长按句子看语法成分（主谓宾 / 时态 / 语态）", 11,
                    Theme.c(this, R.color.text_dim2));
            tip.setGravity(android.view.Gravity.CENTER);
            root.addView(tip);
            root.addView(U.spacer(this, 10));

            LinearLayout card = U.card(this);
            sentenceBox = card;
            for (String s : ps.sentences()) {
                String t = s.trim();
                if (t.isEmpty()) continue;
                final String sentence = t;

                LinearLayout line = U.row(this);
                line.setGravity(android.view.Gravity.TOP);
                line.setPadding(0, U.px(this, 8), 0, U.px(this, 8));

                TextView spk = U.tv(this, "🔊", 14, Theme.c(this, R.color.text_dim2));
                spk.setPadding(0, U.px(this, 3), U.px(this, 8), 0);
                spk.setOnClickListener(v -> Tts.say(this, sentence));
                line.addView(spk);

                TextView tv = new TextView(this);
                tv.setTextSize(16);
                tv.setLineSpacing(U.dp(this, 7), 1f);
                tv.setTextColor(Theme.c(this, R.color.text));
                tv.setText(buildSpans(t));
                tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                // 长按句子 → 语法成分分析
                tv.setOnLongClickListener(v -> {
                    showSyntax(sentence);
                    return true;
                });
                line.addView(tv, U.weight(1));

                card.addView(line);
            }
            root.addView(card);
        }

        sv.addView(root);
        setContentView(Glass.wrapWithBackdrop(this, sv));
    }

    /** 弹出一句的语法成分分析。 */
    /** 中日韩统一表意文字 / 假名 / 谚文。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)  // 平假名 / 片假名
                || (c >= 0xAC00 && c <= 0xD7AF)  // 谚文
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容汉字
    }

    /** 词典就绪后重建句子文本（init 里第一次渲染时词典还没解压完）。 */
    private void rerender() {
        diag("rerender ps=" + (ps != null) + " box=" + (sentenceBox != null)
                + " multi=" + multi + " lang=" + lang
                + " ready=" + com.englishstudy.app.data.MultiDict.ready(lang));
        if (ps == null || sentenceBox == null) return;
        sentenceBox.removeAllViews();
        for (String raw : ps.sentences()) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            final String sentence = t;

            LinearLayout line = U.row(this);
            line.setGravity(android.view.Gravity.TOP);
            line.setPadding(0, U.px(this, 8), 0, U.px(this, 8));

            TextView spk = U.tv(this, "🔊", 14, Theme.c(this, R.color.text_dim));
            spk.setPadding(0, U.px(this, 3), U.px(this, 8), 0);
            spk.setOnClickListener(v -> Tts.say(this, sentence));
            line.addView(spk);

            TextView tv = new TextView(this);
            tv.setTextSize(16);
            tv.setLineSpacing(U.dp(this, 7), 1f);
            tv.setTextColor(Theme.c(this, R.color.text));
            tv.setText(buildSpans(t));
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            tv.setOnLongClickListener(v -> {
                showSyntax(sentence);
                return true;
            });
            line.addView(tv, U.weight(1));
            sentenceBox.addView(line);
        }
    }

    /** 全文翻译：先本地句库，缺的交给模型补，并做语言合理性二审。 */
    private void showFullTranslation() {
        if (ps == null) return;

        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("逐句翻译中…");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            com.englishstudy.app.engine.ArticleTool.Result r =
                    com.englishstudy.app.engine.ArticleTool.translate(ps.text);
            runOnUiThread(() -> {
                pd.dismiss();
                if (!r.ok()) {
                    toast(r.error);
                    return;
                }
                showTranslationDialog(r);
            });
        }, "translate").start();
    }

    private void showTranslationDialog(com.englishstudy.app.engine.ArticleTool.Result r) {
        LinearLayout box = U.col(this);
        int p = U.px(this, 16);
        box.setPadding(p, p, p, p);

        // 统计行
        LinearLayout stat = U.row(this);
        stat.addView(U.pill(this, "句库 " + r.libraryHits + " 句",
                Theme.c(this, R.color.accent)));
        stat.addView(U.hspacer(this, 6));
        stat.addView(U.pill(this, "共 " + r.lines.size() + " 句",
                Theme.c(this, R.color.text_dim)));
        box.addView(stat);
        box.addView(U.spacer(this, 12));

        // 逐句对照
        for (com.englishstudy.app.engine.ArticleTool.Line line : r.lines) {
            LinearLayout row = U.col(this);
            row.setPadding(0, U.px(this, 8), 0, U.px(this, 8));

            TextView en = U.tv(this, line.en, 13, Theme.c(this, R.color.text_dim));
            en.setLineSpacing(U.dp(this, 3), 1f);
            row.addView(en);

            String zh = (line.zh == null || line.zh.isEmpty()) ? "（未译出）" : line.zh;
            TextView zhTv = U.tv(this, zh, 14,
                    Theme.c(this, line.zh == null || line.zh.isEmpty()
                            ? R.color.text_dim2 : R.color.text));
            zhTv.setLineSpacing(U.dp(this, 4), 1f);
            zhTv.setPadding(0, U.px(this, 4), 0, 0);
            row.addView(zhTv);

            if (line.issue != null && !line.issue.isEmpty()) {
                TextView is = U.tv(this, "⚠ " + line.issue, 12,
                        Theme.c(this, R.color.warn));
                is.setLineSpacing(U.dp(this, 3), 1f);
                is.setPadding(0, U.px(this, 4), 0, 0);
                row.addView(is);
            }
            box.addView(row);
            box.addView(U.divider(this, Theme.c(this, R.color.line)));
        }

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("全文翻译")
                .setView(new android.widget.ScrollView(this) {{
                    addView(box);
                }})
                .setNegativeButton("关闭", null)
                .setNeutralButton("模型二审", null)
                .create();

        dlg.setOnShowListener(x -> dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    dlg.dismiss();
                    runModelReview(r);
                }));
        dlg.show();
    }

    /** 调大模型：补全缺的译文 + 检查语言是否地道。 */
    private void runModelReview(com.englishstudy.app.engine.ArticleTool.Result r) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("模型审校中…（本地模型较慢）");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            com.englishstudy.app.engine.ArticleTool.refineWithModel(r, null);
            runOnUiThread(() -> {
                pd.dismiss();
                if (r.error != null && !r.error.isEmpty()) {
                    toast(r.error + "\n（可在「我的 → 学伴设置」配置云端接口）");
                    return;
                }
                showReviewDialog(r);
            });
        }, "review").start();
    }

    private void showReviewDialog(com.englishstudy.app.engine.ArticleTool.Result r) {
        LinearLayout box = U.col(this);
        int p = U.px(this, 16);
        box.setPadding(p, p, p, p);

        if (r.summary != null && !r.summary.isEmpty()) {
            LinearLayout card = U.card(this);
            card.addView(U.bold(this, "语言质量总评", 12, Theme.c(this, R.color.text_dim)));
            card.addView(U.spacer(this, 6));
            TextView s = U.tv(this, r.summary, 14, Theme.c(this, R.color.text));
            s.setLineSpacing(U.dp(this, 4), 1f);
            card.addView(s);
            box.addView(card);
            box.addView(U.spacer(this, 12));
        }

        int issues = 0;
        for (com.englishstudy.app.engine.ArticleTool.Line l : r.lines) {
            if (l.issue == null || l.issue.isEmpty()) continue;
            issues++;
            LinearLayout row = U.col(this);
            row.setPadding(0, U.px(this, 8), 0, U.px(this, 8));
            TextView en = U.tv(this, l.en, 13, Theme.c(this, R.color.text_dim));
            en.setLineSpacing(U.dp(this, 3), 1f);
            row.addView(en);
            TextView is = U.tv(this, "⚠ " + l.issue, 13, Theme.c(this, R.color.warn));
            is.setLineSpacing(U.dp(this, 4), 1f);
            is.setPadding(0, U.px(this, 4), 0, 0);
            row.addView(is);
            box.addView(row);
            box.addView(U.divider(this, Theme.c(this, R.color.line)));
        }

        if (issues == 0) {
            box.addView(U.tv(this, "没发现表达问题，这篇文章读起来是地道的。",
                    13, Theme.c(this, R.color.accent)));
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("语言审校 · " + issues + " 处问题")
                .setView(new android.widget.ScrollView(this) {{
                    addView(box);
                }})
                .setPositiveButton("好", null)
                .show();
    }

    private void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
    }

    /** 多语言词条弹窗：词头 + 读音 + 英文释义 + 存生词本。 */
    /** 诊断日志（排查多语言渲染用）。 */
    private void diag(String line) {
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(
                    new java.io.File(getFilesDir(), "reader-diag.txt"), true));
            w.println(System.currentTimeMillis() % 1000000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private void showMultiWord(String word, String reading, String trans) {
        LinearLayout box = U.col(this);
        int p = U.px(this, 18);
        box.setPadding(p, p, p, p);

        LinearLayout head = U.row(this);
        head.addView(U.bold(this, word, 24, Theme.c(this, R.color.text)));
        if (!reading.isEmpty()) {
            head.addView(U.hspacer(this, 10));
            TextView rd = U.tv(this, reading, 14, Theme.c(this, R.color.text_dim));
            rd.setPadding(0, U.px(this, 6), 0, 0);
            head.addView(rd);
        }
        box.addView(head);

        box.addView(U.spacer(this, 10));
        TextView t = U.tv(this, trans, 14, Theme.c(this, R.color.text));
        t.setLineSpacing(U.dp(this, 5), 1f);
        box.addView(t);

        box.addView(U.spacer(this, 8));
        box.addView(U.tv(this, "来源：CC-CEDICT / JMdict / kengdic（英释）", 10,
                Theme.c(this, R.color.text_dim2)));

        new android.app.AlertDialog.Builder(this)
                .setTitle("查词")
                .setView(new android.widget.ScrollView(this) {{
                    addView(box);
                }})
                .setNegativeButton("关闭", null)
                .setPositiveButton("存生词本", (d, w) -> {
                    com.englishstudy.app.data.Vocab.getOrCreate(word);
                    com.englishstudy.app.data.Vocab.flush();
                    android.widget.Toast.makeText(this, "已加入生词本",
                            android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showSyntax(String sentence) {
        com.englishstudy.app.engine.Syntax.Result r =
                com.englishstudy.app.engine.Syntax.analyze(sentence);
        if (!r.ok()) {
            android.widget.Toast.makeText(this, "这句没分析出来，可能是不完整句",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box = U.col(this);
        int p = U.px(this, 18);
        box.setPadding(p, p, p, p);

        TextView src = U.tv(this, sentence, 13, Theme.c(this, R.color.text_dim));
        src.setLineSpacing(U.dp(this, 4), 1f);
        box.addView(src);
        box.addView(U.spacer(this, 12));

        // 句型 / 时态 / 语态
        LinearLayout tags = U.row(this);
        tags.addView(U.pill(this, r.pattern, Theme.c(this, R.color.primary)));
        tags.addView(U.hspacer(this, 6));
        tags.addView(U.pill(this, r.tense, Theme.c(this, R.color.accent)));
        tags.addView(U.hspacer(this, 6));
        tags.addView(U.pill(this, r.voice, Theme.c(this, R.color.warn)));
        box.addView(tags);
        box.addView(U.spacer(this, 14));

        // 成分列表
        for (com.englishstudy.app.engine.Syntax.Part part : r.parts) {
            LinearLayout row = U.row(this);
            row.setPadding(0, U.px(this, 6), 0, U.px(this, 6));
            TextView role = U.tv(this, part.role, 11, roleColor(part.role));
            role.setPadding(U.px(this, 8), U.px(this, 3), U.px(this, 8), U.px(this, 3));
            role.setBackground(U.pillBg(this));
            row.addView(role);
            row.addView(U.hspacer(this, 10));
            TextView txt = U.tv(this, part.text, 14, Theme.c(this, R.color.text));
            row.addView(txt, U.weight(1));
            box.addView(row);
        }

        if (!r.notes.isEmpty()) {
            box.addView(U.spacer(this, 12));
            for (String n : r.notes) {
                box.addView(U.tv(this, "· " + n, 11, Theme.c(this, R.color.text_dim2)));
            }
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("句子分析")
                .setView(new android.widget.ScrollView(this) {{
                    addView(box);
                }})
                .setPositiveButton("好", null)
                .show();
    }

    private int roleColor(String role) {
        switch (role) {
            case "主语": return Theme.c(this, R.color.primary);
            case "谓语": return Theme.c(this, R.color.accent);
            case "宾语": return Theme.c(this, R.color.warn);
            case "表语": return Theme.c(this, R.color.purple);
            default: return Theme.c(this, R.color.text_dim);
        }
    }

    private Spannable buildSpans(String text) {
        SpannableString sp = new SpannableString(text);
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);

            // 中日韩文字：没有空格，按「最长匹配」从 4 字往 1 字试词典
            if (isCjk(ch)) {
                int maxLen = Math.min(4, n - i);
                int hit = 0;
                for (int len = maxLen; len >= 1; len--) {
                    String cand = text.substring(i, Math.min(n, i + len));
                    if (com.englishstudy.app.data.Dict.lookup(cand) != null) {
                        hit = len;
                        break;
                    }
                }
                // 多语言词典按最长匹配找词，命中的才可点
                int len = 0;
                com.englishstudy.app.data.MultiDict.Entry me = null;
                if (multi) {
                    int[] hl = new int[1];
                    me = com.englishstudy.app.data.MultiDict.longestAt(lang, text, i, hl);
                    len = hl[0];
                }
                if (me != null && len > 0) {
                    final String cand = me.word;
                    final String reading = me.reading;
                    final String trans = me.trans;
                    sp.setSpan(new UnderlineSpan(), i, i + len,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View v) {
                            showMultiWord(cand, reading, trans);
                        }

                        @Override
                        public void updateDrawState(android.text.TextPaint ds) {
                            // 中文没有空格，靠「淡色 + 细下划线」暗示可点；
                            // 全部关掉的话用户根本不知道哪里能点
                            ds.setColor(Theme.c(ReaderActivity.this, R.color.text));
                            ds.setUnderlineText(true);
                            ds.underlineColor = Theme.c(ReaderActivity.this, R.color.text_dim2);
                        }
                    }, i, i + len, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i += len;
                } else {
                    i++;
                }
                continue;
            }

            if (!Character.isLetter(ch)) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '\'')) j++;
            final String w = text.substring(i, j).toLowerCase();

            Dict.Entry e = Dict.lookup(w);
            if (e != null) {
                // 正文保持可读：默认不染色，只有「已在生词本」的词加淡下划线，
                // 点按仍然能查义。之前每个词都染蓝 → 整篇都是蓝的，反而看不清。
                boolean inVocab = Vocab.get(w) != null;
                if (inVocab) {
                    sp.setSpan(new UnderlineSpan(), i, j,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new ForegroundColorSpan(
                                    Theme.c(this, R.color.accent)), i, j,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                final String word = e.word;
                final boolean mark = inVocab;
                sp.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View v) {
                        Intent it = new Intent(ReaderActivity.this, WordActivity.class);
                        it.putExtra("word", word);
                        startActivity(it);
                    }

                    /** 关键：不覆写这里，所有可点词都会变成主题蓝。 */
                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(mark ? Theme.c(ReaderActivity.this, R.color.accent)
                                : Theme.c(ReaderActivity.this, R.color.text));
                        ds.setUnderlineText(false);
                        ds.setFakeBoldText(false);
                    }
                }, i, j, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return sp;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面立刻停掉朗读，否则声音会跟着退出继续播
        Tts.stop();
    }
}
