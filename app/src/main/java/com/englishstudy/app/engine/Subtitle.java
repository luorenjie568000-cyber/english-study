package com.englishstudy.app.engine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.englishstudy.app.data.Media;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 字幕导出：SRT / 纯文本 / 中英对照 Markdown。
 *
 * 为什么三种都要：
 *   - SRT 能直接挂回播放器看（市面上的字幕工具都吃这个）
 *   - TXT 方便丢进 Anki / 词频统计
 *   - Markdown 是「学完了想留一份」的形态
 */
public final class Subtitle {

    private Subtitle() {
    }

    /** 导出为 SRT（含译文时可作为双语字幕）。 */
    public static File srt(Context c, Media.Item it, boolean withZh) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Media.Line l : it.lines) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            sb.append(n++).append('\n');
            sb.append(ts(l.startMs)).append(" --> ").append(ts(l.endMs)).append('\n');
            sb.append(l.text.trim()).append('\n');
            if (withZh && l.zh != null && !l.zh.isEmpty()) {
                sb.append(l.zh.trim()).append('\n');
            }
            sb.append('\n');
        }
        return write(c, it, "srt", sb.toString());
    }

    /** 导出为纯文本（每句一行）。 */
    public static File txt(Context c, Media.Item it, boolean withZh) {
        StringBuilder sb = new StringBuilder();
        for (Media.Line l : it.lines) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            sb.append(l.text.trim());
            if (withZh && l.zh != null && !l.zh.isEmpty()) {
                sb.append("\t").append(l.zh.trim());
            }
            sb.append('\n');
        }
        return write(c, it, "txt", sb.toString());
    }

    /** 导出为中英对照 Markdown（带时间码，方便回到原片）。 */
    public static File markdown(Context c, Media.Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(it.title).append("\n\n");
        sb.append("时长 ").append(it.durationMs / 1000).append(" 秒 · ")
                .append(it.lines.size()).append(" 句 · ")
                .append(it.wordCount()).append(" 词\n\n");
        sb.append("| # | 时间 | 原文 | 译文 |\n|---|---|---|---|\n");
        int i = 1;
        for (Media.Line l : it.lines) {
            if (l.text == null || l.text.trim().isEmpty()) continue;
            sb.append("| ").append(i++).append(" | ")
                    .append(fmtShort(l.startMs)).append(" | ")
                    .append(esc(l.text)).append(" | ")
                    .append(esc(l.zh == null ? "" : l.zh)).append(" |\n");
        }
        return write(c, it, "md", sb.toString());
    }

    private static String esc(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static File write(Context c, Media.Item it, String ext, String content) {
        File dir = new File(c.getExternalFilesDir(null), "subtitles");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        String safe = it.title == null ? "subtitle"
                : it.title.replaceAll("[\\\\/:*?\"<>|]", "_");
        File f = new File(dir, safe + "." + ext);
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
        return f;
    }

    /** SRT 时间格式 00:00:01,500 */
    private static String ts(int ms) {
        int h = ms / 3600000;
        int m = (ms % 3600000) / 60000;
        int s = (ms % 60000) / 1000;
        int r = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, r);
    }

    private static String fmtShort(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /** 用系统分享面板发出去（用户要求的「可以选择保存」）。 */
    public static void share(Context c, File f, String mime) {
        try {
            Uri u = Uri.parse("file://" + f.getAbsolutePath());
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "保存 / 分享字幕");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(chooser);
        } catch (Throwable ignored) {
        }
    }
}
