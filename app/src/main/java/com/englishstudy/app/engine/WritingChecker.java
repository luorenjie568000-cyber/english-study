package com.englishstudy.app.engine;

import com.englishstudy.app.data.Dict;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写作批改：纯本地规则引擎，零网络。
 *
 * 检查项：中式搭配、a/an、大小写、重复词、句长、标点、拼写（词典驱动）。
 */
public final class WritingChecker {

    public static final class Issue {
        public final String type;
        public final int offset;
        public final int length;
        public final String snippet;
        public final String message;
        public final String hint;

        Issue(String type, int offset, int length, String snippet, String message, String hint) {
            this.type = type;
            this.offset = offset;
            this.length = length;
            this.snippet = snippet;
            this.message = message;
            this.hint = hint == null ? "" : hint;
        }
    }

    public static final class Result {
        public final List<Issue> issues = new ArrayList<>();
        public int score;
        public int words;
        public int sentences;
        public int avgLen;
        public String verdict = "";
    }

    /** 中式搭配 / 常见误用。 */
    private static final String[][] CN_ENG = {
            {"very like", "不能说 very like", "really like / like … very much"},
            {"I very", "very 不能直接修饰动词", "I really …"},
            {"how to say", "中式问法", "how do you say"},
            {"open the light", "开灯不是 open", "turn on the light"},
            {"close the light", "关灯不是 close", "turn off the light"},
            {"play phone", "玩手机不是 play phone", "use my phone / play on my phone"},
            {"eat medicine", "吃药不是 eat", "take medicine"},
            {"see a doctor", "看医生用 see 正确，但更常说", "go to the doctor"},
            {"I am boring", "boring 是「让人无聊」", "I am bored"},
            {"I am interesting", "interesting 是「让人感兴趣」", "I am interested"},
            {"I am exciting", "exciting 是「让人兴奋」", "I am excited"},
            {"I am tiring", "tiring 是「让人累」", "I am tired"},
            {"discuss about", "discuss 及物，不加 about", "discuss the topic"},
            {"marry with", "marry 及物，不加 with", "marry someone"},
            {"enter into", "enter 及物", "enter the room"},
            {"according to me", "不地道", "in my opinion"},
            {"in my opinion, I think", "语义重复", "in my opinion / I think"},
            {"more and more people", "可用，但略套话", "a growing number of people"},
            {"with the development of", "套话开头，建议换", "as … develops / thanks to"},
            {"every day" + " " + "day", "重复", "every day"},
            {"although", "although 后不能再接 but", "although … , 主句（去掉 but）"},
            {"because so", "because 与 so 不能连用", "because … 或 so …"},
            {"the reason is because", "reason 后接 that", "the reason is that"},
            {"one of the", "one of the 后接复数名词", "one of the + 复数"},
            {"a lot of", "口语化，正式写作可换", "many / much / a great deal of"},
    };

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");
    private static final Pattern VOWEL_START = Pattern.compile("^[aeiou]", Pattern.CASE_INSENSITIVE);

    private WritingChecker() {
    }

    public static Result check(String text) {
        Result r = new Result();
        String src = text == null ? "" : text;
        if (src.trim().isEmpty()) {
            r.verdict = "先写点东西吧";
            return r;
        }

        // 1) 中式搭配
        for (String[] rule : CN_ENG) {
            String key = rule[0];
            String msg = rule[1];
            String fix = rule[2];
            Pattern p = Pattern.compile(Pattern.quote(key), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(src);
            while (m.find()) {
                // although / one of the 这类只在特定上下文报
                if ("although".equals(key)) {
                    String after = src.substring(m.end());
                    if (!after.toLowerCase(Locale.US).contains(" but ")) continue;
                }
                if ("one of the".equals(key)) {
                    String after = src.substring(m.end()).trim();
                    if (after.isEmpty()) continue;
                    String first = after.split("\\s+")[0];
                    if (first.endsWith("s")) continue;   // 已是复数
                }
                r.issues.add(new Issue("搭配", m.start(), m.end() - m.start(),
                        src.substring(m.start(), m.end()), msg, fix));
            }
        }

        // 2) a / an
        Matcher ma = Pattern.compile("\\b(a|an)\\s+([A-Za-z][A-Za-z'-]*)").matcher(src);
        while (ma.find()) {
            String art = ma.group(1).toLowerCase(Locale.US);
            String word = ma.group(2);
            boolean vowel = VOWEL_START.matcher(word).find();
            boolean silentH = word.matches("(?i)^(hour|honest|honor|honour|heir).*");
            boolean uni = word.matches("(?i)^(university|unit|unique|uniform|union|useful|user|one|euro).*");
            boolean needAn = (vowel && !uni) || silentH;
            if (needAn && art.equals("a")) {
                r.issues.add(new Issue("冠词", ma.start(), ma.group().length(), ma.group(),
                        "元音音素开头，应用 an", "an " + word));
            } else if (!needAn && art.equals("an")) {
                r.issues.add(new Issue("冠词", ma.start(), ma.group().length(), ma.group(),
                        "辅音音素开头，应用 a", "a " + word));
            }
        }

        // 3) 小写 i
        Matcher mi = Pattern.compile("(^|[.!?]\\s+|\\s)i(\\s|'|$|[,.!?])").matcher(src);
        while (mi.find()) {
            int at = mi.start() + mi.group(1).length();
            r.issues.add(new Issue("大小写", at, 1, "i", "人称代词 I 永远大写", "I"));
        }

        // 4) 句首大写
        Matcher ms = Pattern.compile("(^|[.!?]\\s+)([a-z][A-Za-z'-]*)").matcher(src);
        while (ms.find()) {
            int at = ms.start() + ms.group(1).length();
            String w = ms.group(2);
            r.issues.add(new Issue("大小写", at, w.length(), w,
                    "句首字母应大写", Character.toUpperCase(w.charAt(0)) + w.substring(1)));
        }

        // 5) 重复词
        Matcher md = Pattern.compile("\\b([A-Za-z]+)\\s+\\1\\b", Pattern.CASE_INSENSITIVE).matcher(src);
        while (md.find()) {
            r.issues.add(new Issue("重复", md.start(), md.group().length(), md.group(),
                    "同一个词连续出现两次", md.group(1)));
        }

        // 6) 标点：空格位置
        Matcher mp = Pattern.compile("\\s+[,.;:!?]").matcher(src);
        while (mp.find()) {
            r.issues.add(new Issue("标点", mp.start(), mp.group().length(), mp.group().trim(),
                    "标点前不应有空格", "把空格删掉"));
        }

        // 7) 拼写：词典驱动
        if (Dict.ready()) {
            Matcher mw = WORD.matcher(src);
            while (mw.find()) {
                String w = mw.group();
                if (w.length() < 3) continue;
                if (Character.isUpperCase(w.charAt(0))) continue;  // 专名跳过
                // lookup 内部已含 lemma 表 + 规则还原，能覆盖 was/went/gave 这类变形
                if (Dict.lookup(w) != null) continue;
                // 全大写缩写跳过
                if (w.equals(w.toUpperCase(Locale.US))) continue;
                r.issues.add(new Issue("拼写", mw.start(), w.length(), w,
                        "词典里查不到这个词", suggest(w)));
            }
        }

        r.issues.addAll(0, new ArrayList<Issue>());
        List<Issue> deduped = dedupe(r.issues);
        r.issues.clear();
        r.issues.addAll(deduped);

        // 统计
        countStats(r, src);
        score(r);
        return r;
    }

    /** 同类型且区间重叠的只留跨度最长的一条，避免同一处重复扣分。 */
    private static List<Issue> dedupe(List<Issue> in) {
        List<Issue> out = new ArrayList<>();
        for (Issue cand : in) {
            boolean dup = false;
            for (int i = 0; i < out.size(); i++) {
                Issue keep = out.get(i);
                if (!keep.type.equals(cand.type)) continue;
                int aS = keep.offset, aE = keep.offset + keep.length;
                int bS = cand.offset, bE = cand.offset + cand.length;
                if (bS < aE && aS < bE) {
                    dup = true;
                    if (cand.length > keep.length) out.set(i, cand);
                    break;
                }
            }
            if (!dup) out.add(cand);
        }
        return out;
    }

    private static void countStats(Result r, String src) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = WORD.matcher(src);
        while (m.find()) seen.add(m.group().toLowerCase(Locale.US));
        r.words = seen.size();

        String[] sents = src.split("[.!?]+");
        int n = 0, total = 0;
        for (String s : sents) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            n++;
            total += t.split("\\s+").length;
        }
        r.sentences = Math.max(1, n);
        r.avgLen = total / r.sentences;
    }

    private static void score(Result r) {
        int s = 100;
        int[] weight = {0, 0, 0};
        for (Issue it : r.issues) {
            switch (it.type) {
                case "拼写": s -= 8; break;
                case "搭配": s -= 6; break;
                case "冠词": s -= 4; break;
                case "大小写": s -= 2; break;
                case "标点": s -= 2; break;
                case "重复": s -= 3; break;
                default: s -= 2;
            }
        }
        if (r.words < 20) s -= 5;
        if (r.avgLen > 35) s -= 4;
        if (r.avgLen < 6) s -= 3;
        r.score = Math.max(0, Math.min(100, s));

        if (r.score >= 92) r.verdict = "很地道";
        else if (r.score >= 85) r.verdict = "表达清楚";
        else if (r.score >= 72) r.verdict = "能看懂，有几处要改";
        else if (r.score >= 60) r.verdict = "问题较多，看下面的清单";
        else r.verdict = "先从红色项改起";
    }

    /** 给拼写错误一个候选（编辑距离 1 的常见词）。 */
    private static String suggest(String word) {
        String w = word.toLowerCase(Locale.US);
        String[] tries = {
                w.replaceAll("([a-z])\\1+", "$1"),
                w.replace("ie", "ei"),
                w.replace("ei", "ie"),
                w.substring(0, w.length() - 1),
        };
        for (String t : tries) {
            if (!t.equals(w) && Dict.get(t) != null) return "可能是 " + t;
        }
        return "检查拼写";
    }
}
