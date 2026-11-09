package com.englishstudy.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.engine.WriteReview;
import com.englishstudy.app.engine.WritingChecker;

import java.util.List;

/** 写作：写一段 → 本地批改 → 分数 + 问题清单 + 标出的原文。 */
public class WriteView {

    private final Activity a;
    private EditText input;
    private LinearLayout resultBox;
    private TextView count;

    public WriteView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "写作", 24, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "规则层查确定性错误，本地 4B 模型查语法与表达 —— 全离线",
                12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        LinearLayout inCard = U.card(a);
        input = new EditText(a);
        input.setHint("Write something here… 两三句也行");
        input.setHintTextColor(Theme.c(a, R.color.text_dim2));
        input.setTextColor(Theme.c(a, R.color.text));
        input.setTextSize(15);
        input.setBackground(null);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setLineSpacing(U.dp(a, 5), 1f);
        inCard.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 草稿持久化：切标签/退后台不丢（write.draft）
        String draft = com.englishstudy.app.data.Store.get("write.draft", "");
        if (!draft.isEmpty()) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                com.englishstudy.app.data.Store.put("write.draft", e.toString());
            }
        });

        LinearLayout btns = U.row(a);
        btns.setPadding(0, U.px(a, 10), 0, 0);
        TextView go = U.primaryButton(a, "批改");
        go.setOnClickListener(v -> check());
        btns.addView(go, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView sample = U.ghostButton(a, "填入范文", Theme.c(a, R.color.text_dim));
        sample.setOnClickListener(v -> input.setText(SAMPLE));
        btns.addView(sample, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView clear = U.ghostButton(a, "清空", Theme.c(a, R.color.text_dim));
        clear.setOnClickListener(v -> {
            input.setText("");
            resultBox.removeAllViews();
            count.setText("0 词");
        });
        btns.addView(clear, U.weight(1));
        inCard.addView(btns);

        // 批改强度
        LinearLayout lv = U.row(a);
        lv.setPadding(0, U.px(a, 8), 0, 0);
        String[] names = {"只改错", "标准", "全面打磨"};
        String[] vals = {"gentle", "normal", "strict"};
        final TextView[] lvBtns = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final String v = vals[i];
            TextView b = U.tv(a, names[i], 12, Theme.c(a,
                    v.equals(level) ? R.color.primary : R.color.text_dim2));
            b.setPadding(U.px(a, 10), U.px(a, 6), U.px(a, 10), U.px(a, 6));
            b.setBackground(v.equals(level) ? U.primaryBg(a) : null);
            b.setOnClickListener(x -> {
                level = v;
                for (int k = 0; k < 3; k++) {
                    boolean on = vals[k].equals(level);
                    lvBtns[k].setTextColor(Theme.c(a, on ? R.color.primary : R.color.text_dim2));
                    lvBtns[k].setBackground(on ? U.primaryBg(a) : null);
                }
            });
            lvBtns[i] = b;
            lv.addView(b);
            lv.addView(U.hspacer(a, 6));
        }
        inCard.addView(lv);

        count = U.tv(a, "0 词", 11, Theme.c(a, R.color.text_dim2));
        count.setGravity(Gravity.END);
        count.setPadding(0, U.px(a, 6), 0, 0);
        inCard.addView(count);
        root.addView(inCard);

        resultBox = U.col(a);
        resultBox.setPadding(0, U.px(a, 14), 0, 0);
        root.addView(resultBox);

        // 引擎自检入口：让用户自己验证准确率，而不是只听我说
        root.addView(U.spacer(a, U.px(a, 20)));
        LinearLayout foot = U.card(a);
        foot.addView(U.bold(a, "批改引擎自检", 12, Theme.c(a, R.color.text_dim)));
        foot.addView(U.spacer(a, U.px(a, 4)));
        foot.addView(U.tv(a, "用 " + com.englishstudy.app.engine.CheckerEval.caseCount()
                + " 条已知答案的句子测一遍，显示精确率与召回率", 11,
                Theme.c(a, R.color.text_dim2)));
        TextView run = U.ghostButton(a, "跑一次", Theme.c(a, R.color.primary));
        run.setOnClickListener(v -> showEval());
        foot.addView(run);
        root.addView(foot);

        sv.addView(root);
        return sv;
    }

    /** 跑引擎自检并弹窗显示结果。 */
    private void showEval() {
        new Thread(() -> {
            String rep = com.englishstudy.app.engine.CheckerEval.run(a, true);
            a.runOnUiThread(() -> {
                TextView tv = U.tv(a, rep, 10, Theme.c(a, R.color.text));
                tv.setTextIsSelectable(true);
                tv.setTypeface(Typeface.MONOSPACE);
                ScrollView sc = new ScrollView(a);
                LinearLayout w = U.col(a);
                int q = U.px(a, 14);
                w.setPadding(q, q, q, q);
                w.addView(tv);
                sc.addView(w);
                new android.app.AlertDialog.Builder(a)
                        .setTitle("批改引擎自检")
                        .setView(sc)
                        .setPositiveButton("好", null)
                        .show();
            });
        }, "eval").start();
    }

    private static final String SAMPLE =
            "Yesterday I go to a coffee shop near my home. I very like the quite place. "
            + "Although it was crowded, but I found a seat. The waiter was very frienly. "
            + "I order a cup of coffee and read a book. I am boring in the afternoon, "
            + "so I stayed there for two hours. i will go there again.";

    /** 批改档位：gentle 只改错 / normal 改错+润色 / strict 全面打磨。 */
    private String level = "normal";

    private void check() {
        String text = input.getText().toString();
        if (text.trim().isEmpty()) {
            android.widget.Toast.makeText(a, "先写点东西", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 第一步：规则层（毫秒级，立刻出结果，不让用户干等）
        WritingChecker.Result r = WritingChecker.check(text);
        count.setText(r.words + " 词");
        renderResult(text, r, null, true);
        dumpDiag(r);

        // 第二步：本地模型语义批改（几秒到几十秒）
        final String src = text;
        new Thread(() -> {
            WriteReview.Result wr = WriteReview.review(src, level);
            a.runOnUiThread(() -> {
                renderResult(src, r, wr, false);
                dumpReviewDiag(wr);
            });
        }, "write-review").start();
    }

    private void renderResult(String text, WritingChecker.Result r,
                              WriteReview.Result wr, boolean pending) {
        resultBox.removeAllViews();

        // 分数卡
        LinearLayout scoreCard = U.card(a);
        LinearLayout row = U.row(a);
        RingView ring = new RingView(a);
        ring.setProgress(r.score / 100f, String.valueOf(r.score), "分");
        int size = U.px(a, 84);
        row.addView(ring, new LinearLayout.LayoutParams(size, size));
        row.addView(U.hspacer(a, 16));

        LinearLayout info = U.col(a);
        info.addView(U.bold(a, r.verdict, 15, scoreColor(r.score)));
        info.addView(U.spacer(a, 6));
        info.addView(U.tv(a, r.words + " 词 · " + r.sentences + " 句 · 均 "
                + r.avgLen + " 词/句", 12, Theme.c(a, R.color.text_dim2)));
        info.addView(U.spacer(a, 4));
        info.addView(U.tv(a, r.issues.isEmpty() ? "没查出明显问题"
                : "发现 " + r.issues.size() + " 处可改进", 12, Theme.c(a, R.color.text_dim)));
        row.addView(info, U.weight(1));
        scoreCard.addView(row);
        resultBox.addView(scoreCard);

        // 语义批改（本地模型）状态
        if (pending) {
            resultBox.addView(U.spacer(a, 10));
            LinearLayout wait = U.card(a);
            wait.addView(U.tv(a, "🤖 本地模型正在做语法与表达批改…", 12,
                    Theme.c(a, R.color.text_dim)));
            wait.addView(U.tv(a, "规则层已经出结果了，模型结果稍后自动补上", 11,
                    Theme.c(a, R.color.text_dim2)));
            resultBox.addView(wait);
        }

        // 标出的原文
        if (!r.issues.isEmpty()) {
            resultBox.addView(U.spacer(a, 12));
            LinearLayout marked = U.card(a);
            marked.addView(U.bold(a, "标出的位置", 13, Theme.c(a, R.color.text_dim)));
            marked.addView(U.spacer(a, 8));
            TextView tv = new TextView(a);
            tv.setTextSize(15);
            tv.setLineSpacing(U.dp(a, 6), 1f);
            tv.setTextColor(Theme.c(a, R.color.text));
            tv.setText(markText(text, r.issues));
            marked.addView(tv);
            resultBox.addView(marked);
        }

        // 问题清单
        resultBox.addView(U.spacer(a, 12));
        LinearLayout list = U.card(a);
        if (r.issues.isEmpty()) {
            list.addView(U.tv(a, "规则层没查出问题。", 13, Theme.c(a, R.color.text_dim)));
        } else {
            int errs = r.errorCount();
            int styles = r.styleCount();
            list.addView(U.bold(a, "规则检查 · " + (errs > 0 ? errs + " 处错误" : "无错误")
                    + (styles > 0 ? " · " + styles + " 条建议" : ""), 13,
                    Theme.c(a, R.color.text_dim)));
            for (WritingChecker.Issue it : r.issues) {
                list.addView(U.spacer(a, 10));
                list.addView(issueRow(it));
            }
        }
        resultBox.addView(list);

        // 语义批改结果
        if (wr != null) renderSemantic(wr);
    }

    private CharSequence markText(String text, List<WritingChecker.Issue> issues) {
        SpannableString sp = new SpannableString(text);
        for (WritingChecker.Issue it : issues) {
            int start = Math.max(0, Math.min(it.offset, text.length()));
            int end = Math.max(start, Math.min(it.offset + it.length, text.length()));
            if (end <= start) continue;
            int color = it.style ? Theme.c(a, R.color.line)
                    : (it.type.equals("拼写") || it.type.equals("语法")
                    || it.type.equals("搭配")
                    ? Theme.c(a, R.color.danger_soft) : Theme.c(a, R.color.warn_soft));
            sp.setSpan(new BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    /** 本地模型给出的语义批改（语法/表达/逻辑）。 */
    private void renderSemantic(WriteReview.Result wr) {
        resultBox.addView(U.spacer(a, 12));
        LinearLayout card = U.card(a);

        if (!wr.ok()) {
            card.addView(U.tv(a, "模型批改没成功：" + wr.error, 12,
                    Theme.c(a, R.color.text_dim2)));
            card.addView(U.tv(a, "确认「我的 → 学伴设置 → 本地模型」已启用", 11,
                    Theme.c(a, R.color.text_dim2)));
            resultBox.addView(card);
            return;
        }

        LinearLayout head = U.row(a);
        head.addView(U.bold(a, "🤖 模型批改 · " + wr.items.size() + " 处", 13,
                Theme.c(a, R.color.text_dim)), U.weight(1));
        card.addView(head);

        if (!wr.summary.isEmpty()) {
            TextView sum = U.tv(a, wr.summary, 12, Theme.c(a, R.color.text_dim));
            sum.setLineSpacing(U.dp(a, 3), 1f);
            sum.setPadding(0, U.px(a, 6), 0, 0);
            card.addView(sum);
        }

        for (WriteReview.Item it : wr.items) {
            card.addView(U.spacer(a, 10));
            card.addView(semanticRow(it));
        }

        if (!wr.rewritten.isEmpty()) {
            card.addView(U.spacer(a, 12));
            card.addView(U.divider(a, Theme.c(a, R.color.line)));
            card.addView(U.spacer(a, 8));
            card.addView(U.bold(a, "改写版本", 12, Theme.c(a, R.color.accent)));
            TextView rw = U.tv(a, wr.rewritten, 14, Theme.c(a, R.color.text));
            rw.setLineSpacing(U.dp(a, 5), 1f);
            rw.setPadding(0, U.px(a, 6), 0, 0);
            rw.setTextIsSelectable(true);
            card.addView(rw);

            TextView copy = U.ghostButton(a, "用这个版本替换", Theme.c(a, R.color.primary));
            copy.setOnClickListener(v -> {
                input.setText(wr.rewritten);
                count.setText(wr.rewritten.split("\\s+").length + " 词");
                android.widget.Toast.makeText(a, "已替换，可以再批改一次看看",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
            card.addView(copy);
        }
        resultBox.addView(card);
    }

    private View semanticRow(WriteReview.Item it) {
        LinearLayout box = U.col(a);
        LinearLayout head = U.row(a);
        TextView badge = U.tv(a, it.type, 10, Theme.c(a, R.color.warn));
        badge.setPadding(U.px(a, 6), U.px(a, 2), U.px(a, 6), U.px(a, 2));
        badge.setBackgroundResource(R.drawable.bg_pill_soft);
        head.addView(badge);
        box.addView(head);

        if (!it.original.isEmpty()) {
            TextView orig = U.tv(a, "✗ " + it.original, 13, Theme.c(a, R.color.danger));
            orig.setTypeface(Typeface.MONOSPACE);
            orig.setPadding(0, U.px(a, 5), 0, 0);
            orig.setLineSpacing(U.dp(a, 3), 1f);
            box.addView(orig);
        }
        if (!it.better.isEmpty()) {
            TextView better = U.tv(a, "✓ " + it.better, 13, Theme.c(a, R.color.accent));
            better.setTypeface(Typeface.MONOSPACE);
            better.setPadding(0, U.px(a, 3), 0, 0);
            better.setLineSpacing(U.dp(a, 3), 1f);
            box.addView(better);
        }
        if (!it.why.isEmpty()) {
            TextView why = U.tv(a, it.why, 12, Theme.c(a, R.color.text_dim));
            why.setPadding(0, U.px(a, 4), 0, 0);
            why.setLineSpacing(U.dp(a, 3), 1f);
            box.addView(why);
        }
        return box;
    }

    private View issueRow(WritingChecker.Issue it) {
        LinearLayout box = U.col(a);

        LinearLayout head = U.row(a);
        TextView badge = U.tv(a, it.style ? "建议" : it.type, 10,
                Theme.c(a, it.style ? R.color.text_dim2
                        : (it.type.equals("拼写") || it.type.equals("语法")
                        || it.type.equals("搭配") ? R.color.danger : R.color.warn)));
        badge.setPadding(U.px(a, 6), U.px(a, 2), U.px(a, 6), U.px(a, 2));
        badge.setBackgroundResource(R.drawable.bg_pill_soft);
        head.addView(badge);

        TextView snippet = U.tv(a, it.snippet, 13, Theme.c(a, R.color.text));
        snippet.setTypeface(Typeface.MONOSPACE);
        snippet.setPadding(U.px(a, 8), 0, 0, 0);
        head.addView(snippet);
        box.addView(head);

        TextView msg = U.tv(a, it.message, 12, Theme.c(a, R.color.text_dim));
        msg.setPadding(0, U.px(a, 4), 0, 0);
        msg.setLineSpacing(U.dp(a, 3), 1f);
        box.addView(msg);

        if (!it.hint.isEmpty()) {
            TextView hint = U.tv(a, "→ " + it.hint, 12, Theme.c(a, R.color.primary));
            hint.setPadding(0, U.px(a, 3), 0, 0);
            box.addView(hint);
        }
        return box;
    }

    /** 把批改结果写到 files/write-diag.txt，便于自动化校验。 */
    private void dumpDiag(WritingChecker.Result r) {
        try {
            java.io.File f = new java.io.File(a.getFilesDir(), "write-diag.txt");
            java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
            w.println("score=" + r.score);
            w.println("verdict=" + r.verdict);
            w.println("words=" + r.words + " sentences=" + r.sentences + " avg=" + r.avgLen);
            w.println("issues=" + r.issues.size());
            for (WritingChecker.Issue it : r.issues) {
                w.println(it.type + "|" + it.snippet + "|" + it.message + "|" + it.hint);
            }
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 语义批改结果写诊断文件。 */
    private void dumpReviewDiag(WriteReview.Result wr) {
        try {
            java.io.File f = new java.io.File(a.getFilesDir(), "write-review.txt");
            java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8");
            w.println("ok=" + wr.ok() + " err=" + wr.error);
            w.println("summary=" + wr.summary);
            w.println("items=" + wr.items.size());
            for (WriteReview.Item it : wr.items) {
                w.println(it.type + "|" + it.original + "|" + it.better + "|" + it.why);
            }
            w.println("rewritten=" + wr.rewritten);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 直接返回 ARGB，调用方不要再套 getColor（会把颜色值当资源 id）。 */
    private int scoreColor(int s) {
        return a.getColor(s >= 85 ? R.color.primary : s >= 65 ? R.color.warn : R.color.danger);
    }
}
