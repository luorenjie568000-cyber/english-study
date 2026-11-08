package com.englishstudy.app.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 句子成分分析（规则引擎，纯本地）。
 *
 * 对应 1000h/Enjoy 的「句子分析」：把一句话拆成
 *   主语 / 谓语 / 宾语 / 表语 / 状语 / 定语 / 补语
 * 并标出时态、语态、句型。
 *
 * 不是完整句法分析器 —— 只覆盖英语学习里最常见的主干结构，
 * 按「找谓语动词 → 往前找主语 → 往后找宾语/表语」的顺序做。
 * 遇到从句就切出来单独标，不追求嵌套递归。
 */
public final class Syntax {

    /** 一个成分。 */
    public static final class Part {
        public final String role;   // 主语 / 谓语 / 宾语 / 表语 / 状语 / 定语 / 补语
        public final String text;
        public final int start;
        public final int end;

        Part(String role, String text, int start, int end) {
            this.role = role;
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Result {
        public final List<Part> parts = new ArrayList<>();
        public String pattern = "";      // 句型：主谓宾 / 主系表 / 主谓 …
        public String tense = "";        // 时态
        public String voice = "";         // 主被动
        public final List<String> notes = new ArrayList<>();

        public boolean ok() {
            return !parts.isEmpty();
        }
    }

    private Syntax() {
    }

    // ---------------------------------------------------------------- 词表

    private static final Set<String> BE = set(
            "am", "is", "are", "was", "were", "be", "been", "being");

    /** 情态动词 / 助动词。 */
    private static final Set<String> AUX = set(
            "do", "does", "did", "have", "has", "had",
            "will", "would", "shall", "should", "can", "could",
            "may", "might", "must", "ought");

    /** 介词：后面跟名词当状语/定语。 */
    private static final Set<String> PREP = set(
            "in", "on", "at", "by", "for", "with", "from", "to", "of",
            "about", "into", "onto", "over", "under", "through", "between",
            "among", "against", "during", "before", "after", "since", "until",
            "without", "within", "upon", "across", "along", "behind", "beside",
            "beyond", "despite", "except", "inside", "outside", "toward",
            "towards", "throughout", "per", "via", "like", "as");

    /** 连词：从句起点。 */
    private static final Set<String> CONJ = set(
            "although", "though", "because", "since", "while", "when", "whenever",
            "if", "unless", "until", "before", "after", "as", "so", "but", "and",
            "or", "yet", "whereas", "whether", "that", "which", "who", "whom",
            "whose", "where", "why", "how", "than");

    /** 代词主语。 */
    private static final Set<String> SUBJ_PRON = set(
            "i", "you", "he", "she", "it", "we", "they",
            "this", "that", "these", "those", "there", "one", "someone",
            "somebody", "everyone", "everybody", "nobody", "nothing", "everything");

    private static boolean isNumberWord(String w) {
        return set("one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve", "twenty",
                "thirty", "forty", "fifty", "hundred", "thousand", "million",
                "first", "second", "third", "fourth", "fifth", "many", "much",
                "few", "little", "several", "some", "all", "both", "most").contains(w);
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // ---------------------------------------------------------------- 分析

    public static Result analyze(String sentence) {
        Result r = new Result();
        if (sentence == null) return r;
        String text = sentence.trim();
        if (text.isEmpty()) return r;

        List<Tok> toks = tokenize(text);
        if (toks.isEmpty()) return r;

        int verbIdx = findPredicate(toks);
        if (verbIdx < 0) {
            r.notes.add("没找到谓语动词，可能是不完整句");
            return r;
        }

        Tok verb = toks.get(verbIdx);

        // ---- 主语：谓语往前，找第一个名词性短语
        int subjStart = -1, subjEnd = -1;
        for (int i = verbIdx - 1; i >= 0; i--) {
            Tok t = toks.get(i);
            if (t.isWord() && !isNumberWord(t.lower)
                    && (isNounLike(t) || SUBJ_PRON.contains(t.lower))) {
                subjStart = i;
                subjEnd = i + 1;
                // 往前吃掉限定词/形容词（The old man），但停在一名词处
                while (subjStart > 0) {
                    Tok prev = toks.get(subjStart - 1);
                    if (!prev.isWord()) break;
                    if (isDet(prev) || isNumberWord(prev.lower)
                            || "a".equals(posOf(prev.lower))) subjStart--;
                    else break;
                }
                break;
            }
        }
        if (subjStart >= 0) {
            r.parts.add(new Part("主语", raw(toks, subjStart, subjEnd),
                    toks.get(subjStart).start, toks.get(subjEnd - 1).end));
        } else {
            r.notes.add("没找到主语，可能是祈使句或省略句");
        }

        // ---- 谓语：动词 + 紧邻的助动词/副词
        int predStart = verbIdx, predEnd = verbIdx + 1;
        while (predStart > 0 && (AUX.contains(toks.get(predStart - 1).lower)
                || BE.contains(toks.get(predStart - 1).lower))) predStart--;
        // 被动：be + 分词，分词要算进谓语
        boolean beAtStart = BE.contains(toks.get(verbIdx).lower);
        if (beAtStart) {
            while (predEnd < toks.size() && isAdverb(toks.get(predEnd))) predEnd++;
            if (predEnd > verbIdx + 1
                    && predEnd - 1 < toks.size()
                    && isParticiple(toks.get(predEnd - 1).lower)) {
                // 已是分词
            } else if (predEnd < toks.size()
                    && isParticiple(toks.get(predEnd).lower)) {
                predEnd++;
            }
        }
        while (predEnd < toks.size() && isAdverb(toks.get(predEnd))) predEnd++;
        r.parts.add(new Part("谓语", raw(toks, predStart, predEnd),
                toks.get(predStart).start, toks.get(predEnd - 1).end));

        // ---- 时态与语态
        r.tense = tenseOf(toks, predStart, predEnd);

        // ---- 表语 or 宾语
        // 主系表：谓语只有 be（has been learning 是完成进行，不算）
        // 主系表：谓语里只有 be（+ 副词），没有实义动词
        boolean isBeSentence = false;
        for (int i = predStart; i < predEnd; i++) {
            Tok t = toks.get(i);
            String w = t.lower;
            if (isAdverb(t)) continue;          // 跳过 really / very
            if (BE.contains(w)) { isBeSentence = true; continue; }
            if (AUX.contains(w)) continue;
            isBeSentence = false;               // 出现实义动词 → 不是主系表
            break;
        }
        // be + 过去分词 = 被动语态（谓语的一部分），不是主系表
        boolean passive = hasBeInPredicate(toks, predStart, predEnd)
                && predEnd > predStart + 1
                && isParticiple(toks.get(predEnd - 1).lower)
                && !toks.get(predEnd - 1).lower.endsWith("ing");
        r.voice = passive ? "被动" : "主动";

        int objStart = -1, objEnd = -1;
        for (int i = predEnd; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (!t.isWord()) continue;
            if (PREP.contains(t.lower) || CONJ.contains(t.lower)) break;
            // 限定词/数词不能单独当宾语（the / a / two）
            if (isDet(t) || isNumberWord(t.lower)) { 
                // 但数词后面若紧跟名词，名词才是宾语的核
                continue;
            }
            if (isNounLike(t) || SUBJ_PRON.contains(t.lower)) {
                objStart = i;
                objEnd = i + 1;
                // 吃掉后面并列的名词（bread and butter）
                while (objEnd + 2 < toks.size()
                        && "and".equals(toks.get(objEnd).lower)
                        && toks.get(objEnd + 1).isWord()) {
                    objEnd += 2;
                }
                while (objStart > predEnd) {
                    Tok prev = toks.get(objStart - 1);
                    if (!prev.isWord()) break;
                    if (isDet(prev) || isNumberWord(prev.lower)) objStart--;
                    else break;
                }
                break;
            }
        }

        if (objStart >= 0) {
            String role = passive ? "宾语" : (isBeSentence ? "表语" : "宾语");
            r.parts.add(new Part(role, raw(toks, objStart, objEnd),
                    toks.get(objStart).start, toks.get(objEnd - 1).end));
        }

        // ---- 状语：介词短语 / 时间地点副词
        for (int i = predEnd; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (!t.isWord() || !PREP.contains(t.lower)) continue;
            int end = i + 1;
            while (end < toks.size() && !CONJ.contains(toks.get(end).lower)) {
                if (!toks.get(end).isWord()) break;   // 遇到标点就停
                end++;
                if (end - i > 5) break;
            }
            r.parts.add(new Part("状语", raw(toks, i, end),
                    toks.get(i).start, toks.get(end - 1).end));
            i = end - 1;
        }

        // ---- 句型
        boolean hasObj = objStart >= 0;
        if (passive) r.pattern = "主谓（被动）";
        else r.pattern = isBeSentence ? (hasObj ? "主系表" : "主系")
                : (hasObj ? "主谓宾" : "主谓");

        // ---- 长句提示
        int wc = toks.size();
        if (wc > 22) r.notes.add("长句 " + wc + " 词：先找谓语，再把状语单独读");
        if (r.parts.size() >= 3) r.notes.add("朗读时分 3 处停顿：主语后 / 谓语后 / 状语前");

        return r;
    }

    // ---------------------------------------------------------------- 判定

    private static boolean isDet(Tok t) {
        return set("the", "a", "an", "this", "that", "these", "those",
                "my", "your", "his", "her", "its", "our", "their",
                "some", "any", "no", "every", "each", "another", "both").contains(t.lower);
    }

    private static boolean isAdj(Tok t) {
        return t.lower.endsWith("ful") || t.lower.endsWith("ous")
                || t.lower.endsWith("ive") || t.lower.endsWith("able")
                || t.lower.endsWith("ible") || t.lower.endsWith("al")
                || t.lower.endsWith("ic") || t.lower.endsWith("ish")
                || t.lower.endsWith("less") || t.lower.endsWith("y")
                || t.lower.endsWith("ed") || t.lower.endsWith("ing")
                || t.lower.endsWith("er") || t.lower.endsWith("est")
                || set("old", "new", "big", "small", "good", "bad", "quiet",
                "nice", "happy", "sad", "cold", "hot", "young", "long", "short",
                "high", "low", "easy", "hard", "busy", "free", "full", "empty").contains(t.lower);
    }

    private static boolean isAdverb(Tok t) {
        return t.lower.endsWith("ly");
    }

    private static boolean isNounLike(Tok t) {
        // 简化：不是动词、不是介词/连词、不是副词，就当名词
        if (AUX.contains(t.lower) || BE.contains(t.lower)) return false;
        if (PREP.contains(t.lower) || CONJ.contains(t.lower)) return false;
        if (isAdverb(t)) return false;
        return true;
    }

    /** 找主谓语动词：跳过助动词后面的实义动词，优先取最后一个非 BE 的动词。 */
    private static int findPredicate(List<Tok> toks) {
        int firstAux = -1;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (!t.isWord()) continue;
            String w = t.lower;
            if (BE.contains(w)) {
                // be + 过去分词 → 被动，整个算谓语，返回 be 的位置
                int j = i + 1;
                while (j < toks.size() && isAdverb(toks.get(j))) j++;
                if (j < toks.size() && isParticiple(toks.get(j).lower)) return i;
                if (j < toks.size() && toks.get(j).lower.endsWith("ing")) return j; // be + ing
                if (firstAux < 0) firstAux = i;
                continue;
            }
            if (AUX.contains(w)) {
                if (firstAux < 0) firstAux = i;
                continue;
            }
            // 数词/量词绝不可能是谓语
            if (isNumberWord(w)) continue;
            String pos = posOf(w);
            // 词典明确说是名词/形容词/副词的，绝不可能是谓语
            boolean verb = "v".equals(pos) || (pos.isEmpty() && looksVerb(w));
            if ("n".equals(pos) || "a".equals(pos) || "r".equals(pos)) verb = false;
            // 词典说是名词/形容词的，不当谓语（解决 The old man walked 把 old 当动词）
            if (!verb && !pos.isEmpty()) continue;
            if (verb) {
                if (firstAux >= 0) return i;
                return i;
            }
        }
        return firstAux >= 0 ? firstAux : -1;
    }

    private static boolean hasBeInPredicate(List<Tok> toks, int from, int to) {
        for (int i = from; i < to; i++) {
            if (BE.contains(toks.get(i).lower)) return true;
        }
        return false;
    }

    /** 过去分词判定（不规则表 + -ed/-en 后缀）。 */
    private static boolean isParticiple(String w) {
        if (w.endsWith("ed") || w.endsWith("en")) return true;
        return set("written", "spoken", "taken", "given", "made", "done",
                "seen", "known", "eaten", "broken", "chosen", "driven",
                "bought", "brought", "caught", "taught", "thought", "sent",
                "built", "found", "held", "kept", "left", "lost", "paid",
                "read", "said", "sold", "told", "understood", "won", "put",
                "set", "cut", "hit", "let", "shut").contains(w);
    }

    /** 查词典词性：v = 动词, n = 名词, a/j = 形容词, r = 副词。 */
    private static String posOf(String w) {
        try {
            com.englishstudy.app.data.Dict.Entry e =
                    com.englishstudy.app.data.Dict.lookup(w);
            if (e != null && e.pos != null && !e.pos.isEmpty()) {
                String p0 = e.pos.toLowerCase(Locale.US);
                if (p0.contains("v")) return "v";
                if (p0.contains("n")) return "n";
                if (p0.contains("a") || p0.contains("j")) return "a";
                if (p0.contains("r")) return "r";
                return p0;
            }
            // 词典没收录：用不规则动词表兜底（bought / wrote / gave …）
            if (IRREGULAR_VERB.contains(w)) return "v";
            if (w.endsWith("ed") || w.endsWith("ing")) return "v";
            return "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 不规则动词（过去式/过去分词），词典可能查不到它们的词性。 */
    private static final Set<String> IRREGULAR_VERB = set(
            "bought", "brought", "caught", "taught", "thought", "sent", "built",
            "found", "held", "kept", "left", "lost", "paid", "read", "said",
            "sold", "told", "understood", "won", "written", "spoken", "taken",
            "given", "made", "done", "seen", "known", "eaten", "drunk", "broken",
            "chosen", "driven", "wrote", "went", "came", "got", "gave", "took",
            "saw", "knew", "thought", "felt", "left", "met", "ran", "sat",
            "stood", "heard", "put", "set", "cut", "hit", "let", "shut");

    private static String posOfOld(String w) {
        try {
            com.englishstudy.app.data.Dict.Entry e =
                    com.englishstudy.app.data.Dict.lookup(w);
            if (e == null || e.pos == null) return "";
            String p = e.pos.toLowerCase(Locale.US);
            // 优先返回动词标记
            if (p.contains("v")) return "v";
            if (p.contains("n")) return "n";
            if (p.contains("a") || p.contains("j")) return "a";
            if (p.contains("r")) return "r";
            return p;
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean looksVerb(String w) {
        if (w.length() < 2) return false;
        return w.endsWith("ed") || w.endsWith("ing") || w.endsWith("es")
                || w.endsWith("s") || w.endsWith("d")
                || set("go", "get", "make", "take", "give", "see", "say", "know",
                "think", "come", "want", "use", "find", "tell", "ask", "work",
                "seem", "feel", "try", "leave", "call", "need", "become",
                "buy", "walk", "read", "sit", "stop", "start", "open", "close",
                "like", "love", "hate", "eat", "drink", "sleep", "run", "put",
                "keep", "hold", "turn", "move", "live", "play", "write", "speak",
                "learn", "teach", "help", "show", "hear", "watch", "look").contains(w);
    }

    /** 谓语段里 be 后面紧跟的是过去分词 → 被动。 */
    private static boolean ppAfterBe(List<Tok> toks, int from, int to) {
        boolean beSeen = false;
        for (int i = from; i < to; i++) {
            String w = toks.get(i).lower;
            if (BE.contains(w)) {
                beSeen = true;
                continue;
            }
            if (beSeen) {
                if (w.endsWith("ing")) return false;
                return w.endsWith("ed") || w.endsWith("en")
                        || set("written", "spoken", "taken", "given", "made",
                        "done", "seen", "known", "eaten", "broken", "bought",
                        "sent", "built", "found", "held", "kept", "left",
                        "lost", "paid", "read", "said", "sold", "told").contains(w);
            }
        }
        return false;
    }

    /** be + 过去分词 = 被动；be + 现在分词 = 进行（主动）。 */
    private static boolean hasPastParticiple(List<Tok> toks, int verbIdx) {
        boolean beBefore = false;
        for (int i = 0; i < verbIdx; i++) {
            if (BE.contains(toks.get(i).lower)) beBefore = true;
        }
        if (!beBefore) return false;
        // 动词本身是 -ing → 主动进行
        String v = toks.get(verbIdx).lower;
        if (v.endsWith("ing")) return false;
        // -ed/-en 才是过去分词
        return v.endsWith("ed") || v.endsWith("en")
                || set("written", "spoken", "taken", "given", "made", "done",
                "seen", "known", "eaten", "drunk", "broken", "chosen", "driven",
                "bought", "brought", "caught", "taught", "thought", "sent",
                "built", "found", "held", "kept", "left", "lost", "paid",
                "read", "said", "sold", "told", "understood", "won").contains(v);
    }

    private static String tenseOf(List<Tok> toks, int from, int to) {
        boolean hasWill = false, hasHave = false, hasHad = false,
                hasBe = false, pastMark = false;
        for (int i = from; i < to; i++) {
            String w = toks.get(i).lower;
            if ("will".equals(w) || "shall".equals(w)) hasWill = true;
            if ("have".equals(w) || "has".equals(w)) hasHave = true;
            if ("had".equals(w)) hasHad = true;
            if (BE.contains(w)) hasBe = true;
            if ("was".equals(w) || "were".equals(w) || "did".equals(w)) pastMark = true;
        }
        boolean ing = false, pp = false;
        for (int i = from; i < to; i++) {
            String w = toks.get(i).lower;
            if (w.endsWith("ing")) ing = true;
            if (w.endsWith("ed") || w.endsWith("en")) pp = true;
        }
        if (hasWill && hasHave) return "将来完成时";
        if (hasWill) return ing ? "将来进行时" : "一般将来时";
        if (hasHad) return pp ? "过去完成时" : "过去完成时";
        if (hasHave) return pp ? "现在完成时" : "现在完成进行时";
        if (hasBe && ing) return pastMark ? "过去进行时" : "现在进行时";
        if (pastMark) return "一般过去时";
        if (pp && !hasBe) return "一般过去时";
        return "一般现在时";
    }

    // ---------------------------------------------------------------- 分词

    private static final class Tok {
        String raw;
        String lower;
        int start;
        int end;
        boolean punct;

        boolean isWord() {
            return !punct;
        }
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '\'') {
                int j = i;
                while (j < n && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '\'')) j++;
                Tok t = new Tok();
                t.raw = s.substring(i, j);
                t.lower = t.raw.toLowerCase(Locale.US).replace("’", "'");
                t.start = i;
                t.end = j;
                out.add(t);
                i = j;
            } else {
                Tok t = new Tok();
                t.raw = String.valueOf(c);
                t.lower = t.raw;
                t.start = i;
                t.end = i + 1;
                t.punct = true;
                out.add(t);
                i++;
            }
        }
        return out;
    }

    private static String raw(List<Tok> toks, int from, int to) {
        if (from < 0 || to > toks.size() || from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(toks.get(i).raw);
        }
        return sb.toString();
    }

    private static String span(List<Tok> toks, int from, int to) {
        if (from < 0 || to > toks.size() || from >= to) return "";
        return toks.get(from).raw + " … " + toks.get(to - 1).raw;
    }
}
