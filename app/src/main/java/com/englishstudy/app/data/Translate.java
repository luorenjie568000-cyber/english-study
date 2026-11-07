package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 离线整句翻译。
 *
 * 两级：
 *   1) 精确匹配 Tatoeba 人工译文（71,917 句对，已繁转简）
 *   2) 未命中时按句拆分，逐词给出释义（词典驱动，非机翻）
 *
 * 归一化只做大小写/空白/末尾标点，不改词序，避免误配。
 */
public final class Translate {

    private static final String TAG = "Translate";
    private static final Map<String, String> PAIRS = new HashMap<>(100000);
    private static volatile boolean ready;

    /** 一段翻译结果。 */
    public static final class Result {
        public String source = "";
        /** 人工译文；为空表示未命中。 */
        public String translation = "";
        /** 逐词对照（未命中时的兜底）。 */
        public final List<Word> words = new ArrayList<>();
        public boolean exact;

        public boolean hasTranslation() {
            return translation != null && !translation.isEmpty();
        }
    }

    /** 逐词对照中的一项。 */
    public static final class Word {
        public String text = "";
        public String pos = "";
        public String def = "";
        public boolean known;
    }

    private Translate() {
    }

    public static boolean ready() {
        return ready;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        long t0 = System.currentTimeMillis();
        try {
            GZIPInputStream gz = new GZIPInputStream(c.getAssets().open("sent_zh.tsv.gz"));
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(gz, StandardCharsets.UTF_8), 1 << 16);
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                PAIRS.put(norm(line.substring(0, tab)), line.substring(tab + 1));
            }
            r.close();
        } catch (Throwable e) {
            Log.e(TAG, "load failed", e);
        }
        ready = true;
        Log.i(TAG, "loaded " + PAIRS.size() + " pairs in "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 归一化：小写、压缩空白、去末尾标点。 */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US).trim();
        StringBuilder sb = new StringBuilder(t.length());
        boolean sp = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!sp && sb.length() > 0) sb.append(' ');
                sp = true;
            } else {
                sb.append(ch);
                sp = false;
            }
        }
        String out = sb.toString().trim();
        while (out.length() > 1 && ".!?,;:\"'".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }

    /** 整段翻译：先试整句，再按句拆分逐句试。 */
    public static Result translate(String text) {
        Result res = new Result();
        if (text == null) return res;
        res.source = text.trim();
        if (res.source.isEmpty()) return res;

        // 1) 整段精确匹配
        String hit = PAIRS.get(norm(res.source));
        if (hit != null) {
            res.translation = hit;
            res.exact = true;
            return res;
        }

        // 2) 拆句逐句翻译，能拼就拼
        String[] sents = res.source.split("(?<=[.!?])\\s+");
        StringBuilder joined = new StringBuilder();
        int matched = 0;
        for (String s : sents) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            String zh = PAIRS.get(norm(t));
            if (zh != null) {
                matched++;
                if (joined.length() > 0) joined.append(' ');
                joined.append(zh);
            }
        }
        if (matched > 0 && matched >= (sents.length + 1) / 2) {
            res.translation = joined.toString();
            res.exact = false;
            return res;
        }

        // 3) 兜底：逐词对照
        res.words.addAll(wordByWord(res.source));
        return res;
    }

    /** 逐词对照：切词 → 查词典 → 给词性和简短释义。 */
    public static List<Word> wordByWord(String text) {
        List<Word> out = new ArrayList<>();
        if (text == null) return out;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '\'')) j++;
            String raw = text.substring(i, j);

            Word w = new Word();
            w.text = raw;
            Dict.Entry e = Dict.lookup(raw);
            if (e != null) {
                w.known = true;
                w.pos = shortPos(e.pos);
                String fixed = FIXED.get(raw.toLowerCase(Locale.US));
                w.def = fixed != null ? fixed : shortDef(e.def);
            }
            out.add(w);
            i = j;
        }
        return out;
    }

    private static String shortPos(String pos) {
        if (pos == null || pos.isEmpty()) return "";
        String p = pos.trim();
        if (p.startsWith("n")) return "名";
        if (p.startsWith("v")) return "动";
        if (p.startsWith("a") || p.startsWith("s")) return "形";
        if (p.startsWith("r") || p.startsWith("adv")) return "副";
        if (p.startsWith("p") || p.startsWith("prep")) return "介";
        if (p.startsWith("c") || p.startsWith("conj")) return "连";
        if (p.startsWith("pron")) return "代";
        return "";
    }

    /** 取释义第一条，最多 12 字。 */
    private static String shortDef(String def) {
        if (def == null || def.isEmpty()) return "";
        String d = def;
        int cut = d.indexOf('\n');
        if (cut > 0) d = d.substring(0, cut);
        cut = d.indexOf('；');
        if (cut < 0) cut = d.indexOf(',');
        if (cut > 0) d = d.substring(0, cut);
        d = d.trim();
        if (d.length() > 12) d = d.substring(0, 12) + "…";
        return d;
    }

    /** 极常用虚词/单字母给一个稳定的默认释义，避免取到「字母 A」这类义项。 */
    private static final Map<String, String> FIXED = new HashMap<>();

    static {
        FIXED.put("a", "一个");
        FIXED.put("an", "一个");
        FIXED.put("the", "这/那");
        FIXED.put("i", "我");
        FIXED.put("you", "你");
        FIXED.put("he", "他");
        FIXED.put("she", "她");
        FIXED.put("it", "它");
        FIXED.put("we", "我们");
        FIXED.put("they", "他们");
        FIXED.put("is", "是");
        FIXED.put("am", "是");
        FIXED.put("are", "是");
        FIXED.put("was", "是(过去)");
        FIXED.put("were", "是(过去)");
        FIXED.put("be", "是");
        FIXED.put("to", "到/去");
        FIXED.put("of", "的");
        FIXED.put("in", "在…里");
        FIXED.put("on", "在…上");
        FIXED.put("at", "在");
        FIXED.put("and", "和");
        FIXED.put("or", "或");
        FIXED.put("but", "但是");
        FIXED.put("for", "为了");
        FIXED.put("with", "和…一起");
        FIXED.put("by", "通过");
        FIXED.put("as", "作为");
        FIXED.put("that", "那个");
        FIXED.put("this", "这个");
        FIXED.put("not", "不");
        FIXED.put("no", "不/没有");
        FIXED.put("do", "做");
        FIXED.put("does", "做");
        FIXED.put("did", "做(过去)");
        FIXED.put("have", "有");
        FIXED.put("has", "有");
        FIXED.put("had", "有(过去)");
        FIXED.put("my", "我的");
        FIXED.put("your", "你的");
        FIXED.put("his", "他的");
        FIXED.put("her", "她的");
        FIXED.put("its", "它的");
        FIXED.put("our", "我们的");
        FIXED.put("their", "他们的");
    }

    public static int pairCount() {
        return PAIRS.size();
    }
}
