package com.englishstudy.app.engine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 词性标注（Part of Speech）。
 *
 * 为什么需要它：
 *   之前的规则引擎只能靠「字符串匹配」判断对错 —— 比如看到 "mine team" 就报错，
 *   但它并不知道 mine 是代词、team 是名词，只是背了一条规则。
 *   规则一多就互相打架，而且没法处理没写过的组合。
 *
 *   有了词性，「代词 + 名词」这种结构性错误可以被**推导**出来，
 *   而不是一条条列举。这是底层能力，不是补丁。
 *
 * 数据来源：ECDICT 的 trans 字段（"n. 矿\nvt. 挖掘\npron. 我的"），
 *   抽出词性标记后压成 116KB 的索引，覆盖 31,933 个词（91%）。
 */
public final class Pos {

    private static final String TAG = "Pos";

    /** 归一化后的词性：n/v/a/adv/pron/prep/conj/num/art/int/aux */
    public static final char NONE = 0;

    private static final Map<String, String> MAP = new HashMap<>();
    private static boolean loaded;

    private Pos() {
    }

    /** 从 assets/pos.json.gz 加载（首启一次，约 116KB → 3 万条）。 */
    public static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            InputStream in = c.getAssets().open("pos.json.gz");
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            parse(sb.toString());
            Log.i(TAG, "loaded " + MAP.size() + " pos entries");
        } catch (Throwable e) {
            Log.w(TAG, "load failed", e);
        }
    }

    /** 解析 {"word":"nva",...}（极简 JSON，避免依赖 org.json 的开销）。 */
    private static void parse(String json) {
        int i = 0, n = json.length();
        while (i < n) {
            int k1 = json.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = json.indexOf('"', k1 + 1);
            if (k2 < 0) break;
            String word = json.substring(k1 + 1, k2);
            int c1 = json.indexOf('"', k2 + 1);
            if (c1 < 0) break;
            int c2 = json.indexOf('"', c1 + 1);
            if (c2 < 0) break;
            MAP.put(word, json.substring(c1 + 1, c2));
            i = c2 + 1;
        }
    }

    public static boolean ready() {
        return loaded && !MAP.isEmpty();
    }

    /** 原始标签串，如 "nv" / "pron" / "aadv"。 */
    public static String tags(String word) {
        if (word == null) return "";
        String w = word.toLowerCase(Locale.US);
        String t = MAP.get(w);
        if (t != null) return t;
        // 复数/过去式/进行时退到词干
        String base = stem(w);
        if (base != null) {
            t = MAP.get(base);
            if (t != null) return t;
        }
        return "";
    }

    /** 是否具备某类词性。 */
    public static boolean is(String word, char pos) {
        return tags(word).indexOf(pos) >= 0;
    }

    public static boolean isNoun(String w) {
        return is(w, 'n');
    }

    public static boolean isVerb(String w) {
        return is(w, 'v');
    }

    public static boolean isAdj(String w) {
        return is(w, 'a');
    }

    public static boolean isAdv(String w) {
        return is(w, 'a') || tags(w).contains("adv");
    }

    /** 限定词 / 冠词 / 代词 —— 这些不能直接当名词用。 */
    public static boolean isDeterminer(String w) {
        String t = tags(w);
        return t.contains("pron") || t.contains("art");
    }

    /**
     * 判断一个词在**这个位置**能不能作名词。
     *
     * 只看词性不够：mine 既是 pron 又是 n（矿），
     * 但 "mine team" 里的 mine 明显是代词用法。
     * 这里用「前面的词」做消歧：代词性物主代词 + 名词 = 错。
     */
    public static boolean canBeNounHere(String word, String prevWord) {
        String t = tags(word);
        if (t.indexOf('n') < 0) return false;
        // mine 有名词义（矿），但作为限定词出现在名词前是错的
        if (isPossessivePronoun(word) && prevWord != null
                && !prevWord.isEmpty()) {
            return false;
        }
        return true;
    }

    /** 名词性物主代词：mine / yours / hers / theirs / ours / its。 */
    public static boolean isPossessivePronoun(String w) {
        switch (w.toLowerCase(Locale.US)) {
            case "mine": case "yours": case "hers": case "theirs": case "ours":
                return true;
            default:
                return false;
        }
    }

    /** 形容词性物主代词（可以修饰名词）。 */
    public static boolean isPossessiveAdj(String w) {
        switch (w.toLowerCase(Locale.US)) {
            case "my": case "your": case "her": case "their": case "our":
            case "its": case "his":
                return true;
            default:
                return false;
        }
    }

    /** 简单的词形还原（够用于词性查询）。 */
    private static String stem(String w) {
        if (w.length() < 4) return null;
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("es") && w.length() > 4) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && w.length() > 3) return w.substring(0, w.length() - 1);
        if (w.endsWith("ed") && w.length() > 4) return w.substring(0, w.length() - 2);
        if (w.endsWith("ing") && w.length() > 5) return w.substring(0, w.length() - 3);
        return null;
    }

    /**
     * 把一句话切成 (词, 起点, 终点) 序列，跳过标点和空白。
     */
    public static final class Token {
        public final String text;
        public final int start;
        public final int end;

        Token(String t, int s, int e) {
            text = t;
            start = s;
            end = e;
        }
    }

    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        if (src == null) return out;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isLetter(c)) {
                int j = i;
                while (j < n && (Character.isLetter(src.charAt(j))
                        || src.charAt(j) == '\'' || src.charAt(j) == '-')) j++;
                out.add(new Token(src.substring(i, j), i, j));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }
}
