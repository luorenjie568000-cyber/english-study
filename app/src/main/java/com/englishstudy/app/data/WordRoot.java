package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 词根词缀（ECDICT 的 wordroot.txt，611 条）。
 *
 * 数据结构：{ "hom": {"meaning":"man,human","class":"root","example":[...],"origin":"Latin"} }
 *
 * 两个用法：
 *   1. 查一个词包含哪些词根/前缀/后缀（反向索引：example 里的词 → 词根）
 *   2. 列出全部词根（给「词根表」页用）
 */
public final class WordRoot {

    private static final String TAG = "WordRoot";

    public static final class Item {
        public String root = "";
        public String meaning = "";
        public String kind = "";      // root / prefix / suffix
        public String origin = "";
        public List<String> examples = new ArrayList<>();
        public String function = "";
    }

    private static final Map<String, Item> BY_ROOT = new HashMap<>();
    /** 例词 → 它出现的词根（反向索引）。 */
    private static final Map<String, List<Item>> BY_WORD = new HashMap<>();
    private static volatile boolean ready;

    private WordRoot() {
    }

    public static boolean ready() {
        return ready;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        long t0 = System.currentTimeMillis();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("wordroot.json"), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(1 << 19);
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject all = new JSONObject(sb.toString());
            java.util.Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject o = all.getJSONObject(key);
                Item item = new Item();
                item.root = o.optString("root", key);
                item.meaning = o.optString("meaning", "");
                item.kind = o.optString("class", "root");
                item.origin = o.optString("origin", "");
                item.function = o.optString("function", "");
                JSONArray ex = o.optJSONArray("example");
                if (ex != null) {
                    for (int i = 0; i < ex.length(); i++) {
                        String w = ex.optString(i, "").toLowerCase(Locale.US).trim();
                        if (w.isEmpty()) continue;
                        item.examples.add(w);
                        List<Item> list = BY_WORD.get(w);
                        if (list == null) {
                            list = new ArrayList<>(2);
                            BY_WORD.put(w, list);
                        }
                        list.add(item);
                    }
                }
                BY_ROOT.put(item.root, item);
            }
        } catch (Throwable e) {
            Log.e(TAG, "load failed", e);
        }
        ready = true;
        Log.i(TAG, "loaded " + BY_ROOT.size() + " roots, " + BY_WORD.size()
                + " indexed words in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 这个词直接出现在哪些词根的例词里。 */
    public static List<Item> of(String word) {
        if (!ready || word == null) return new ArrayList<>(0);
        List<Item> l = BY_WORD.get(word.toLowerCase(Locale.US));
        return l == null ? new ArrayList<>(0) : l;
    }

    /**
     * 反向拆词：这个词包含哪些词根/词缀。
     *
     * 不是精确的形态学分析，而是「按词根表里出现的前缀/后缀/词根字符串做包含匹配」，
     * 按匹配长度降序，最多返回 4 条。够用来给学习者一个"这词怎么来的"提示。
     */
    public static List<Item> decompose(String word) {
        List<Item> out = new ArrayList<>();
        if (!ready || word == null) return out;
        String w = word.toLowerCase(Locale.US);
        if (w.length() < 4) return out;

        for (Map.Entry<String, Item> e : BY_ROOT.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.US);
            String bare = key;
            // 去掉连字符和数字后缀（"-less" / "-ful1"）
            while (bare.startsWith("-") || bare.startsWith("_")) bare = bare.substring(1);
            while (bare.endsWith("-")) bare = bare.substring(0, bare.length() - 1);
            while (bare.length() > 0 && Character.isDigit(bare.charAt(bare.length() - 1))) {
                bare = bare.substring(0, bare.length() - 1);
            }
            if (bare.length() < 3) continue;
            // 词缀要求落在词首或词尾，词根只要包含
            boolean hit;
            String kind = e.getValue().kind.toLowerCase(Locale.US);
            if (kind.contains("prefix")) hit = w.startsWith(bare);
            else if (kind.contains("suffix")) hit = w.endsWith(bare);
            else hit = w.contains(bare);
            if (hit) out.add(e.getValue());
        }

        // 按词根长度降序（长的更具体）
        out.sort((a, b) -> b.root.length() - a.root.length());
        return out.size() > 4 ? new ArrayList<>(out.subList(0, 4)) : out;
    }

    /** 全部词根，按类型分组。 */
    public static List<Item> all() {
        List<Item> l = new ArrayList<>(BY_ROOT.values());
        l.sort((a, b) -> a.root.compareToIgnoreCase(b.root));
        return l;
    }

    public static int size() {
        return BY_ROOT.size();
    }
}
