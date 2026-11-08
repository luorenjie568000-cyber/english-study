package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Media;
import com.englishstudy.app.engine.AudioExtract;
import com.englishstudy.app.engine.ArticleTool;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.List;
import java.util.Locale;

/**
 * 媒体跟读：导入音视频 → 自动切句 + 识别 → 句子列表（可改 / 可播 / 可译）。
 *
 * 核心流程：
 *   1. AudioExtract 抽音轨 → 16k PCM
 *   2. VAD 按能量切句（人说话本来就有停顿）
 *   3. 逐段喂 ASR 识别出文本
 *   4. 结果存 Media（本地缓存 + 历史）
 *
 * 每句能做的事：
 *   - 点播放：跳到该片段的起始位置播（视频会同步跳）
 *   - 长按改文本：识别错了手动改
 *   - 点「译」：本地句库 → 模型补
 *   - 点「读」：用本地 TTS 朗读这句
 */
public class MediaPanel {

    private final Activity a;
    private LinearLayout root;
    private ScrollView scroll;
    private Media.Item current;

    public MediaPanel(Activity a) {
        this.a = a;
    }

    public View build() {
        scroll = new ScrollView(a);
        root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));
        render();
        scroll.addView(root);
        return scroll;
    }

    /** 选文件：主界面和独立页都能用。 */
    private void pick(int code) {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startActivityForResult(i, code);
    }

    // ---------------------------------------------------------------- 列表页

    private void render() {
        root.removeAllViews();
        if (current != null) {
            renderDetail();
            return;
        }

        root.addView(U.bold(a, "视频 / 音频跟读", 20, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "导入后自动切句 + 识别，句子可改、可播、可译",
                12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 12));

        LinearLayout addRow = U.row(a);
        TextView addV = U.primaryButton(a, "＋ 视频");
        addV.setOnClickListener(v -> pick(0xB1));
        addRow.addView(addV, U.weight(1));
        addRow.addView(U.hspacer(a, 8));
        TextView addA = U.ghostButton(a, "＋ 音频", Theme.c(a, R.color.accent));
        addA.setOnClickListener(v -> pick(0xB2));
        addRow.addView(addA, U.weight(1));
        root.addView(addRow);

        root.addView(U.spacer(a, 16));

        List<Media.Item> list = Media.all(a);
        if (list.isEmpty()) {
            LinearLayout tip = U.card(a);
            tip.addView(U.tv(a, "还没有导入过素材。", 13, Theme.c(a, R.color.text_dim)));
            tip.addView(U.spacer(a, 6));
            tip.addView(U.tv(a, "导入后会：自动切句 → 逐句识别 → 存到本地。"
                    + "下次打开直接能用，不用重新识别。", 12,
                    Theme.c(a, R.color.text_dim2)));
            root.addView(tip);
            return;
        }

        root.addView(U.bold(a, "历史记录 · " + list.size(), 13,
                Theme.c(a, R.color.text_dim)));
        root.addView(U.spacer(a, 8));

        for (Media.Item it : list) {
            LinearLayout card = U.card(a);
            LinearLayout top = U.row(a);
            top.addView(U.bold(a, it.title, 15, Theme.c(a, R.color.text)), U.weight(1));
            top.addView(U.tv(a, it.isVideo ? "视频" : "音频", 10,
                    Theme.c(a, R.color.text_dim2)));
            card.addView(top);

            card.addView(U.spacer(a, 4));
            String stat;
            if (it.state == 2) {
                stat = it.lines.size() + " 句 · " + it.wordCount() + " 词 · 已译 "
                        + it.translatedCount();
            } else if (it.state == 1) {
                stat = "识别中…";
            } else if (it.state == 3) {
                stat = "识别失败：" + it.error;
            } else {
                stat = "未识别";
            }
            card.addView(U.tv(a, stat, 11, Theme.c(a, R.color.text_dim2)));
            card.addView(U.tv(a, fmt(it.durationMs), 11, Theme.c(a, R.color.text_dim2)));

            final Media.Item item = it;
            card.setOnClickListener(v -> {
                current = item;
                render();
            });
            card.setOnLongClickListener(v -> {
                confirmDelete(item);
                return true;
            });

            root.addView(card);
            root.addView(U.spacer(a, 10));
        }
    }

    private void confirmDelete(Media.Item it) {
        new AlertDialog.Builder(a)
                .setTitle("删除这个素材？")
                .setMessage(it.title + "\n（文件与识别结果都会删除）")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    Media.remove(a, it);
                    render();
                }).show();
    }

    // ---------------------------------------------------------------- 详情页

    private void renderDetail() {
        // 顶栏
        LinearLayout top = U.row(a);
        TextView back = U.tv(a, "‹ 历史", 13, Theme.c(a, R.color.primary));
        back.setOnClickListener(v -> {
            current = null;
            render();
        });
        top.addView(back, U.weight(1));
        top.addView(U.tv(a, fmt(current.durationMs), 11, Theme.c(a, R.color.text_dim2)));
        root.addView(top);
        root.addView(U.spacer(a, 8));

        root.addView(U.bold(a, current.title, 19, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));

        if (current.state == 1) {
            root.addView(U.tv(a, "识别中…（每句约 0.3 秒）", 12,
                    Theme.c(a, R.color.warn)));
            return;
        }
        if (current.state == 3) {
            root.addView(U.tv(a, "识别失败：" + current.error, 12,
                    Theme.c(a, R.color.danger)));
            TextView retry = U.primaryButton(a, "重新识别");
            retry.setOnClickListener(v -> startRecognize(current, false));
            root.addView(retry);
            return;
        }

        if (current.lines.isEmpty()) {
            root.addView(U.tv(a, "还没有识别内容", 13, Theme.c(a, R.color.text_dim)));
            TextView go = U.primaryButton(a, "开始识别");
            go.setOnClickListener(v -> startRecognize(current, false));
            root.addView(go);
            return;
        }

        // 统计 + 操作
        LinearLayout stat = U.row(a);
        stat.addView(U.pill(a, current.lines.size() + " 句", Theme.c(a, R.color.primary)));
        stat.addView(U.hspacer(a, 6));
        stat.addView(U.pill(a, "已译 " + current.translatedCount(),
                Theme.c(a, R.color.accent)));
        root.addView(stat);
        root.addView(U.spacer(a, 10));

        LinearLayout acts = U.row(a);
        TextView tr = U.primaryButton(a, "翻译全部");
        tr.setOnClickListener(v -> translateAll());
        acts.addView(tr, U.weight(1));
        acts.addView(U.hspacer(a, 8));
        TextView again = U.ghostButton(a, "重新识别", Theme.c(a, R.color.text_dim));
        again.setOnClickListener(v -> startRecognize(current, true));
        acts.addView(again, U.weight(1));
        root.addView(acts);

        root.addView(U.spacer(a, 8));
        TextView fix = U.ghostButton(a, "🤖 本地模型校对照（改同音错词）",
                Theme.c(a, R.color.accent));
        fix.setOnClickListener(v -> recheck());
        root.addView(fix);

        root.addView(U.spacer(a, 8));

        // 导出：三种格式各有用途（SRT 挂播放器 / TXT 进 Anki / MD 留档）
        LinearLayout exp = U.row(a);
        TextView srtBtn = U.ghostButton(a, "导出 SRT", Theme.c(a, R.color.primary));
        srtBtn.setOnClickListener(v -> export("srt"));
        exp.addView(srtBtn, U.weight(1));
        exp.addView(U.hspacer(a, 6));
        TextView txtBtn = U.ghostButton(a, "导出 TXT", Theme.c(a, R.color.primary));
        txtBtn.setOnClickListener(v -> export("txt"));
        exp.addView(txtBtn, U.weight(1));
        exp.addView(U.hspacer(a, 6));
        TextView mdBtn = U.ghostButton(a, "导出 MD", Theme.c(a, R.color.text_dim));
        mdBtn.setOnClickListener(v -> export("md"));
        exp.addView(mdBtn, U.weight(1));
        root.addView(exp);

        root.addView(U.tv(a, "导出的是完整字幕，含时间码与译文", 10,
                Theme.c(a, R.color.text_dim2)));

        root.addView(U.spacer(a, 14));

        // 句子列表
        for (int i = 0; i < current.lines.size(); i++) {
            root.addView(lineCard(current.lines.get(i), i));
            root.addView(U.spacer(a, 8));
        }
    }

    private View lineCard(Media.Line line, int idx) {
        LinearLayout card = U.card(a);
        card.setPadding(U.px(a, 14), U.px(a, 12), U.px(a, 14), U.px(a, 12));

        LinearLayout head = U.row(a);
        TextView num = U.tv(a, String.valueOf(idx + 1), 11, Theme.c(a, R.color.primary));
        num.setPadding(0, 0, U.px(a, 8), 0);
        head.addView(num);
        head.addView(U.tv(a, fmt(line.startMs) + " – " + fmt(line.endMs),
                10, Theme.c(a, R.color.text_dim2)), U.weight(1));

        TextView play = U.tv(a, "▶ 播这段", 11, Theme.c(a, R.color.accent));
        play.setPadding(U.px(a, 6), 0, 0, 0);
        play.setOnClickListener(v -> playSegment(line));
        head.addView(play);
        card.addView(head);

        TextView text = U.tv(a, "", 15, Theme.c(a, R.color.text));
        text.setLineSpacing(U.dp(a, 4), 1f);
        text.setPadding(0, U.px(a, 6), 0, 0);
        if (line.text.isEmpty()) {
            text.setText("（空）");
        } else {
            text.setText(clickable(a, line.text));
            text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            text.setHighlightColor(0x00000000);
        }
        card.addView(text);

        if (line.zh != null && !line.zh.isEmpty()) {
            TextView zh = U.tv(a, line.zh, 13, Theme.c(a, R.color.text_dim));
            zh.setLineSpacing(U.dp(a, 3), 1f);
            zh.setPadding(0, U.px(a, 4), 0, 0);
            card.addView(zh);
        }

        LinearLayout tools = U.row(a);
        tools.setPadding(0, U.px(a, 8), 0, 0);
        TextView say = U.tv(a, "🔊 读", 11, Theme.c(a, R.color.primary));
        say.setOnClickListener(v -> Tts.say(a, line.text));
        tools.addView(say);
        tools.addView(U.hspacer(a, 14));
        TextView edit = U.tv(a, "改文本", 11, Theme.c(a, R.color.warn));
        edit.setOnClickListener(v -> editLine(line));
        tools.addView(edit);
        tools.addView(U.hspacer(a, 14));
        TextView tr = U.tv(a, line.zh != null && !line.zh.isEmpty() ? "重译" : "翻译",
                11, Theme.c(a, R.color.accent));
        tr.setOnClickListener(v -> translateOne(line));
        tools.addView(tr);
        card.addView(tools);

        return card;
    }

    // ---------------------------------------------------------------- 动作

    private void playSegment(Media.Line line) {
        if (current == null) return;
        if (current.isVideo) {
            a.startActivity(new android.content.Intent(a,
                    com.englishstudy.app.ui.MediaPlayerActivity.class)
                    .putExtra("id", current.id)
                    .putExtra("start", line.startMs)
                    .putExtra("end", line.endMs));
        } else {
            playAudioSegment(line);
        }
    }

    private android.media.MediaPlayer audioPlayer;

    private void playAudioSegment(Media.Line line) {
        try {
            if (audioPlayer != null) {
                audioPlayer.stop();
                audioPlayer.release();
            }
            audioPlayer = new android.media.MediaPlayer();
            audioPlayer.setDataSource(Media.file(a, current).getAbsolutePath());
            audioPlayer.prepare();
            audioPlayer.seekTo(line.startMs);
            audioPlayer.start();
            final android.media.MediaPlayer mp = audioPlayer;
            final int end = line.endMs;
            final android.os.Handler h =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mp != null && mp.isPlaying() && mp.getCurrentPosition() >= end) {
                            mp.pause();
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (mp != null) h.postDelayed(this, 100);
                }
            }, 100);
        } catch (Throwable e) {
            android.widget.Toast.makeText(a, "播放失败：" + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void editLine(Media.Line line) {
        final android.widget.EditText et = new android.widget.EditText(a);
        et.setText(line.text);
        et.setTextSize(15);
        et.setPadding(U.px(a, 12), U.px(a, 10), U.px(a, 12), U.px(a, 10));
        new AlertDialog.Builder(a)
                .setTitle("修改这句（识别错误可以直接改）")
                .setView(new ScrollView(a) {{
                    addView(et);
                    setPadding(U.px(a, 16), 0, U.px(a, 16), 0);
                }})
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (!t.isEmpty()) {
                        line.text = t;
                        line.edited = true;
                        Media.save(a);
                        render();
                    }
                }).show();
    }

    private void translateOne(Media.Line line) {
        new Thread(() -> {
            ArticleTool.Result r = ArticleTool.translate(line.text, "en");
            String zh = r.fullTranslation();
            a.runOnUiThread(() -> {
                if (zh == null || zh.isEmpty()) {
                    // 本地没有 → 走模型
                    translateByModel(line);
                    return;
                }
                line.zh = zh;
                Media.save(a);
                render();
            });
        }, "tr1").start();
    }

    /**
     * 解析 {"1":"译文","2":"译文"} 这种返回。
     *
     * 4B 模型经常顺手加点 markdown 包裹或前后废话，
     * 所以先掐 { 到 } 区间，再容错解析。
     */
    private static java.util.Map<Integer, String> parseNumberedJson(String raw, int expect) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        String s = raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return out;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s.substring(a, b + 1));
            for (int i = 1; i <= expect; i++) {
                String v = o.optString(String.valueOf(i), "");
                if (v.isEmpty()) v = o.optString("\"" + i + "\"", "");
                if (!v.isEmpty()) out.put(i, v);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 退路：按行解析「1|译文」或「1. 译文」。 */
    private static java.util.Map<Integer, String> parseNumberedLines(String raw, int expect) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\d+\\s*[|.、:：)\\]]?\\s*(.+)$").matcher(t);
            if (!m.find()) continue;
            int idx = out.size() + 1;
            if (idx > expect) break;
            out.put(idx, m.group(1).replaceAll("[\"{}]", "").trim());
        }
        return out;
    }

    /** 去掉模型爱加的前缀和引号。 */
    private static String cleanZh(String s) {
        String t = s.trim();
        t = t.replaceAll("^[\"「『]+|[\"」』]+$", "");
        t = t.replaceAll("^(译文|翻译)[:：\\s]*", "");
        return t.trim();
    }

    /** 单句重译：走本地模型（4B，约 5-7 秒）。 */
    private void translateByModel(Media.Line line) {
        android.widget.Toast.makeText(a, "本地模型翻译中…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String zh = null;
            try {
                String out = com.englishstudy.app.data.Agent.askRaw(
                        "把下面这句英文译成自然的中文，只输出译文，不要任何解释：\n"
                                + line.text, 300);
                if (out != null) {
                    String t = out.trim();
                    int a0 = t.indexOf('{');
                    int b0 = t.lastIndexOf('}');
                    if (a0 >= 0 && b0 > a0) {
                        try {
                            t = new org.json.JSONObject(t.substring(a0, b0 + 1))
                                    .optString("zh", t);
                        } catch (Throwable ignored) {
                        }
                    }
                    // 去掉模型可能加的前缀
                    t = t.replaceAll("^[\"「]?译文[\"」]?[:：]?", "").trim();
                    if (!t.isEmpty()) zh = t;
                }
            } catch (Throwable ignored) {
            }
            final String fz = zh;
            a.runOnUiThread(() -> {
                if (fz == null) {
                    android.widget.Toast.makeText(a,
                            "翻译失败：确认「学伴设置 → 本地模型」已启用",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                line.zh = fz;
                Media.save(a);
                render();
            });
        }, "tr2").start();
    }

    private void translateAll() {
        if (current == null || current.lines.isEmpty()) return;
        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("翻译中…（本地模型一次翻完全部）");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            int lib = 0;
            // 1) 先走本地句库（71,642 对命中就不用模型，瞬时）
            for (Media.Line l : current.lines) {
                if (l.zh != null && !l.zh.isEmpty()) continue;
                ArticleTool.Result r = ArticleTool.translate(l.text, "en");
                String zh = r.fullTranslation();
                if (zh != null && !zh.isEmpty()) {
                    l.zh = zh;
                    lib++;
                }
            }

            // 2) 剩下的**一次性**交给模型。
            //    逐句调用每句 6-7 秒（4B 端侧），10 句就一分钟；
            //    拼成一篇一次翻完，分批 8 句一组，速度能压到 1/3。
            List<Media.Line> todo = new java.util.ArrayList<>();
            for (Media.Line l : current.lines) {
                if (l.zh == null || l.zh.isEmpty()) todo.add(l);
            }
            int model = 0;
            final int total = todo.size();
            int batch = 8;
            for (int i = 0; i < todo.size(); i += batch) {
                List<Media.Line> part = todo.subList(i, Math.min(i + batch, todo.size()));
                final int done = Math.min(i + batch, todo.size());
                a.runOnUiThread(() -> pd.setMessage("翻译中… " + done + "/" + total));
                StringBuilder sb = new StringBuilder();
                for (Media.Line l : part) {
                    sb.append(l.text.replace('\n', ' ')).append('\n');
                }
                String out = null;
                try {
                    // 让模型直接给 JSON 对象：{序号: 译文}。
                    // 试过「编号|译文」的行格式，4B 会自己改成 JSON —— 干脆顺着它。
                    out = com.englishstudy.app.data.Agent.askRaw(
                            "把下面每一行英文译成自然的中文。只输出一个 JSON 对象，"
                                    + "键是行号（从 1 开始），值是中文译文，不要任何解释。\n"
                                    + sb, 1200);
                } catch (Throwable ignored) {
                }
                if (out == null || out.trim().isEmpty()) continue;
                java.util.Map<Integer, String> got = parseNumberedJson(out, part.size());
                if (got.isEmpty()) got = parseNumberedLines(out, part.size());
                for (int k = 0; k < part.size(); k++) {
                    String t = got.get(k + 1);
                    if (t == null || t.isEmpty()) continue;
                    part.get(k).zh = cleanZh(t);
                    model++;
                }
            }

            final int flib = lib, fmodel = model;
            a.runOnUiThread(() -> {
                pd.dismiss();
                Media.save(a);
                render();
                android.widget.Toast.makeText(a,
                        "翻译完成：句库 " + flib + " 句 · 本地模型 " + fmodel + " 句",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "trall").start();
    }

    /** 手动重跑 LLM 纠错（不会覆盖用户改过的句子）。 */
    private void recheck() {
        if (current == null || current.lines.isEmpty()) return;
        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("本地模型正在校对照…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            List<String> raw = new java.util.ArrayList<>();
            for (Media.Line l : current.lines) raw.add(l.text);
            List<com.englishstudy.app.engine.AsrFix.Fixed> fx =
                    com.englishstudy.app.engine.AsrFix.fix(raw);
            int changed = 0;
            int n = Math.min(fx.size(), current.lines.size());
            for (int i = 0; i < n; i++) {
                com.englishstudy.app.engine.AsrFix.Fixed f = fx.get(i);
                if (f.changed && !current.lines.get(i).edited) {
                    current.lines.get(i).text = f.text;
                    changed++;
                }
            }
            final int fc = changed;
            a.runOnUiThread(() -> {
                pd.dismiss();
                Media.save(a);
                render();
                android.widget.Toast.makeText(a,
                        fc == 0 ? "没发现需要改的地方" : "修正了 " + fc + " 句",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "recheck").start();
    }

    /** 导出字幕（用户要求「可以选择保存」）。 */
    private void export(String kind) {
        if (current == null || current.lines.isEmpty()) return;
        boolean hasZh = current.translatedCount() > 0;
        java.io.File f;
        String mime;
        if ("srt".equals(kind)) {
            f = com.englishstudy.app.engine.Subtitle.srt(a, current, hasZh);
            mime = "application/x-subrip";
        } else if ("txt".equals(kind)) {
            f = com.englishstudy.app.engine.Subtitle.txt(a, current, hasZh);
            mime = "text/plain";
        } else {
            f = com.englishstudy.app.engine.Subtitle.markdown(a, current);
            mime = "text/markdown";
        }
        if (f == null || !f.exists() || f.length() == 0) {
            android.widget.Toast.makeText(a, "导出失败", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.io.File ff = f;
        final String mm = mime;
        new AlertDialog.Builder(a)
                .setTitle("已生成：" + f.getName())
                .setMessage(f.getAbsolutePath() + "\n\n"
                        + (hasZh ? "含双语对照" : "只有原文（先点「翻译全部」可加译文）"))
                .setNegativeButton("知道了", null)
                .setPositiveButton("保存 / 分享", (d, w) ->
                        com.englishstudy.app.engine.Subtitle.share(a, ff, mm))
                .show();
    }

    // ---------------------------------------------------------------- 识别

    /** 由 MainActivity 转发文件选择。 */
    public void onFile(Uri uri, boolean video) {
        if (uri == null) return;
        // 文件名：先查 DISPLAY_NAME（哈希路径取不到有意义的名字）
        String name = null;
        try {
            android.database.Cursor cur = a.getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cur != null) {
                if (cur.moveToFirst()) name = cur.getString(0);
                cur.close();
            }
        } catch (Throwable ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) {
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        if (name == null || name.isEmpty() || name.matches("[0-9a-f]{16,}")) {
            name = (video ? "视频 " : "音频 ")
                    + (Media.all(a).size() + 1);
        }
        Media.Item it = new Media.Item();
        it.id = "m" + System.currentTimeMillis();
        it.title = name;
        it.isVideo = video;
        it.createdAt = System.currentTimeMillis();
        it.fileName = it.id + (video ? ".mp4" : ".mp3");

        // 复制文件
        try {
            java.io.InputStream in = a.getContentResolver().openInputStream(uri);
            java.io.FileOutputStream out = new java.io.FileOutputStream(Media.file(a, it));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        } catch (Throwable e) {
            android.widget.Toast.makeText(a, "复制文件失败：" + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        Media.all(a).add(0, it);
        Media.save(a);
        current = it;
        render();
        startRecognize(it, false);
    }

    /** 抽音轨 → 切句 → 逐段识别 → 存缓存。 */
    private void startRecognize(Media.Item it, boolean force) {
        if (it.state == 2 && !force && !it.lines.isEmpty()) return;

        // 关键：独立页不会自动加载 ASR 引擎（只有主界面启动时才 init），
        // 这里主动踢一次并等待就绪，否则 ready() 永远 false，识别被静默跳过。
        if (!OfflineAsrEngine.ready()) {
            OfflineAsrEngine.init(a);
            boolean ok = false;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 40000) {
                if (OfflineAsrEngine.ready()) {
                    ok = true;
                    break;
                }
                if (OfflineAsrEngine.unavailable()) break;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            if (!ok) {
                android.widget.Toast.makeText(a,
                        "识别模型没加载起来：去「我的 → 离线语音」确认模型已下载",
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
        }

        it.state = 1;
        it.error = "";
        Media.save(a);
        render();

        ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("正在抽音轨…");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            Uri uri = Uri.fromFile(Media.file(a, it));
            AudioExtract.Pcm pcm = AudioExtract.extract(a, uri);
            if (!pcm.ok()) {
                fail(it, pcm.error);
                a.runOnUiThread(pd::dismiss);
                return;
            }
            it.durationMs = (long) (pcm.seconds() * 1000);

            a.runOnUiThread(() -> pd.setMessage("正在切句…"));
            List<AudioExtract.Seg> segs = AudioExtract.segment(pcm);
            if (segs.isEmpty()) {
                fail(it, "没检测到人声（可能是纯音乐或静音）");
                a.runOnUiThread(pd::dismiss);
                return;
            }

            List<Media.Line> lines = new java.util.ArrayList<>();
            final int total = segs.size();
            for (int i = 0; i < total; i++) {
                AudioExtract.Seg s = segs.get(i);
                final int idx = i + 1;
                a.runOnUiThread(() -> pd.setMessage(
                        "识别中 " + idx + "/" + total + "…"));
                float[] slice = AudioExtract.slice(pcm, s.startMs, s.endMs);
                String text = "";
                try {
                    text = OfflineAsrEngine.recognize(slice, AudioExtract.SR);
                } catch (Throwable ignored) {
                }
                Media.Line l = new Media.Line();
                l.startMs = s.startMs;
                l.endMs = s.endMs;
                l.text = text == null ? "" : text.trim();
                // 过滤 Whisper 的幻觉：占位标记 / 单字符 / 全大写无意义串
                if (!plausible(l.text)) continue;
                lines.add(l);
            }

            // 用本地 LLM 做一遍上下文纠错（"Seven" → "students" 这类同音错）
            if (!lines.isEmpty()) {
                a.runOnUiThread(() -> pd.setMessage("本地模型校对照…"));
                List<String> raw = new java.util.ArrayList<>();
                for (Media.Line l : lines) raw.add(l.text);
                List<com.englishstudy.app.engine.AsrFix.Fixed> fixed =
                        com.englishstudy.app.engine.AsrFix.fix(raw);
                int n = Math.min(fixed.size(), lines.size());
                for (int k = 0; k < n; k++) {
                    com.englishstudy.app.engine.AsrFix.Fixed f = fixed.get(k);
                    if (f.changed && !lines.get(k).edited) {
                        lines.get(k).text = f.text;
                    }
                }
            }

            it.lines.clear();
            it.lines.addAll(lines);
            it.state = lines.isEmpty() ? 3 : 2;
            if (lines.isEmpty()) it.error = "每句都没识别出内容";
            Media.save(a);

            a.runOnUiThread(() -> {
                pd.dismiss();
                render();
                android.widget.Toast.makeText(a,
                        "识别完成： " + lines.size() + " 句",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "recognize").start();
    }

    /** 判断识别结果是否可信（过滤 Whisper 在静音段的幻觉）。 */
    private static boolean plausible(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.isEmpty()) return false;
        // 纯占位标记
        if (s.matches("^[\\[\\(].*[\\]\\)]$")) return false;
        if (s.contains("[speaking in") || s.contains("[music") || s.contains("[silence")
                || s.contains("[BLANK") || s.contains("[inaudible")) return false;
        // 太短（单字符/单字母）
        if (s.length() < 2) return false;
        // 没有任何字母
        boolean hasLetter = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }

    private void fail(Media.Item it, String err) {
        it.state = 3;
        it.error = err;
        Media.save(a);
        a.runOnUiThread(this::render);
    }

    /**
     * 把句子做成可点词：点词进单词详情，返回还在这句字幕上（不会回主界面）。
     *
     * 和阅读页同一套：不覆写 updateDrawState 的话所有词都会变主题蓝，正文反而看不清，
     * 所以这里保持正常文字色，只给「已在生词本」的词加淡下划线。
     */
    public static android.text.Spannable clickable(Activity a, String text) {
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            char ch = text.charAt(i);
            if (!Character.isLetter(ch)) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && (Character.isLetter(text.charAt(j))
                    || text.charAt(j) == '\'' || text.charAt(j) == '-')) j++;
            final String w = text.substring(i, j).toLowerCase(java.util.Locale.US);
            final com.englishstudy.app.data.Dict.Entry e =
                    com.englishstudy.app.data.Dict.lookup(w);
            if (e != null) {
                final String word = e.word;
                final boolean inVocab =
                        com.englishstudy.app.data.Vocab.get(w) != null;
                if (inVocab) {
                    sp.setSpan(new android.text.style.UnderlineSpan(), i, j,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sp.setSpan(new android.text.style.ForegroundColorSpan(
                            Theme.c(a, R.color.accent)), i, j,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sp.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View v) {
                        a.startActivity(new android.content.Intent(a,
                                com.englishstudy.app.ui.WordActivity.class)
                                .putExtra("word", word));
                    }

                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(inVocab ? Theme.c(a, R.color.accent)
                                : Theme.c(a, R.color.text));
                        ds.setUnderlineText(false);
                        ds.setFakeBoldText(false);
                    }
                }, i, j, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return sp;
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}
