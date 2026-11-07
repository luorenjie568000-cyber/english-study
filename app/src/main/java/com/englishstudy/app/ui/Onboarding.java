package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Books;
import com.englishstudy.app.data.Store;

/**
 * 首次启动引导。
 *
 * 顺序照 Duolingo：先定每日目标 → 立刻开始第一组 → 最后才提权限。
 * 离线 App 没有账号，所以不涉及登录，只要把「今天做什么」讲清楚。
 */
public final class Onboarding {

    private Onboarding() {
    }

    public static boolean needed() {
        return !Store.getBool("onboarded", false);
    }

    public static void show(Activity a, Runnable onDone) {
        // 第一步：每日目标
        String[] labels = {"10 词 · 每天 2 分钟", "20 词 · 每天 5 分钟",
                "30 词 · 每天 8 分钟", "50 词 · 每天 15 分钟"};
        final int[] values = {10, 20, 30, 50};

        LinearLayout box = U.col(a);
        int p = U.px(a, 20);
        box.setPadding(p, p, p, p);
        box.addView(U.bold(a, "每天学多少词？", 17, a.getColor(R.color.text)));
        box.addView(U.spacer(a, 6));
        box.addView(U.tv(a, "选一个能坚持的量。之后在「我的」里随时能改。",
                12, a.getColor(R.color.text_dim2)));

        new AlertDialog.Builder(a)
                .setView(box)
                .setCancelable(false)
                .setItems(labels, (d, i) -> {
                    Store.putInt("goal", values[i]);
                    Store.putInt("limit.new", values[i]);
                    pickBook(a, onDone);
                })
                .show();
    }

    private static void pickBook(Activity a, Runnable onDone) {
        String[] names = new String[5];
        final String[] tags = {"gk", "zk", "cet4", "cet6", "toefl"};
        names[0] = "高考 3500";
        names[1] = "中考 1600";
        names[2] = "CET-4";
        names[3] = "CET-6";
        names[4] = "TOEFL";

        new AlertDialog.Builder(a)
                .setTitle("先背哪本词书？")
                .setCancelable(false)
                .setItems(names, (d, i) -> {
                    Store.put("book", tags[i]);
                    Store.put("book.unit", "");
                    Store.put("book.words", "");
                    finish(a, onDone);
                })
                .show();
    }

    private static void finish(Activity a, Runnable onDone) {
        Store.putBool("onboarded", true);
        if (onDone != null) onDone.run();

        new AlertDialog.Builder(a)
                .setTitle("可以开始了")
                .setMessage("首页点「开始复习」就能学第一组。\n\n"
                        + "想听发音：去「我的 → 离线语音」下载一个音色（完全离线）。\n"
                        + "想跟读打分：再下载一个识别模型。\n"
                        + "想自由聊天：去「对话 → 设置」填接口，或用本地小模型。")
                .setPositiveButton("开始学", (d, w) -> {
                    Intent it = new Intent(a, StudyActivity.class);
                    it.putExtra("mode", StudyActivity.MODE_NEW);
                    a.startActivity(it);
                })
                .setNegativeButton("先看看", null)
                .show();
    }
}
