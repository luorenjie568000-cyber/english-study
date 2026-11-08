package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.History;
import com.englishstudy.app.data.Translate;
import com.englishstudy.app.data.Vocab;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 翻译：整句译文 + 逐词对照 + 本地历史。 */
public class TransView {

    private final Activity a;
    private EditText input;
    private LinearLayout resultBox;
    private LinearLayout historyBox;

    public TransView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "翻译", 24, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "贴一段英文，本地整句翻译；查不到时给逐词对照",
                12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        // 输入区
        LinearLayout inCard = U.card(a);
        input = new EditText(a);
        input.setHint("粘贴或输入英文");
        input.setHintTextColor(Theme.c(a, R.color.text_dim2));
        input.setTextColor(Theme.c(a, R.color.text));
        input.setTextSize(15);
        input.setBackground(null);
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        inCard.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout btns = U.row(a);
        btns.setPadding(0, U.px(a, 10), 0, 0);
        TextView go = U.primaryButton(a, "翻译");
        go.setOnClickListener(v -> doTranslate());
        btns.addView(go, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView paste = U.ghostButton(a, "粘贴", Theme.c(a, R.color.text_dim));
        paste.setOnClickListener(v -> doPaste());
        btns.addView(paste, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView clear = U.ghostButton(a, "清空", Theme.c(a, R.color.text_dim));
        clear.setOnClickListener(v -> {
            input.setText("");
            resultBox.removeAllViews();
        });
        btns.addView(clear, U.weight(1));
        inCard.addView(btns);
        root.addView(inCard);

        // 结果区
        resultBox = U.col(a);
        resultBox.setPadding(0, U.px(a, 14), 0, 0);
        root.addView(resultBox);

        // 历史区
        root.addView(U.spacer(a, 10));
        LinearLayout hHead = U.row(a);
        hHead.addView(U.bold(a, "翻译历史", 14, Theme.c(a, R.color.text_dim)), U.weight(1));
        TextView clearH = U.tv(a, "清空", 12, Theme.c(a, R.color.danger));
        clearH.setPadding(U.px(a, 10), U.px(a, 4), 0, U.px(a, 4));
        clearH.setOnClickListener(v -> confirmClearHistory());
        hHead.addView(clearH);
        root.addView(hHead);
        root.addView(U.spacer(a, 8));

        historyBox = U.col(a);
        root.addView(historyBox);

        renderHistory();
        sv.addView(root);
        return sv;
    }

    // ---------------------------------------------------------------- 动作

    private void doPaste() {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                toast("剪贴板是空的");
                return;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence cs = clip.getItemAt(0).coerceToText(a);
            if (cs != null) input.setText(cs.toString());
        } catch (Throwable e) {
            toast("无法读取剪贴板");
        }
    }

    private void doTranslate() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            toast("先输入要翻译的英文");
            return;
        }
        if (!Translate.ready()) {
            toast("翻译库加载中，稍等几秒");
            return;
        }
        Translate.Result r = Translate.translate(text);
        renderResult(r);

        String saved = r.hasTranslation() ? r.translation : "(逐词对照)";
        History.add(text, saved);
        renderHistory();
    }

    private void renderResult(Translate.Result r) {
        resultBox.removeAllViews();

        // 整句译文
        LinearLayout card = U.card(a);
        if (r.hasTranslation()) {
            LinearLayout head = U.row(a);
            head.addView(U.bold(a, "译文", 13, Theme.c(a, R.color.text_dim)), U.weight(1));
            head.addView(U.pill(a, r.exact ? "人工译文" : "分句匹配",
                    a.getColor(r.exact ? R.color.accent : R.color.warn)));
            card.addView(head);
            card.addView(U.spacer(a, 8));

            TextView t = U.tv(a, r.translation, 17, Theme.c(a, R.color.text));
            t.setLineSpacing(U.dp(a, 6), 1f);
            card.addView(t);

            card.addView(U.spacer(a, 10));
            LinearLayout ops = U.row(a);
            TextView copy = U.tv(a, "复制", 12, Theme.c(a, R.color.primary));
            copy.setPadding(0, U.px(a, 4), U.px(a, 16), U.px(a, 4));
            copy.setOnClickListener(v -> copyText(r.translation));
            ops.addView(copy);
            TextView speak = U.tv(a, "朗读原文", 12, Theme.c(a, R.color.primary));
            speak.setOnClickListener(v -> Tts.say(a, r.source));
            ops.addView(speak);
            card.addView(ops);
        } else {
            card.addView(U.tv(a, "句库里没有这句的现成译文，下面是逐词对照",
                    12, Theme.c(a, R.color.text_dim2)));
        }
        resultBox.addView(card);

        // 逐词对照
        if (!r.words.isEmpty()) {
            resultBox.addView(U.spacer(a, 12));
            LinearLayout wc = U.card(a);
            LinearLayout wh = U.row(a);
            wh.addView(U.bold(a, "逐词对照", 13, Theme.c(a, R.color.text_dim)), U.weight(1));
            int unknown = 0;
            for (Translate.Word w : r.words) if (!w.known) unknown++;
            if (unknown > 0) wh.addView(U.pill(a, unknown + " 个生词", Theme.c(a, R.color.warn)));
            wc.addView(wh);
            wc.addView(U.spacer(a, 8));

            for (Translate.Word w : r.words) {
                LinearLayout row = U.row(a);
                row.setPadding(0, U.px(a, 6), 0, U.px(a, 6));

                TextView word = U.bold(a, w.text, 15,
                        a.getColor(w.known ? R.color.text : R.color.danger));
                row.addView(word);
                row.addView(U.hspacer(a, 10));

                if (w.known) {
                    if (!w.pos.isEmpty()) {
                        TextView pos = U.tv(a, w.pos, 11, Theme.c(a, R.color.primary));
                        row.addView(pos);
                        row.addView(U.hspacer(a, 6));
                    }
                    row.addView(U.tv(a, w.def, 13, Theme.c(a, R.color.text_dim)), U.weight(1));
                } else {
                    row.addView(U.tv(a, "词典未收录", 12, Theme.c(a, R.color.text_dim2)),
                            U.weight(1));
                    TextView add = U.tv(a, "加入生词本", 11, Theme.c(a, R.color.primary));
                    add.setPadding(U.px(a, 8), 0, 0, 0);
                    add.setOnClickListener(v -> {
                        Vocab.getOrCreate(w.text);
                        Vocab.flush();
                        toast("已加入 " + w.text);
                    });
                    row.addView(add);
                }
                wc.addView(row);
            }
            resultBox.addView(wc);
        }
    }

    // ---------------------------------------------------------------- 历史

    private void renderHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();

        List<History.Item> list = History.all();
        if (list.isEmpty()) {
            historyBox.addView(U.tv(a, "还没有翻译记录", 12, Theme.c(a, R.color.text_dim2)));
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        int shown = Math.min(list.size(), 30);
        for (int i = 0; i < shown; i++) {
            final History.Item it = list.get(i);
            LinearLayout row = U.col(a);
            row.setBackground(U.tileBg(a));
            int p = U.px(a, 12);
            row.setPadding(p, p, p, p);

            LinearLayout top = U.row(a);
            TextView src = U.tv(a, it.source, 14, Theme.c(a, R.color.text));
            src.setMaxLines(2);
            top.addView(src, U.weight(1));
            TextView time = U.tv(a, fmt.format(new Date(it.time)), 10,
                    Theme.c(a, R.color.text_dim2));
            top.addView(time);
            row.addView(top);

            if (it.target != null && !it.target.isEmpty()) {
                TextView tgt = U.tv(a, it.target, 13, Theme.c(a, R.color.text_dim));
                tgt.setMaxLines(2);
                tgt.setPadding(0, U.px(a, 4), 0, 0);
                row.addView(tgt);
            }

            row.setOnClickListener(v -> {
                input.setText(it.source);
                doTranslate();
            });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(a)
                        .setTitle("删除这条记录？")
                        .setMessage(it.source)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> {
                            History.remove(it.source);
                            renderHistory();
                        })
                        .show();
                return true;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = U.px(a, 8);
            historyBox.addView(row, lp);
        }

        if (list.size() > shown) {
            TextView more = U.tv(a, "还有 " + (list.size() - shown) + " 条更早的记录", 11,
                    Theme.c(a, R.color.text_dim2));
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, U.px(a, 6), 0, 0);
            historyBox.addView(more);
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(a)
                .setTitle("清空翻译历史？")
                .setMessage("只删本地记录，不影响词库")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    History.clear();
                    renderHistory();
                })
                .show();
    }

    private void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("translation", s));
                toast("已复制");
            }
        } catch (Throwable ignored) {
        }
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show();
    }
}
