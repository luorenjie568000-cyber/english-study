package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Agent;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.voice.LocalLlm;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 本地模型管理：列表 / 下载 / 启用 / 删除 / 自检。
 *
 * v6.0 重做要点：
 *   - 换成一列可选模型（Qwen3-4B 推荐 / 1.7B / 0.6B），不再只认死一个
 *   - 每个模型单独显示下载进度、状态、能不能在这台机器上跑
 *   - 加「自检」按钮：跑闲聊 / 翻译 / 批改三个任务，把结果写在界面上
 *     这样「本地到底行不行」一眼能看出来，不用去翻日志
 */
final class LocalModelPanel {

    private LocalModelPanel() {
    }

    static void show(Activity a) {
        ScrollView sv = new ScrollView(a);
        LinearLayout box = U.col(a);
        int pad = U.px(a, 18);
        box.setPadding(pad, pad, pad, pad);

        box.addView(U.bold(a, "本地模型", 18, Theme.c(a, R.color.text)));
        box.addView(U.spacer(a, 4));
        box.addView(U.tv(a, "全部装在本机，不联网、不花钱。推荐 Qwen3 4B："
                + "4B 档里最聪明的一版，手机 8 线程约 8-12 tok/s。", 12,
                Theme.c(a, R.color.text_dim2)));
        box.addView(U.spacer(a, 12));

        // 当前状态
        LocalLlm.Pack act = LocalLlm.active(a);
        LinearLayout statCard = U.card(a);
        if (act == null) {
            statCard.addView(U.tv(a, "当前没有可用模型", 13, Theme.c(a, R.color.warn)));
        } else {
            statCard.addView(U.bold(a, act.title.split("（")[0], 14,
                    Theme.c(a, R.color.text)));
            String line = LocalLlm.ready(a, act) ? "已下载完整" : "文件不完整";
            if (LocalLlm.loaded()) line += " · 已加载到内存";
            statCard.addView(U.tv(a, line, 11, Theme.c(a, R.color.text_dim)));
            if (LocalLlm.loaded() && !LocalLlm.lastStats().isEmpty()) {
                statCard.addView(U.tv(a, LocalLlm.lastStats(), 11,
                        Theme.c(a, R.color.accent)));
            }
            statCard.addView(U.tv(a, "对话模式：" + (Agent.localMode()
                    ? "本地优先" : "云端"), 11, Theme.c(a, R.color.text_dim2)));
        }
        box.addView(statCard);

        box.addView(U.spacer(a, 14));
        box.addView(U.bold(a, "可选模型", 13, Theme.c(a, R.color.text_dim)));

        for (LocalLlm.Pack p : LocalLlm.packs()) {
            box.addView(packRow(a, p));
            box.addView(U.spacer(a, 10));
        }

        box.addView(U.spacer(a, 6));
        LinearLayout acts = U.row(a);
        TextView mode = U.primaryButton(a, Agent.localMode() ? "切到云端" : "切到本地");
        mode.setOnClickListener(v -> {
            boolean on = !Agent.localMode();
            Store.putBool("ai.local", on);
            if (!on) LocalLlm.release();
            Agent.reset();
            ((com.englishstudy.app.MainActivity) a).refresh();
            a.finish();
        });
        acts.addView(mode, U.weight(1));

        if (act != null) {
            acts.addView(U.hspacer(a, 8));
            TextView test = U.ghostButton(a, "自检", Theme.c(a, R.color.accent));
            test.setOnClickListener(v -> selfTest(a));
            acts.addView(test, U.weight(1));
        }
        box.addView(acts);

        box.addView(U.spacer(a, 10));
        box.addView(U.tv(a, "自检会依次跑：闲聊 / 翻译一句 / 批改一句，"
                + "结果直接显示在这里。首次加载 4B 模型约 2-4 秒。", 10,
                Theme.c(a, R.color.text_dim2)));

        sv.addView(box);
        new AlertDialog.Builder(a)
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 一个模型一行：标题 / 大小 / 状态 / 操作。 */
    private static View packRow(Activity a, LocalLlm.Pack p) {
        LinearLayout card = U.card(a);
        boolean ready = LocalLlm.ready(a, p);
        long done = LocalLlm.doneBytes(a, p);
        boolean isActive = p.id.equals(
                com.englishstudy.app.data.Store.get("ai.llm", LocalLlm.defaultId()));

        LinearLayout head = U.row(a);
        head.addView(U.bold(a, p.title, 14, Theme.c(a, R.color.text)), U.weight(1));
        if (isActive) {
            head.addView(U.pill(a, "当前", Theme.c(a, R.color.primary)));
        }
        card.addView(head);

        String st;
        if (ready) {
            st = "已下载 · " + (p.size / 1048576) + "MB";
        } else if (done > 0) {
            st = "已下 " + (done / 1048576) + "MB / " + (p.size / 1048576) + "MB";
        } else {
            st = "未下载 · " + (p.size / 1048576) + "MB";
        }
        card.addView(U.tv(a, st, 11, Theme.c(a, R.color.text_dim2)));

        LinearLayout acts = U.row(a);
        acts.setPadding(0, U.px(a, 8), 0, 0);

        if (!ready) {
            TextView dl = U.ghostButton(a, done > 0 ? "继续下载" : "下载",
                    Theme.c(a, R.color.primary));
            dl.setOnClickListener(v -> download(a, p));
            acts.addView(dl, U.weight(1));
        } else {
            TextView use = U.ghostButton(a, isActive ? "使用中" : "用这个",
                    Theme.c(a, isActive ? R.color.text_dim2 : R.color.accent));
            use.setOnClickListener(v -> {
                if (isActive) return;
                Store.put("ai.llm", p.id);
                LocalLlm.release();
                Store.putBool("ai.local", true);
                Agent.reset();
                android.widget.Toast.makeText(a, "已切到 " + p.title.split("（")[0]
                                + "，下次提问会加载它",
                        android.widget.Toast.LENGTH_SHORT).show();
                a.finish();
                a.startActivity(a.getIntent());
            });
            acts.addView(use, U.weight(1));
        }

        if (done > 0) {
            acts.addView(U.hspacer(a, 8));
            TextView del = U.ghostButton(a, "删除", Theme.c(a, R.color.danger));
            del.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle("删除模型？")
                    .setMessage(p.title)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> {
                        LocalLlm.delete(a, p);
                        a.finish();
                        a.startActivity(a.getIntent());
                    }).show());
            acts.addView(del, U.weight(1));
        }
        card.addView(acts);
        return card;
    }

    // ---------------------------------------------------------------- 自检

    private static void selfTest(Activity a) {
        LocalLlm.Pack p = LocalLlm.active(a);
        if (p == null) {
            android.widget.Toast.makeText(a, "先下载一个模型",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("加载模型并跑三项测试…（首次约 10-30 秒）");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            long t0 = System.currentTimeMillis();
            boolean ok = LocalLlm.load(a, p);
            sb.append("加载：").append(ok ? "成功 " : "失败 ")
                    .append(System.currentTimeMillis() - t0).append("ms\n\n");
            if (ok) {
                t0 = System.currentTimeMillis();
                String chat = LocalLlm.ask("你是英语学习助手，用中文简短回答。",
                        "你好，介绍一下你自己", 80);
                sb.append("① 闲聊（").append(System.currentTimeMillis() - t0)
                        .append("ms）\n").append(nz(chat)).append("\n\n");

                t0 = System.currentTimeMillis();
                String tr = LocalLlm.askJson("你是翻译引擎，只输出 JSON。",
                        "把下面这句译成中文，返回 {\"zh\":\"译文\"}：\n"
                                + "The weather is really nice today.", 200);
                sb.append("② 翻译（").append(System.currentTimeMillis() - t0)
                        .append("ms）\n").append(nz(tr)).append("\n\n");

                t0 = System.currentTimeMillis();
                String fix = LocalLlm.askJson("你是英语老师，只输出 JSON。",
                        "指出语法错误，返回 {\"fixed\":\"改写后\",\"why\":\"中文说明\"}：\n"
                                + "Yesterday I go to the park and very like it.", 300);
                sb.append("③ 批改（").append(System.currentTimeMillis() - t0)
                        .append("ms）\n").append(nz(fix));
            }
            String out = sb.toString();
            a.runOnUiThread(() -> {
                pd.dismiss();
                TextView tv = U.tv(a, out, 11, Theme.c(a, R.color.text));
                tv.setTextIsSelectable(true);
                ScrollView s = new ScrollView(a);
                LinearLayout w = U.col(a);
                int q = U.px(a, 16);
                w.setPadding(q, q, q, q);
                w.addView(tv);
                s.addView(w);
                new AlertDialog.Builder(a)
                        .setTitle("本地模型自检")
                        .setView(s)
                        .setPositiveButton("好", null)
                        .show();
            });
        }, "llm-selftest").start();
    }

    private static String nz(String s) {
        return s == null || s.trim().isEmpty() ? "（空 — 模型没吐内容）" : s.trim();
    }

    // ---------------------------------------------------------------- 下载

    private static void download(Activity a, LocalLlm.Pack p) {
        final boolean[] cancel = {false};
        ProgressDialog pd = new ProgressDialog(a);
        pd.setTitle("下载 " + p.title.split("（")[0]);
        pd.setMessage("连接中…");
        pd.setCancelable(false);
        pd.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消", (d, w) -> {
            cancel[0] = true;
            d.dismiss();
        });
        pd.show();

        new Thread(() -> {
            try {
                fetch(a, p, pd, cancel);
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle("下载完成")
                            .setMessage(p.title + " 已就绪。要现在启用吗？")
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("启用", (d, w) -> {
                                Store.put("ai.llm", p.id);
                                Store.putBool("ai.local", true);
                                LocalLlm.release();
                                Agent.reset();
                                ((com.englishstudy.app.MainActivity) a).refresh();
                            })
                            .show();
                });
            } catch (Throwable e) {
                final boolean byUser = cancel[0];
                a.runOnUiThread(() -> {
                    pd.dismiss();
                    new AlertDialog.Builder(a)
                            .setTitle(byUser ? "已取消" : "下载失败")
                            .setMessage(byUser ? "可以再点下载继续，会从断点接着下。"
                                    : String.valueOf(e.getMessage()))
                            .setPositiveButton("好", null)
                            .show();
                });
            }
        }, "llm-dl").start();
    }

    private static void fetch(Activity a, LocalLlm.Pack p, ProgressDialog pd,
                              boolean[] cancel) throws Exception {
        File dst = LocalLlm.file(a, p);
        File tmp = new File(dst.getAbsolutePath() + ".part");
        if (dst.exists() && dst.length() == p.size) return;

        long have = tmp.exists() ? tmp.length() : 0;
        if (have >= p.size) {
            if (!moveTo(tmp, dst)) throw new Exception("落盘失败");
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(p.url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "EnglishStudy/1.0");
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");

        int code = conn.getResponseCode();
        if (code != 200 && code != 206) throw new Exception("HTTP " + code);
        if (code == 200 && have > 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            have = 0;
        }

        InputStream in = conn.getInputStream();
        RandomAccessFile out = new RandomAccessFile(tmp, "rw");
        out.seek(have);

        byte[] buf = new byte[262144];
        long got = have;
        long lastUi = 0;
        long t0 = System.currentTimeMillis();
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancel[0]) {
                out.close();
                in.close();
                conn.disconnect();
                throw new Exception("已取消");
            }
            out.write(buf, 0, n);
            got += n;
            long now = System.currentTimeMillis();
            if (now - lastUi > 400) {
                lastUi = now;
                final long g = got;
                final long speed = (g - have) * 1000 / Math.max(1, now - t0) / 1048576;
                a.runOnUiThread(() -> pd.setMessage(
                        (g / 1048576) + "MB / " + (p.size / 1048576) + "MB"
                                + "（" + (g * 100 / p.size) + "%）"
                                + (speed > 0 ? " · " + speed + "MB/s" : "")));
            }
        }
        out.close();
        in.close();
        conn.disconnect();

        if (tmp.length() != p.size) {
            throw new Exception("大小不符 " + tmp.length() + " / " + p.size
                    + "（可再点下载续传）");
        }
        if (!moveTo(tmp, dst)) throw new Exception("落盘失败");
    }

    /** renameTo 在部分机型会静默失败，退回流式复制。 */
    private static boolean moveTo(File src, File dst) {
        if (dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.delete();
        if (src.renameTo(dst)) return true;
        try {
            InputStream in = new java.io.FileInputStream(src);
            java.io.OutputStream out = new java.io.FileOutputStream(dst);
            byte[] b = new byte[262144];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            if (dst.length() != src.length()) return false;
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
