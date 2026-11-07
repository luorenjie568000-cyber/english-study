package com.englishstudy.app.data;

import android.content.Context;

import com.englishstudy.app.engine.Card;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据导出：把生词本和复习状态写成 CSV / JSON。
 *
 * 离线 App 没有云同步，导出就是用户「数据是我的」的唯一保障，
 * 也是换机时唯一能把进度搬走的办法。
 */
public final class Export {

    private Export() {
    }

    /** 导出成 CSV（Excel 可直接打开）。 */
    public static File csv(Context c) throws Exception {
        File f = new File(c.getExternalFilesDir(null) != null
                ? c.getExternalFilesDir(null) : c.getFilesDir(),
                "english-study-" + stamp() + ".csv");
        PrintWriter w = new PrintWriter(f, "UTF-8");
        // BOM，Excel 才认 UTF-8
        w.print('\ufeff');
        w.println("单词,稳定度(天),难度,复习次数,遗忘次数,答对,答错,状态,下次复习");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (Card card : sorted()) {
            Dict.Entry e = Dict.lookup(card.w);
            w.print(card.w);
            w.print(',');
            w.print(String.format(Locale.US, "%.2f", card.s));
            w.print(',');
            w.print(String.format(Locale.US, "%.2f", card.d));
            w.print(',');
            w.print(card.reps);
            w.print(',');
            w.print(card.lapses);
            w.print(',');
            w.print(card.right);
            w.print(',');
            w.print(card.wrong);
            w.print(',');
            w.print(statusName(card.status));
            w.print(',');
            w.print(df.format(new Date(card.next)));
            if (e != null && !e.def.isEmpty()) {
                w.print(',');
                w.print(escape(e.def.replace("\n", "；")));
            }
            w.println();
        }
        w.close();
        return f;
    }

    /** 导出成 JSON（自己写，格式稳定，方便脚本处理）。 */
    public static File json(Context c) throws Exception {
        File f = new File(c.getExternalFilesDir(null) != null
                ? c.getExternalFilesDir(null) : c.getFilesDir(),
                "english-study-" + stamp() + ".json");
        PrintWriter w = new PrintWriter(f, "UTF-8");
        w.println("{");
        w.println("  \"exportedAt\": \"" + stamp() + "\",");
        w.println("  \"words\": " + Vocab.size() + ",");
        w.println("  \"cards\": [");
        List<Card> all = sorted();
        for (int i = 0; i < all.size(); i++) {
            Card card = all.get(i);
            w.println("    {\"w\": \"" + escape(card.w) + "\", "
                    + "\"s\": " + String.format(Locale.US, "%.4f", card.s) + ", "
                    + "\"d\": " + String.format(Locale.US, "%.4f", card.d) + ", "
                    + "\"reps\": " + card.reps + ", "
                    + "\"lapses\": " + card.lapses + ", "
                    + "\"right\": " + card.right + ", "
                    + "\"wrong\": " + card.wrong + ", "
                    + "\"status\": " + card.status + ", "
                    + "\"last\": " + card.last + ", "
                    + "\"next\": " + card.next + "}"
                    + (i < all.size() - 1 ? "," : ""));
        }
        w.println("  ]");
        w.println("}");
        w.close();
        return f;
    }

    private static List<Card> sorted() {
        List<Card> all = new ArrayList<>(Vocab.all());
        all.sort((a, b) -> a.w.compareTo(b.w));
        return all;
    }

    private static String statusName(int s) {
        switch (s) {
            case Card.NEW: return "新词";
            case Card.LEARNING: return "学习中";
            case Card.MASTERED: return "已掌握";
            case Card.LEECH: return "顽固词";
            default: return "已忽略";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }
}
