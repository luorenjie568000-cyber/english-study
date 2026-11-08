package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.view.View;
import android.widget.LinearLayout;
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
 * 本地模型管理：下载 / 启用 / 删除。
 *
 * 和语音模型一样支持断点续传（Range 请求 + .part 文件），
 * 下完校验字节数，不符就删掉重来，避免把半个模型交给 llama.cpp。
 */
final class LocalModelPanel {

    private LocalModelPanel() {
    }

    static void show(Activity a) {
        LocalLlm.Pack found = LocalLlm.active(a);
        final LocalLlm.Pack p = found != null ? found : LocalLlm.pack15();
        boolean ready = LocalLlm.ready(a, p);
        long done = LocalLlm.doneBytes(a, p);

        String state = ready
                ? "已下载（" + (p.size / 1048576) + "MB）"
                : done > 0
                ? "已下 " + (done / 1048576) + "MB / " + (p.size / 1048576) + "MB"
                : "未下载 · " + (p.size / 1048576) + "MB";

        LinearLayout box = U.col(a);
        int pad = U.px(a, 18);
        box.setPadding(pad, pad, pad, pad);
        box.addView(U.tv(a, "端侧小模型，装在手机里跑，不联网、不花钱。", 12,
                Theme.c(a, R.color.text_dim2)));
        box.addView(U.spacer(a, 10));
        box.addView(U.tv(a, p.title, 14, Theme.c(a, R.color.text)));
        box.addView(U.tv(a, state, 12, Theme.c(a, R.color.text_dim)));
        if (ready && LocalLlm.loaded()) {
            box.addView(U.spacer(a, 6));
            box.addView(U.tv(a, "已加载到内存 · " + LocalLlm.lastStats(), 11,
                    Theme.c(a, R.color.accent)));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("本地模型")
                .setView(box)
                .setNegativeButton("关闭", null);

        if (ready) {
            b.setNeutralButton("删除", (d, w) -> {
                LocalLlm.delete(a, p);
                Store.putBool("ai.local", false);
                ((com.englishstudy.app.MainActivity) a).refresh();
            });
            b.setPositiveButton(Agent.localMode() ? "停用" : "启用", (d, w) -> {
                boolean on = !Agent.localMode();
                Store.putBool("ai.local", on);
                if (!on) LocalLlm.release();
                Agent.reset();
                ((com.englishstudy.app.MainActivity) a).refresh();
            });
        } else {
            b.setPositiveButton("下载", (d, w) -> download(a, p));
        }
        b.show();
    }

    // ---------------------------------------------------------------- 下载

    private static void download(Activity a, LocalLlm.Pack p) {
        final boolean[] cancel = {false};
        ProgressDialog pd = new ProgressDialog(a);
        pd.setTitle("下载本地模型");
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
                                Store.putBool("ai.local", true);
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
            // 服务器不支持续传，从头来
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
                a.runOnUiThread(() -> pd.setMessage(
                        (g / 1048576) + "MB / " + (p.size / 1048576) + "MB"
                                + "（" + (g * 100 / p.size) + "%）"));
            }
        }
        out.close();
        in.close();
        conn.disconnect();

        if (tmp.length() != p.size) {
            throw new Exception("大小不符 " + tmp.length() + " / " + p.size);
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
