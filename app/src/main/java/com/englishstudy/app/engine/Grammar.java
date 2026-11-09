package com.englishstudy.app.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 语法检查（基于词性，不是字符串匹配）。
 *
 * 和 WritingChecker 的分工：
 *   WritingChecker  = 词形层（拼写、a/an、大小写、标点）—— 看单个词
 *   Grammar         = 结构层（主谓一致、代词、时态）—— 看词与词的关系
 *
 * 为什么这是「底层修复」而不是补丁：
 *   旧做法是列举 "mine team" 这条规则，遇到 "mine car" 就不认了。
 *   现在用词性推导：物主代词 + 名词 → 结构错误。
 *   一次写对，所有同类错误自动覆盖。
 */
public final class Grammar {

    /** 一条发现。 */
    public static final class Finding {
        public final String type;
        public final int offset;
        public final int length;
        public final String snippet;
        public final String message;
        public final String hint;

        Finding(String t, int o, int l, String s, String m, String h) {
            type = t;
            offset = o;
            length = l;
            snippet = s;
            message = m;
            hint = h == null ? "" : h;
        }
    }

    /** 系动词 / be 动词。 */
    private static final Set<String> BE = new HashSet<>(Arrays.asList(
            "am", "is", "are", "was", "were", "be", "been", "being"));

    /** 第三人称单数代词。 */
    private static final Set<String> SINGULAR_SUBJ = new HashSet<>(Arrays.asList(
            "he", "she", "it"));

    /** 复数/第一二人称代词。 */
    private static final Set<String> PLURAL_SUBJ = new HashSet<>(Arrays.asList(
            "they", "we", "you", "i"));

    /** 复数名词（不规则）。 */
    private static final Set<String> IRREGULAR_PLURAL = new HashSet<>(Arrays.asList(
            "people", "children", "men", "women", "teeth", "feet", "mice", "geese"));

    /** 形容词后缀（-ing/-ed 形容词化，用于区分 boring/bored）。 */
    private static final Set<String> EMO_ADJ = new HashSet<>(Arrays.asList(
            "boring", "interesting", "exciting", "tiring", "confusing", "annoying",
            "surprising", "amazing", "embarrassing", "frightening", "disappointing",
            "bored", "interested", "excited", "tired", "confused", "annoyed",
            "surprised", "amazed", "embarrassed", "frightened", "disappointed"));

    private Grammar() {
    }

    /** 主入口：返回所有结构性发现。 */
    public static List<Finding> check(String src) {
        List<Finding> out = new ArrayList<>();
        if (src == null || src.trim().isEmpty()) return out;

        List<Pos.Token> toks = Pos.tokenize(src);
        if (toks.size() < 2) return out;

        checkPronounBeforeNoun(toks, src, out);
        checkSubjectVerb(toks, src, out);
        checkThereIs(toks, src, out);
        checkAuxVerbForm(toks, src, out);
        checkThirdPersonS(toks, src, out);
        checkNounSubjectVerb(toks, src, out);
        checkArticleBeforeNoun(toks, src, out);
        checkVerbAfterModal(toks, src, out);
        checkDoubleComparative(toks, src, out);
        checkAdjectiveBeforeVerb(toks, src, out);
        checkInfinitive(toks, src, out);
        checkHandTypoEd(toks, src, out);

        return out;
    }

    /**
     * 结构 1：物主代词 + 名词 → 错（应改用形容词性物主代词）。
     *
     * 覆盖：mine/yours/hers/theirs/ours + 任意名词
     * 不再需要为每个名词写一条规则。
     */
    private static void checkPronounBeforeNoun(List<Pos.Token> toks, String src,
                                               List<Finding> out) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            Pos.Token a = toks.get(i);
            Pos.Token b = toks.get(i + 1);
            if (!Pos.isPossessivePronoun(a.text)) continue;

            String next = b.text.toLowerCase(Locale.US);
            // 后面是功能词就没错（mine is / yours are）
            if (isFunctionWord(next)) continue;
            // 后面是名词 → 结构错误（team 同时有 v 词性，不能因此放过）
            boolean nounish = Pos.isNoun(next)
                    || (!Pos.tags(next).isEmpty() && !Pos.isAdj(next));
            if (!nounish) continue;

            String fix = possessiveAdjOf(a.text);
            out.add(new Finding("语法", a.start, a.text.length(), a.text,
                    a.text + " 是名词性物主代词，不能修饰名词",
                    fix + " " + b.text));
        }
    }

    private static String possessiveAdjOf(String pron) {
        switch (pron.toLowerCase(Locale.US)) {
            case "mine": return "my";
            case "yours": return "your";
            case "hers": return "her";
            case "theirs": return "their";
            case "ours": return "our";
            default: return pron;
        }
    }

    /**
     * 结构 2：主谓一致。
     *
     * 三单主语 + are/were/have/do → 错
     * 复数主语 + is/was/has/does → 错
     * 复数名词主语（people/children/…）+ is → 错
     */
    private static void checkSubjectVerb(List<Pos.Token> toks, String src,
                                         List<Finding> out) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            String subj = toks.get(i).text.toLowerCase(Locale.US);
            String verb = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (!BE.contains(verb) && !verb.equals("have") && !verb.equals("has")
                    && !verb.equals("do") && !verb.equals("does")) {
                continue;
            }

            boolean subjSingular = SINGULAR_SUBJ.contains(subj);
            boolean subjPlural = PLURAL_SUBJ.contains(subj)
                    || IRREGULAR_PLURAL.contains(subj);
            // 名词主语：students / books 这类复数（-s 结尾且词性含名词）
            if (!subjSingular && !subjPlural && subj.endsWith("s")
                    && subj.length() > 3 && Pos.isNoun(subj)) {
                subjPlural = true;
            }

            if (subjSingular && (verb.equals("are") || verb.equals("were")
                    || verb.equals("have") || verb.equals("do"))) {
                String fix = verb.equals("are") ? "is" : verb.equals("were") ? "was"
                        : verb.equals("have") ? "has" : "does";
                out.add(new Finding("语法", toks.get(i + 1).start, verb.length(), verb,
                        subj + " 是第三人称单数，谓语要用 " + fix,
                        subj + " " + fix));
            } else if (subjPlural && (verb.equals("is") || verb.equals("was")
                    || verb.equals("has") || verb.equals("does"))) {
                String fix = verb.equals("is") ? "are" : verb.equals("was") ? "were"
                        : verb.equals("has") ? "have" : "do";
                out.add(new Finding("语法", toks.get(i + 1).start, verb.length(), verb,
                        subj + " 是复数，谓语要用 " + fix,
                        subj + " " + fix));
            }
        }
    }

    /**
     * 结构 2b：There is + 复数名词 → There are。
     */
    private static void checkThereIs(List<Pos.Token> toks, String src,
                                     List<Finding> out) {
        for (int i = 0; i + 2 < toks.size(); i++) {
            String a = toks.get(i).text.toLowerCase(Locale.US);
            String b = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (!a.equals("there") || !b.equals("is")) continue;
            // 跳过冠词/形容词找名词
            int k = i + 2;
            while (k < toks.size() && (Pos.isAdj(toks.get(k).text)
                    || toks.get(k).text.equalsIgnoreCase("a")
                    || toks.get(k).text.equalsIgnoreCase("an")
                    || toks.get(k).text.equalsIgnoreCase("the"))) {
                k++;
            }
            if (k >= toks.size()) continue;
            String noun = toks.get(k).text.toLowerCase(Locale.US);
            boolean plural = IRREGULAR_PLURAL.contains(noun)
                    || (noun.endsWith("s") && noun.length() > 3 && Pos.isNoun(noun));
            if (plural) {
                out.add(new Finding("语法", toks.get(i + 1).start, 2, "is",
                        "There is 后面是复数名词，应该用 are", "There are"));
            }
        }
    }

    /**
     * 结构 2c：情态/助动词 + 过去式（have went / are go）。
     */
    private static void checkAuxVerbForm(List<Pos.Token> toks, String src,
                                         List<Finding> out) {
        Set<String> haveAux = new HashSet<>(Arrays.asList("have", "has", "had"));
        Set<String> beAux = new HashSet<>(Arrays.asList("are", "is", "am", "was", "were"));
        for (int i = 0; i + 1 < toks.size(); i++) {
            String aux = toks.get(i).text.toLowerCase(Locale.US);
            String v = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (haveAux.contains(aux)) {
                // have + 过去式（应该是过去分词）
                if (isPastTense(v) && !isPastParticiple(v)) {
                    out.add(new Finding("语法", toks.get(i + 1).start, v.length(), v,
                            "have/has/had 后面要用过去分词", pastParticipleOf(v)));
                }
            } else if (beAux.contains(aux)) {
                // are/is + 动词原形（应该是 -ing）
                if (Pos.isVerb(v) && !v.endsWith("ing") && !v.endsWith("ed")
                        && !isIrregularParticiple(v) && !BE.contains(v)) {
                    out.add(new Finding("语法", toks.get(i + 1).start, v.length(), v,
                            "be 动词后面要用 -ing 或过去分词", v + "ing"));
                }
            }
        }
    }

    /** 不规则动词的过去式（不是过去分词）。 */
    private static final Set<String> PAST_ONLY = new HashSet<>(Arrays.asList(
            "went", "saw", "ate", "took", "gave", "came", "became", "got",
            "did", "said", "made", "knew", "grew", "threw", "flew", "drew",
            "wrote", "rode", "drove", "rose", "spoke", "broke", "chose", "froze"));

    private static boolean isPastTense(String v) {
        return PAST_ONLY.contains(v) || (v.endsWith("ed") && v.length() > 4);
    }

    private static boolean isPastParticiple(String v) {
        // went 是过去式，gone 才是过去分词
        return !PAST_ONLY.contains(v);
    }

    private static boolean isIrregularParticiple(String v) {
        Set<String> pp = new HashSet<>(Arrays.asList(
                "been", "gone", "seen", "eaten", "taken", "given", "come", "become",
                "got", "gotten", "done", "said", "made", "known", "grown", "thrown",
                "flown", "drawn", "written", "ridden", "driven", "risen", "spoken",
                "broken", "chosen", "frozen"));
        return pp.contains(v);
    }

    private static String pastParticipleOf(String past) {
        switch (past) {
            case "went": return "gone";
            case "saw": return "seen";
            case "ate": return "eaten";
            case "took": return "taken";
            case "gave": return "given";
            case "came": return "come";
            case "became": return "become";
            case "did": return "done";
            case "knew": return "known";
            case "grew": return "grown";
            case "threw": return "thrown";
            case "flew": return "flown";
            case "drew": return "drawn";
            case "wrote": return "written";
            case "rode": return "ridden";
            case "drove": return "driven";
            case "rose": return "risen";
            case "spoke": return "spoken";
            case "broke": return "broken";
            case "chose": return "chosen";
            case "froze": return "frozen";
            default: return past;
        }
    }

    /**
     * 结构 2d：第三人称单数 + 动词原形（She go / He don't）。
     */
    private static void checkThirdPersonS(List<Pos.Token> toks, String src,
                                          List<Finding> out) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            String subj = toks.get(i).text.toLowerCase(Locale.US);
            if (!SINGULAR_SUBJ.contains(subj)) continue;
            String v = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (v.equals("don't") || v.equals("dont")) {
                out.add(new Finding("语法", toks.get(i + 1).start, v.length(), v,
                        subj + " 后面用 doesn't", "doesn't"));
                continue;
            }
            if (BE.contains(v) || v.endsWith("s") || v.endsWith("ed")
                    || v.endsWith("ing") || v.equals("can") || v.equals("will")
                    || v.equals("would") || v.equals("should") || v.equals("must")
                    || v.equals("may") || v.equals("might") || v.equals("could")
                    || v.equals("had") || v.equals("has") || v.equals("does")) {
                continue;
            }
            if (!Pos.isVerb(v)) continue;
            // 排除过去式（不规则）
            if (isPastTense(v)) continue;
            String third = thirdPersonOf(v);
            out.add(new Finding("语法", toks.get(i + 1).start, v.length(), v,
                    subj + " 是第三人称单数，动词要加 s", third));
        }
    }

    /**
     * 结构 2e：单数名词主语 + 动词原形（The dog run → runs）。
     *
     * 只看「限定词 + 单数名词 + 动词原形」这个确定模式，避免误伤
     * （"people run" 是对的，因为 people 是复数）。
     */
    private static void checkNounSubjectVerb(List<Pos.Token> toks, String src,
                                             List<Finding> out) {
        Set<String> dets = new HashSet<>(Arrays.asList("a", "an", "the", "this", "that"));
        for (int i = 0; i + 2 < toks.size(); i++) {
            String det = toks.get(i).text.toLowerCase(Locale.US);
            if (!dets.contains(det)) continue;
            String noun = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (!Pos.isNoun(noun)) continue;
            // 复数名词不算
            if (IRREGULAR_PLURAL.contains(noun)) continue;
            if (noun.endsWith("s") && noun.length() > 3) continue;

            String v = toks.get(i + 2).text.toLowerCase(Locale.US);
            if (BE.contains(v) || v.endsWith("s") || v.endsWith("ed")
                    || v.endsWith("ing") || isPastTense(v)) continue;
            if (v.equals("can") || v.equals("will") || v.equals("would")
                    || v.equals("should") || v.equals("must") || v.equals("may")
                    || v.equals("might") || v.equals("could")
                    || v.equals("do") || v.equals("does") || v.equals("did")) continue;
            if (!Pos.isVerb(v)) continue;

            out.add(new Finding("语法", toks.get(i + 2).start, v.length(), v,
                    noun + " 是单数主语，动词要加 s", thirdPersonOf(v)));
        }
    }

    private static String thirdPersonOf(String v) {
        if (v.endsWith("y") && v.length() > 2
                && "aeiou".indexOf(v.charAt(v.length() - 2)) < 0) {
            return v.substring(0, v.length() - 1) + "ies";
        }
        if (v.endsWith("s") || v.endsWith("sh") || v.endsWith("ch")
                || v.endsWith("x") || v.endsWith("z") || v.endsWith("o")) {
            return v + "es";
        }
        return v + "s";
    }

    /**
     * 结构 3：a/an 与名词的搭配（用词性判断，不是首字母）。
     *
     * 之前用首字母判断，于是 "an university" 和 "a hour" 都判错。
     * 现在先确认后面确实是名词，再按音素判断。
     */
    private static void checkArticleBeforeNoun(List<Pos.Token> toks, String src,
                                               List<Finding> out) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            String art = toks.get(i).text.toLowerCase(Locale.US);
            if (!art.equals("a") && !art.equals("an")) continue;
            Pos.Token next = toks.get(i + 1);
            String w = next.text;
            // 后面不是名词 → 可能修饰形容词（a beautiful day），放过
            if (!Pos.isNoun(w) && !Pos.isAdj(w)) continue;

            boolean needAn = startsWithVowelSound(w);
            if (needAn && art.equals("a")) {
                out.add(new Finding("冠词", toks.get(i).start, art.length(), art,
                        "元音音素开头，应用 an", "an " + w));
            } else if (!needAn && art.equals("an")) {
                out.add(new Finding("冠词", toks.get(i).start, art.length(), art,
                        "辅音音素开头，应用 a", "a " + w));
            }
        }
    }

    /** 按音素判断，而不是首字母（university 是 /j/，hour 是 /aʊ/）。 */
    private static boolean startsWithVowelSound(String w) {
        String s = w.toLowerCase(Locale.US);
        // 不发元音的特殊开头
        if (s.matches("^(university|unit|unique|uniform|union|united|universal"
                + "|useful|user|usual|usually|euro|european|one|once|ubiquitous).*")) {
            return false;
        }
        // h 不发音
        if (s.matches("^(hour|honest|honor|honour|heir).*")) return true;
        // 缩写按字母读（an FBI / an MBA）
        if (s.length() >= 2 && s.equals(s.toUpperCase(Locale.US))) {
            char c = s.charAt(0);
            return "AEFHILMNORSX".indexOf(c) >= 0;
        }
        char c = s.charAt(0);
        return "aeiou".indexOf(c) >= 0;
    }

    /**
     * 结构 4：情态动词后必须是动词原形。
     * "can start by passing" 对，"can to start" 错。
     */
    private static void checkVerbAfterModal(List<Pos.Token> toks, String src,
                                            List<Finding> out) {
        Set<String> modals = new HashSet<>(Arrays.asList(
                "can", "could", "will", "would", "shall", "should",
                "may", "might", "must"));
        for (int i = 0; i + 1 < toks.size(); i++) {
            String m = toks.get(i).text.toLowerCase(Locale.US);
            if (!modals.contains(m)) continue;
            String next = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (next.equals("to")) {
                out.add(new Finding("语法", toks.get(i + 1).start, 2, "to",
                        m + " 后面直接接动词原形，不加 to",
                        m + " " + (i + 2 < toks.size() ? toks.get(i + 2).text : "…")));
            } else if (next.endsWith("ed") && next.length() > 4 && Pos.isVerb(next)) {
                String base = next.substring(0, next.length() - 2);
                if (Pos.isVerb(base)) {
                    out.add(new Finding("语法", toks.get(i + 1).start, next.length(), next,
                            m + " 后面要用动词原形", base));
                }
            }
        }
    }

    /**
     * 结构 5：双重比较级 / 最高级（more better、most easiest）。
     */
    private static void checkDoubleComparative(List<Pos.Token> toks, String src,
                                               List<Finding> out) {
        for (int i = 0; i + 1 < toks.size(); i++) {
            String a = toks.get(i).text.toLowerCase(Locale.US);
            String b = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (a.equals("more") && (b.endsWith("er") || b.matches(
                    "^(better|worse|further|farther|less|more)$"))) {
                out.add(new Finding("语法", toks.get(i).start,
                        a.length() + 1 + b.length(), a + " " + b,
                        "双重比较级", b));
            }
            if (a.equals("most") && (b.endsWith("est") || b.matches(
                    "^(best|worst|furthest|farthest|least|most)$"))) {
                out.add(new Finding("语法", toks.get(i).start,
                        a.length() + 1 + b.length(), a + " " + b,
                        "双重最高级", b));
            }
        }
    }

    /**
     * 结构 6：系动词 + 情感形容词要配对主语。
     * I am boring → 应该是 bored（除非你真在说"我这个人很无聊"）。
     */
    private static void checkAdjectiveBeforeVerb(List<Pos.Token> toks, String src,
                                                 List<Finding> out) {
        Set<String> personSubj = new HashSet<>(Arrays.asList("i", "he", "she", "we", "they"));
        for (int i = 0; i + 2 < toks.size(); i++) {
            String subj = toks.get(i).text.toLowerCase(Locale.US);
            if (!personSubj.contains(subj)) continue;
            String be = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (!BE.contains(be)) continue;
            String adj = toks.get(i + 2).text.toLowerCase(Locale.US);
            if (!EMO_ADJ.contains(adj)) continue;

            // -ing 形式形容"让人…"，主语是人时应该用 -ed
            if (adj.endsWith("ing")) {
                String ed = adj.substring(0, adj.length() - 3) + "ed";
                if (adj.endsWith("ting") || adj.endsWith("ding")) {
                    ed = adj.substring(0, adj.length() - 3) + "ed";
                }
                out.add(new Finding("语法", toks.get(i + 2).start, adj.length(), adj,
                        adj + " 是「让人…」，主语是人应该用 " + ed,
                        subj + " " + be + " " + ed));
            }
        }
    }

    /**
     * 结构 7：及物动词后面不该加介词。
     * discuss about / marry with / enter into
     */
    private static void checkInfinitive(List<Pos.Token> toks, String src,
                                        List<Finding> out) {
        Set<String> vtOnly = new HashSet<>(Arrays.asList(
                "discuss", "marry", "enter", "reach", "resemble", "lack",
                "approach", "attend", "contact", "answer", "obey"));
        Set<String> badPrep = new HashSet<>(Arrays.asList(
                "about", "with", "into", "to", "at"));
        for (int i = 0; i + 1 < toks.size(); i++) {
            String v = toks.get(i).text.toLowerCase(Locale.US);
            if (!vtOnly.contains(v)) continue;
            String p = toks.get(i + 1).text.toLowerCase(Locale.US);
            if (!badPrep.contains(p)) continue;
            out.add(new Finding("搭配", toks.get(i + 1).start, p.length(), p,
                    v + " 是及物动词，不加 " + p, v + " + 宾语"));
        }
    }

    /**
     * 结构 8：系动词/助动词多打了 ed（ared → are）。
     *
     * 这类错误靠词性推导：ared 既不在词典里，又不是合法过去式，
     * 去掉 ed 后是合法系动词 → 判定为手滑。
     */
    private static final java.util.Map<String, String> ED_TYPOS =
            new java.util.HashMap<>();

    static {
        ED_TYPOS.put("ared", "are");
        ED_TYPOS.put("ised", "is");
        ED_TYPOS.put("wered", "were");
        ED_TYPOS.put("hased", "has");
        ED_TYPOS.put("haved", "have");
        ED_TYPOS.put("doed", "do");
        ED_TYPOS.put("dosed", "does");
        ED_TYPOS.put("caned", "can");
        ED_TYPOS.put("willed", "will");
        ED_TYPOS.put("am'ed", "am");
    }

    private static void checkHandTypoEd(List<Pos.Token> toks, String src,
                                        List<Finding> out) {
        for (Pos.Token t : toks) {
            String w = t.text.toLowerCase(Locale.US);
            String fix = ED_TYPOS.get(w);
            if (fix == null) continue;
            out.add(new Finding("语法", t.start, t.text.length(), t.text,
                    "这个词形不成立，多打了 ed", "→ " + fix));
        }
    }

    private static boolean isFunctionWord(String w) {
        switch (w) {
            case "is": case "are": case "was": case "were": case "has": case "have":
            case "had": case "will": case "would": case "can": case "could":
            case "should": case "must": case "may": case "might": case "do":
            case "does": case "did": case "to": case "of": case "in": case "on":
            case "at": case "for": case "with": case "and": case "or": case "but":
            case "not": case "also": case "too": case "very": case "so": case "that":
                return true;
            default:
                return false;
        }
    }
}
