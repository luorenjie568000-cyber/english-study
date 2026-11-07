package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 例句库：assets/sents4.tsv.gz
 *
 * 行格式：word \t en1 \x1f zh1 \x1e en2 \x1f zh2 …
 * 中文可能为空（该句 Tatoeba 里没有对应译文）。
 */
public final class Sentences {

    private static final String TAG = "Sentences";
    private static final char SEG = '\u001e';
    private static final char FIELD = '\u001f';

    /** 一条例句。 */
    public static final class Item {
        public final String en;
        public final String zh;

        Item(String en, String zh) {
            this.en = en;
            this.zh = zh;
        }

        public boolean hasZh() {
            return zh != null && !zh.isEmpty();
        }
    }

    private static final Map<String, List<Item>> MAP = new HashMap<>(8192);
    private static volatile boolean ready;
    private static int withZh;

    private Sentences() {
    }

    public static boolean ready() {
        return ready;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        long t0 = System.currentTimeMillis();
        try {
            GZIPInputStream gz = new GZIPInputStream(
                    new BufferedInputStream(c.getAssets().open("sents4.tsv.gz"), 1 << 15));
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(gz, StandardCharsets.UTF_8), 1 << 15);
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String w = line.substring(0, tab);
                List<Item> list = new ArrayList<>(4);
                for (String seg : line.substring(tab + 1).split(String.valueOf(SEG))) {
                    int bar = seg.indexOf(FIELD);
                    String en = (bar < 0 ? seg : seg.substring(0, bar)).trim();
                    String zh = bar < 0 ? "" : seg.substring(bar + 1).trim();
                    if (en.isEmpty()) continue;
                    list.add(new Item(en, zh));
                    if (!zh.isEmpty()) withZh++;
                }
                if (!list.isEmpty()) MAP.put(w, list);
            }
            r.close();
        } catch (Throwable e) {
            Log.e(TAG, "load failed", e);
        }
        ready = true;
        Log.i(TAG, "loaded " + MAP.size() + " words / " + withZh + " 带译文 in "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 取例句（含中文），自动做词形还原。 */
    public static List<Item> items(String word) {
        if (word == null) return new ArrayList<>(0);
        String w = word.toLowerCase();
        List<Item> l = MAP.get(w);
        if (l != null) return l;

        String base = Dict.lemmaOf(w);
        if (base != null) {
            l = MAP.get(base);
            if (l != null) return l;
        }
        for (String c : Dict.lemmas(w)) {
            l = MAP.get(c);
            if (l != null) return l;
        }
        return new ArrayList<>(0);
    }

    /** 只要英文（旧接口，保持兼容）。 */
    public static List<String> of(String word) {
        List<Item> items = items(word);
        List<String> out = new ArrayList<>(items.size());
        for (Item it : items) out.add(it.en);
        return out;
    }

    public static int wordCount() {
        return MAP.size();
    }

    public static int withZhCount() {
        return withZh;
    }
}
