package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.voice.ModelRepo;
import com.englishstudy.app.voice.OfflineAsrEngine;
import com.englishstudy.app.voice.OfflineTtsEngine;

import java.util.concurrent.atomic.AtomicBoolean;

/** 离线语音：模型包下载 / 删除 / 状态，TTS 与 ASR 的就绪情况。 */
public class VoiceCard {

    /** 在"我的"页 root 里追加语音卡片。 */
    public static View build(Activity a) {
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, "离线语音", 15, Theme.c(a, R.color.text)));
        card.addView(U.spacer(a, 6));

        // TTS 状态行
        String ttsState;
        if (OfflineTtsEngine.ready()) {
            ttsState = "已就绪 · 本地合成";
        } else if (OfflineTtsEngine.unavailable()) {
            ttsState = "未下载 · 暂用系统发音";
        } else {
            ttsState = "加载中…";
        }
        TextView ttsTv = new TextView(a);
        card.addView(statusRow(a, "朗读 TTS", ttsState, ttsTv));

        // ASR 状态行
        String asrState;
        if (OfflineAsrEngine.ready()) {
            asrState = "已就绪 · " + OfflineAsrEngine.loadedPack();
        } else if (OfflineAsrEngine.unavailable()) {
            asrState = "未下载 · 跟读不可用";
        } else {
            asrState = "加载中…";
        }
        TextView asrTv = new TextView(a);
        card.addView(statusRow(a, "跟读 ASR", asrState, asrTv));

        // 引擎是后台异步加载的，原地刷新文本（绝不重建整页，否则滚动位置被重置）
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!card.isAttachedToWindow()) return;
            ttsTv.setText(OfflineTtsEngine.ready() ? "已就绪 · 本地合成"
                    : OfflineTtsEngine.unavailable() ? "未下载 · 暂用系统发音" : "加载中…");
            asrTv.setText(OfflineAsrEngine.ready() ? "已就绪 · " + OfflineAsrEngine.loadedPack()
                    : OfflineAsrEngine.unavailable() ? "未下载 · 跟读不可用" : "加载中…");
            h.postDelayed(tick[0], 900);
        };
        h.postDelayed(tick[0], 900);

        card.addView(divider(a));

        // 四个模型包，各一行
        for (ModelRepo.Pack p : ModelRepo.all()) {
            card.addView(packRow(a, p));
            card.addView(divider(a));
        }
        return card;
    }

    // ------------------------------------------------------------ 行

    private static View statusRow(Activity a, String label, String state, TextView out) {
        LinearLayout row = U.row(a);
        row.setPadding(0, U.px(a, 10), 0, U.px(a, 10));
        row.addView(U.tv(a, label, 14, Theme.c(a, R.color.text)), U.weight(1));
        out.setText(state);
        out.setTextSize(12);
        out.setTextColor(Theme.c(a, R.color.text_dim2));
        row.addView(out);
        return row;
    }

    private static View packRow(Activity a, ModelRepo.Pack p) {
        final boolean ready = ModelRepo.ready(a, p);
        LinearLayout row = U.row(a);
        row.setPadding(0, U.px(a, 13), 0, U.px(a, 13));

        LinearLayout col = U.col(a);
        TextView title = U.tv(a, p.title, 14, Theme.c(a, R.color.text));
        col.addView(title);
        String sub;
        if (ready) {
            sub = "已下载 · 点按可删除";
        } else {
            long done = ModelRepo.doneBytes(a, p);
            long total = p.totalBytes();
            sub = done > 0
                    ? "已下 " + mb(done) + " / " + mb(total) + " · 点按继续"
                    : "点按下载 · " + mb(total);
        }
        TextView subTv = U.tv(a, sub, 12, Theme.c(a, R.color.text_dim2));
        subTv.setPadding(0, U.px(a, 2), 0, 0);
        col.addView(subTv);
        row.addView(col, U.weight(1));

        TextView action = U.tv(a, ready ? "删除" : "下载", 13,
                a.getColor(ready ? R.color.danger : R.color.primary));
        row.addView(action);

        row.setOnClickListener(v -> {
            if (ModelRepo.ready(a, p)) {
                confirmDelete(a, p);
            } else {
                startDownload(a, p, row, title, subTv, action);
            }
        });
        return row;
    }

    // ------------------------------------------------------------ 下载

    private static void startDownload(Activity a, ModelRepo.Pack p,
                                      LinearLayout row, TextView title,
                                      TextView sub, TextView action) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final boolean[] cancel = new boolean[1];

        title.setText(p.title + " · 下载中");
        action.setText("取消");
        row.setOnClickListener(v -> {
            cancelled.set(true);
            cancel[0] = true;
        });

        new Thread(() -> {
            try {
                ModelRepo.download(a, p, (done, total) -> {
                    a.runOnUiThread(() -> {
                        if (cancelled.get()) return;
                        sub.setText("已下 " + mb(done) + " / " + mb(total)
                                + "（" + (total > 0 ? done * 100 / total : 0) + "%）");
                    });
                }, cancel);
                a.runOnUiThread(() -> {
                    if (p.kind.equals(ModelRepo.KIND_TTS)) {
                        // 下载完直接热加载 TTS
                        OfflineTtsEngine.shutdown();
                        OfflineTtsEngine.init(a);
                    } else {
                        OfflineAsrEngine.release();
                        OfflineAsrEngine.init(a);
                    }
                    new AlertDialog.Builder(a)
                            .setTitle("下载完成")
                            .setMessage(p.title + " 已就绪，语音功能已切换到本地模型。")
                            .setPositiveButton("好", (d, w) -> ((MainActivity) a).refresh())
                            .show();
                });
            } catch (Exception e) {
                boolean byUser = cancelled.get();
                a.runOnUiThread(() -> {
                    title.setText(p.title);
                    sub.setText(byUser ? "已取消 · 可断点续传"
                            : "失败：" + e.getMessage() + " · 点按重试");
                    action.setText("下载");
                    // 恢复默认点击行为
                    row.setOnClickListener(v -> {
                        if (ModelRepo.ready(a, p)) {
                            confirmDelete(a, p);
                        } else {
                            startDownload(a, p, row, title, sub, action);
                        }
                    });
                    if (!byUser) {
                        new AlertDialog.Builder(a)
                                .setTitle("下载失败")
                                .setMessage(String.valueOf(e.getMessage()))
                                .setPositiveButton("好", null).show();
                    }
                });
            }
        }, "dl-" + p.id).start();
    }

    private static void confirmDelete(Activity a, ModelRepo.Pack p) {
        new AlertDialog.Builder(a)
                .setTitle("删除模型包？")
                .setMessage(p.title + " 将被删除（约 " + mb(p.totalBytes())
                        + "），下次点按重新下载。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    ModelRepo.delete(a, p);
                    if (p.kind.equals(ModelRepo.KIND_TTS)) {
                        OfflineTtsEngine.shutdown();
                        OfflineTtsEngine.init(a);
                    } else {
                        OfflineAsrEngine.release();
                        OfflineAsrEngine.init(a);
                    }
                    ((MainActivity) a).refresh();
                }).show();
    }

    private static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(Theme.c(a, R.color.line));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, U.px(a, 1)));
        return v;
    }

    private static String mb(long n) {
        return (n / 1024 / 1024) + "MB";
    }
}
