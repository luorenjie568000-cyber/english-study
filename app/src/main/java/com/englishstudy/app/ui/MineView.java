package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.History;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.data.Translate;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.engine.Card;

import java.util.Calendar;

/** 我的：统计 + 设置 + 数据管理。 */
public class MineView {

    private final Activity a;

    public MineView(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "我的", 24, a.getColor(R.color.text)));
        root.addView(U.spacer(a, 16));

        root.addView(statsCard());
        root.addView(U.spacer(a, 14));
        root.addView(VoiceCard.build(a));
        root.addView(U.spacer(a, 14));
        root.addView(settingsCard());
        root.addView(U.spacer(a, 14));
        root.addView(dataCard());
        root.addView(U.spacer(a, 20));

        TextView ver = U.tv(a, "English Study · 原生版 v4.2\n"
                + "完全离线 · 无网络请求 · 数据全部保存在本机", 11, a.getColor(R.color.text_dim2));
        ver.setGravity(Gravity.CENTER);
        ver.setLineSpacing(U.dp(a, 4), 1f);
        root.addView(ver);

        sv.addView(root);
        return sv;
    }

    private View statsCard() {
        LinearLayout card = U.card(a);
        long now = System.currentTimeMillis();
        long dayStart = dayStart();

        LinearLayout top = U.row(a);
        top.addView(U.bold(a, "学习统计", 15, a.getColor(R.color.text)), U.weight(1));
        top.addView(U.pill(a, "共 " + Vocab.size() + " 词", a.getColor(R.color.primary)));
        card.addView(top);
        card.addView(U.spacer(a, 14));

        LinearLayout grid = U.row(a);
        grid.addView(stat("今日复习", String.valueOf(Vocab.reviewedToday(dayStart)), R.color.primary),
                U.weight(1));
        grid.addView(stat("待复习", String.valueOf(Vocab.due(now, 99999).size()), R.color.warn),
                U.weight(1));
        grid.addView(stat("已掌握", String.valueOf(Vocab.countByStatus(Card.MASTERED)), R.color.accent),
                U.weight(1));
        grid.addView(stat("顽固词", String.valueOf(Vocab.countByStatus(Card.LEECH)), R.color.danger),
                U.weight(1));
        card.addView(grid);
        return card;
    }

    private View stat(String label, String value, int colorRes) {
        LinearLayout col = U.col(a);
        col.setGravity(Gravity.CENTER);
        TextView v = U.bold(a, value, 20, a.getColor(colorRes));
        v.setGravity(Gravity.CENTER);
        col.addView(v);
        TextView l = U.tv(a, label, 11, a.getColor(R.color.text_dim2));
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, U.px(a, 3), 0, 0);
        col.addView(l);
        return col;
    }

    private View settingsCard() {
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, "设置", 15, a.getColor(R.color.text)));
        card.addView(U.spacer(a, 6));
        card.addView(switchRow("每日目标", Store.getInt("goal", 30) + " 词", v -> pickGoal()));
        card.addView(divider());
        card.addView(switchRow("朗读语速", String.format("%.2fx", Store.getInt("rate", 92) / 100f),
                v -> pickRate()));
        card.addView(divider());
        card.addView(switchRow("朗读音色", voiceLabel(), v -> pickVoice()));
        card.addView(divider());
        card.addView(switchRow("例句中文", Store.getInt("ex.zh", 0) == 1 ? "显示" : "隐藏",
                v -> {
                    boolean on = Store.getInt("ex.zh", 0) != 1;
                    Store.putInt("ex.zh", on ? 1 : 0);
                    ((MainActivity) a).refresh();
                }));
        return card;
    }

    private View dataCard() {
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, "数据", 15, a.getColor(R.color.text)));
        card.addView(U.spacer(a, 6));

        card.addView(kvRow("内置词典", Dict.ready() ? Dict.size() + " 词" : "加载中…"));
        card.addView(divider());
        card.addView(kvRow("例句库", Sentences.ready()
                ? Sentences.wordCount() + " 词" : "加载中…"));
        card.addView(divider());
        card.addView(kvRow("翻译句库", Translate.ready()
                ? Translate.pairCount() + " 句" : "加载中…"));
        card.addView(divider());
        card.addView(kvRow("翻译历史", History.size() + " 条"));

        card.addView(divider());
        LinearLayout reset = U.row(a);
        reset.setPadding(0, U.px(a, 13), 0, U.px(a, 13));
        reset.setOnClickListener(v -> confirmReset());
        reset.addView(U.tv(a, "清空全部学习数据", 14, a.getColor(R.color.danger)), U.weight(1));
        card.addView(reset);
        return card;
    }

    private View kvRow(String label, String value) {
        LinearLayout row = U.row(a);
        row.setPadding(0, U.px(a, 12), 0, U.px(a, 12));
        row.addView(U.tv(a, label, 14, a.getColor(R.color.text)), U.weight(1));
        row.addView(U.tv(a, value, 12, a.getColor(R.color.text_dim2)));
        return row;
    }

    private View switchRow(String label, String value, View.OnClickListener l) {
        LinearLayout row = U.row(a);
        row.setPadding(0, U.px(a, 13), 0, U.px(a, 13));
        row.setOnClickListener(l);
        row.addView(U.tv(a, label, 14, a.getColor(R.color.text)), U.weight(1));
        row.addView(U.tv(a, value, 13, a.getColor(R.color.text_dim)));
        row.addView(U.tv(a, " ›", 16, a.getColor(R.color.text_dim2)));
        return row;
    }

    private View divider() {
        return U.divider(a, a.getColor(R.color.line));
    }

    private void pickGoal() {
        String[] items = {"10 词", "20 词", "30 词", "50 词", "80 词", "100 词"};
        final int[] vals = {10, 20, 30, 50, 80, 100};
        new AlertDialog.Builder(a).setTitle("每日目标")
                .setItems(items, (d, i) -> {
                    Store.putInt("goal", vals[i]);
                    ((MainActivity) a).refresh();
                }).show();
    }

    private void pickRate() {
        String[] items = {"0.70x 慢", "0.85x 稍慢", "0.92x 标准", "1.00x 原速", "1.15x 快"};
        final int[] vals = {70, 85, 92, 100, 115};
        new AlertDialog.Builder(a).setTitle("朗读语速")
                .setItems(items, (d, i) -> {
                    Store.putInt("rate", vals[i]);
                    Tts.setRate(vals[i] / 100f);
                    ((MainActivity) a).refresh();
                }).show();
    }

    private String voiceLabel() {
        String want = Store.get("voice.tts", "amy");
        String cur = com.englishstudy.app.voice.OfflineTtsEngine.loadedVoice();
        String name = "lessac".equals(want) ? "男声 Lessac" : "女声 Amy";
        if (!cur.isEmpty() && !cur.equals("vits-" + want)) {
            // 想用的那个没装，实际跑的是另一个
            return name + "（未装，暂用 " + ("lessac".equals(want) ? "Amy" : "Lessac") + "）";
        }
        return name;
    }

    /** 音色切换：选完立刻热重载，并把示例句念一遍。 */
    private void pickVoice() {
        com.englishstudy.app.voice.ModelRepo.Pack amy =
                com.englishstudy.app.voice.ModelRepo.ttsAmi();
        com.englishstudy.app.voice.ModelRepo.Pack les =
                com.englishstudy.app.voice.ModelRepo.ttsLessac();
        boolean hasAmy = com.englishstudy.app.voice.ModelRepo.ready(a, amy);
        boolean hasLes = com.englishstudy.app.voice.ModelRepo.ready(a, les);

        java.util.List<String> names = new java.util.ArrayList<>();
        final java.util.List<String> ids = new java.util.ArrayList<>();
        names.add("女声 Amy" + (hasAmy ? "" : "（未下载）"));
        ids.add("amy");
        names.add("男声 Lessac" + (hasLes ? "" : "（未下载）"));
        ids.add("lessac");

        new AlertDialog.Builder(a)
                .setTitle("朗读音色")
                .setItems(names.toArray(new String[0]), (d, i) -> {
                    String id = ids.get(i);
                    boolean have = "amy".equals(id) ? hasAmy : hasLes;
                    if (!have) {
                        new AlertDialog.Builder(a)
                                .setTitle("模型还没下载")
                                .setMessage("先去下面「离线语音」里下载这个音色，再回来切换。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    Store.put("voice.tts", id);
                    Tts.shutdown();
                    Tts.init(a);
                    ((MainActivity) a).refresh();

                    // 模型加载是异步的，等它就绪再念示例句，否则会退到系统 TTS
                    final android.os.Handler h =
                            new android.os.Handler(android.os.Looper.getMainLooper());
                    final long deadline = System.currentTimeMillis() + 15000;
                    final Runnable[] trySay = new Runnable[1];
                    trySay[0] = () -> {
                        boolean ok = com.englishstudy.app.voice.OfflineTtsEngine.ready();
                        boolean bad = com.englishstudy.app.voice.OfflineTtsEngine.unavailable();
                        if (ok || bad || System.currentTimeMillis() > deadline) {
                            Tts.say(a, "This is how I sound now.");
                        } else {
                            h.postDelayed(trySay[0], 400);
                        }
                    };
                    h.postDelayed(trySay[0], 400);
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(a)
                .setTitle("清空全部学习数据？")
                .setMessage("生词本、复习进度、翻译历史都会删除，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清空", (d, w) -> {
                    Vocab.clear();
                    History.clear();
                    ((MainActivity) a).refresh();
                }).show();
    }

    private long dayStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
