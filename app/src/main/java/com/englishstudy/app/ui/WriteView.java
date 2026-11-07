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

        root.addView(U.bold(a, "写作", 24, a.getColor(R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "写完点批改，本地检查中式表达、冠词、拼写、标点",
                12, a.getColor(R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        LinearLayout inCard = U.card(a);
        input = new EditText(a);
        input.setHint("Write something here… 两三句也行");
        input.setHintTextColor(a.getColor(R.color.text_dim2));
        input.setTextColor(a.getColor(R.color.text));
        input.setTextSize(15);
        input.setBackground(null);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setLineSpacing(U.dp(a, 5), 1f);
        inCard.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = U.row(a);
        btns.setPadding(0, U.px(a, 10), 0, 0);
        TextView go = U.primaryButton(a, "批改");
        go.setOnClickListener(v -> check());
        btns.addView(go, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView sample = U.ghostButton(a, "填入范文", a.getColor(R.color.text_dim));
        sample.setOnClickListener(v -> input.setText(SAMPLE));
        btns.addView(sample, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView clear = U.ghostButton(a, "清空", a.getColor(R.color.text_dim));
        clear.setOnClickListener(v -> {
            input.setText("");
            resultBox.removeAllViews();
            count.setText("0 词");
        });
        btns.addView(clear, U.weight(1));
        inCard.addView(btns);

        count = U.tv(a, "0 词", 11, a.getColor(R.color.text_dim2));
        count.setGravity(Gravity.END);
        count.setPadding(0, U.px(a, 6), 0, 0);
        inCard.addView(count);
        root.addView(inCard);

        resultBox = U.col(a);
        resultBox.setPadding(0, U.px(a, 14), 0, 0);
        root.addView(resultBox);

        sv.addView(root);
        return sv;
    }

    private static final String SAMPLE =
            "Yesterday I go to a coffee shop near my home. I very like the quite place. "
            + "Although it was crowded, but I found a seat. The waiter was very frienly. "
            + "I order a cup of coffee and read a book. I am boring in the afternoon, "
            + "so I stayed there for two hours. i will go there again.";

    private void check() {
        String text = input.getText().toString();
        if (text.trim().isEmpty()) {
            android.widget.Toast.makeText(a, "先写点东西", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        WritingChecker.Result r = WritingChecker.check(text);
        count.setText(r.words + " 词");
        renderResult(text, r);
        dumpDiag(r);
    }

    private void renderResult(String text, WritingChecker.Result r) {
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
        info.addView(U.bold(a, r.verdict, 15, a.getColor(scoreColor(r.score))));
        info.addView(U.spacer(a, 6));
        info.addView(U.tv(a, r.words + " 词 · " + r.sentences + " 句 · 均 "
                + r.avgLen + " 词/句", 12, a.getColor(R.color.text_dim2)));
        info.addView(U.spacer(a, 4));
        info.addView(U.tv(a, r.issues.isEmpty() ? "没查出明显问题"
                : "发现 " + r.issues.size() + " 处可改进", 12, a.getColor(R.color.text_dim)));
        row.addView(info, U.weight(1));
        scoreCard.addView(row);
        resultBox.addView(scoreCard);

        // 标出的原文
        if (!r.issues.isEmpty()) {
            resultBox.addView(U.spacer(a, 12));
            LinearLayout marked = U.card(a);
            marked.addView(U.bold(a, "标出的位置", 13, a.getColor(R.color.text_dim)));
            marked.addView(U.spacer(a, 8));
            TextView tv = new TextView(a);
            tv.setTextSize(15);
            tv.setLineSpacing(U.dp(a, 6), 1f);
            tv.setTextColor(a.getColor(R.color.text));
            tv.setText(markText(text, r.issues));
            marked.addView(tv);
            resultBox.addView(marked);
        }

        // 问题清单
        resultBox.addView(U.spacer(a, 12));
        LinearLayout list = U.card(a);
        if (r.issues.isEmpty()) {
            list.addView(U.tv(a, "没查出明显问题，写得干净。", 14, a.getColor(R.color.text_dim)));
        } else {
            list.addView(U.bold(a, "问题清单（" + r.issues.size() + "）", 13,
                    a.getColor(R.color.text_dim)));
            for (WritingChecker.Issue it : r.issues) {
                list.addView(U.spacer(a, 10));
                list.addView(issueRow(it));
            }
        }
        resultBox.addView(list);
    }

    private CharSequence markText(String text, List<WritingChecker.Issue> issues) {
        SpannableString sp = new SpannableString(text);
        for (WritingChecker.Issue it : issues) {
            int start = Math.max(0, Math.min(it.offset, text.length()));
            int end = Math.max(start, Math.min(it.offset + it.length, text.length()));
            if (end <= start) continue;
            int color = it.type.equals("拼写") || it.type.equals("搭配")
                    ? a.getColor(R.color.danger_soft) : a.getColor(R.color.warn_soft);
            sp.setSpan(new BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    private View issueRow(WritingChecker.Issue it) {
        LinearLayout box = U.col(a);

        LinearLayout head = U.row(a);
        TextView badge = U.tv(a, it.type, 10,
                a.getColor(it.type.equals("拼写") || it.type.equals("搭配")
                        ? R.color.danger : R.color.warn));
        badge.setPadding(U.px(a, 6), U.px(a, 2), U.px(a, 6), U.px(a, 2));
        badge.setBackgroundResource(R.drawable.bg_pill_soft);
        head.addView(badge);

        TextView snippet = U.tv(a, it.snippet, 13, a.getColor(R.color.text));
        snippet.setTypeface(Typeface.MONOSPACE);
        snippet.setPadding(U.px(a, 8), 0, 0, 0);
        head.addView(snippet);
        box.addView(head);

        TextView msg = U.tv(a, it.message, 12, a.getColor(R.color.text_dim));
        msg.setPadding(0, U.px(a, 4), 0, 0);
        msg.setLineSpacing(U.dp(a, 3), 1f);
        box.addView(msg);

        if (!it.hint.isEmpty()) {
            TextView hint = U.tv(a, "→ " + it.hint, 12, a.getColor(R.color.primary));
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

    private int scoreColor(int s) {
        return a.getColor(s >= 85 ? R.color.primary : s >= 65 ? R.color.warn : R.color.danger);
    }
}
