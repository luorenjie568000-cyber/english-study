package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Articles;
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

        LinearLayout head = U.row(a);
        head.addView(U.bold(a, "阅读", 24, Theme.c(a, R.color.text)), U.weight(1));
        TextView add = U.tv(a, "＋ 导入文章", 12, Theme.c(a, R.color.primary));
        add.setPadding(U.px(a, 12), U.px(a, 6), U.px(a, 12), U.px(a, 6));
        add.setBackground(U.pillBg(a));
        add.setOnClickListener(v -> showImportDialog());
        head.addView(add);
        root.addView(head);
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "分级短文 / 自己的文章 · 点词查义 · 点句朗读", 12,
                Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        // 用户导入的文章排最前面
        List<Articles.Item> mine = Articles.all();
        if (!mine.isEmpty()) {
            root.addView(U.bold(a, "我的文章 · " + mine.size(), 13,
                    Theme.c(a, R.color.primary)));
            root.addView(U.spacer(a, 8));
            for (Articles.Item it : mine) {
                root.addView(articleCard(it));
                root.addView(U.spacer(a, 10));
            }
            root.addView(U.spacer(a, 6));
        }

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

    /** 自己导入的文章卡片：长按删除。 */
    private View articleCard(Articles.Item it) {
        LinearLayout card = U.card(a);
        LinearLayout top = U.row(a);
        top.addView(U.bold(a, it.title, 16, Theme.c(a, R.color.text)), U.weight(1));
        top.addView(U.tv(a, it.words() + " 词", 11, Theme.c(a, R.color.text_dim2)));
        card.addView(top);

        card.addView(U.spacer(a, 6));
        String preview = it.text.replace("\n", " ");
        if (preview.length() > 70) preview = preview.substring(0, 70) + "…";
        card.addView(U.tv(a, preview, 12, Theme.c(a, R.color.text_dim)));

        card.addView(U.spacer(a, 8));
        LinearLayout acts = U.row(a);
        acts.addView(U.tv(a, "约 " + it.minutes() + " 分钟", 11,
                Theme.c(a, R.color.text_dim2)), U.weight(1));
        acts.addView(U.tv(a, "打开 ›", 12, Theme.c(a, R.color.primary)));
        card.addView(acts);

        card.setOnClickListener(v -> openArticle(it.id));
        card.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(a)
                    .setTitle("删除这篇文章？")
                    .setMessage(it.title)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        Articles.remove(it.id);
                        ((com.englishstudy.app.MainActivity) a).refresh();
                    }).show();
            return true;
        });
        return card;
    }

    private void openArticle(String id) {
        android.content.Intent i = new android.content.Intent(a,
                com.englishstudy.app.ui.ReaderActivity.class);
        i.putExtra("articleId", id);
        a.startActivity(i);
    }

    /** 粘贴 / 选文件导入文章。 */
    private void showImportDialog() {
        LinearLayout box = U.col(a);
        int p = U.px(a, 18);
        box.setPadding(p, p, p, p);

        box.addView(U.tv(a, "粘贴一段英文，或者从 txt / md 文件导入。"
                + "导入后可以点词查义、点句朗读，和内置短文一样。",
                12, Theme.c(a, R.color.text_dim2)));
        box.addView(U.spacer(a, 12));

        box.addView(U.tv(a, "标题", 12, Theme.c(a, R.color.text_dim)));
        final android.widget.EditText title = new android.widget.EditText(a);
        title.setHint("可留空");
        title.setHintTextColor(Theme.c(a, R.color.text_dim2));
        title.setTextColor(Theme.c(a, R.color.text));
        title.setTextSize(14);
        title.setBackground(U.tileBg(a));
        title.setPadding(U.px(a, 12), U.px(a, 8), U.px(a, 12), U.px(a, 8));
        title.setSingleLine(true);
        box.addView(title);
        box.addView(U.spacer(a, 12));

        box.addView(U.tv(a, "正文", 12, Theme.c(a, R.color.text_dim)));
        final android.widget.EditText body = new android.widget.EditText(a);
        body.setHint("粘贴文章…");
        body.setHintTextColor(Theme.c(a, R.color.text_dim2));
        body.setTextColor(Theme.c(a, R.color.text));
        body.setTextSize(14);
        body.setBackground(U.tileBg(a));
        body.setPadding(U.px(a, 12), U.px(a, 10), U.px(a, 12), U.px(a, 10));
        body.setMinLines(6);
        body.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        box.addView(body);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(a)
                .setTitle("导入文章")
                .setView(new android.widget.ScrollView(a) {{
                    addView(box);
                }})
                .setNeutralButton("从文件", (d, w) -> {
                    ((com.englishstudy.app.MainActivity) a).pickFile(0x91);
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String t = body.getText().toString().trim();
                    if (t.isEmpty()) {
                        android.widget.Toast.makeText(a, "正文不能为空",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Articles.add(title.getText().toString(), t);
                    dlg.dismiss();
                    ((com.englishstudy.app.MainActivity) a).refresh();
                }));
        dlg.show();
    }

    /** 从文件导入后由 MainActivity 转发。 */
    public void onArticleFile(android.net.Uri uri) {
        if (uri == null) return;
        String text = Articles.readFile(a, uri);
        if (text.isEmpty()) {
            android.widget.Toast.makeText(a, "文件是空的或读不了",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        Articles.add(name, text);
        ((com.englishstudy.app.MainActivity) a).refresh();
        android.widget.Toast.makeText(a, "已导入「" + name + "」",
                android.widget.Toast.LENGTH_SHORT).show();
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
