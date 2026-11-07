package com.englishstudy.app.data;

import com.englishstudy.app.engine.WritingChecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线学伴：没有任何 API Key 时的兜底回答。
 *
 * 只做能确定做对的事：查词、翻译、批改、取复习词。
 * 不确定的一律说清楚，不编造。
 */
public final class OfflineTutor {

    private OfflineTutor() {
    }

    public static String reply(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return "想问点什么？查词、翻译、改句子、看今天要复习什么，都可以。";

        // 1) 改句子：里面是英文且够长
        if (looksEnglish(text) && words(text) >= 4) {
            WritingChecker.Result r = WritingChecker.check(text);
            StringBuilder sb = new StringBuilder();
            sb.append("批改：").append(r.score).append(" 分（").append(r.verdict).append("）");
            if (r.issues.isEmpty()) {
                sb.append("\n没查出明显问题。");
            } else {
                int n = Math.min(5, r.issues.size());
                for (int i = 0; i < n; i++) {
                    WritingChecker.Issue it = r.issues.get(i);
                    sb.append("\n· ").append(it.type).append("「").append(it.snippet).append("」");
                    if (!it.hint.isEmpty()) sb.append(" → ").append(it.hint);
                }
            }
            return sb.toString();
        }

        // 2) 要复习
        if (text.contains("复习") || text.contains("到期") || text.toLowerCase().contains("due")) {
            List<com.englishstudy.app.engine.Card> due =
                    Vocab.due(System.currentTimeMillis(), 10);
            if (due.isEmpty()) return "现在没有到期的词，今日已清空。";
            StringBuilder sb = new StringBuilder("今天到期 " + due.size() + " 个：");
            for (com.englishstudy.app.engine.Card c : due) {
                Dict.Entry e = Dict.lookup(c.w);
                sb.append("\n· ").append(c.w);
                if (e != null && !e.def.isEmpty()) {
                    String d = e.def.replace("\n", "；");
                    sb.append(" — ").append(d.length() > 24 ? d.substring(0, 24) + "…" : d);
                }
            }
            return sb.toString();
        }

        // 3) 翻译
        if (text.startsWith("翻译") || text.startsWith("译")) {
            String body = text.replaceFirst("^(翻译|译)[:：\\s]*", "");
            if (body.isEmpty()) return "要翻哪句？直接贴在「翻译」标签里也行。";
            Translate.Result r = Translate.translate(body);
            if (r.hasTranslation()) return r.translation;
            StringBuilder sb = new StringBuilder("句库里没有整句译文，逐词对照：");
            for (Translate.Word w : r.words) {
                sb.append("\n").append(w.text).append(" — ")
                        .append(w.def.isEmpty() ? "（未收录）" : w.def);
            }
            return sb.toString();
        }

        // 4) 查词：取最后一个像英文单词的词
        String word = lastWord(text);
        if (word != null) return explain(word);

        // 5) 单个英文词
        if (looksEnglish(text) && words(text) == 1) return explain(text);

        return "离线模式下我会：查词（`apple 怎么读`）、翻译（`翻译 xxx`）、"
                + "改句子（直接贴英文）、看复习（`今天复习什么`）。\n"
                + "想自由聊天，去「对话 → 设置」填一个 OpenAI 兼容接口。";
    }

    private static String explain(String word) {
        Dict.Entry e = Dict.lookup(word);
        if (e == null) {
            return "词典里没查到「" + word + "」，可能是拼写有误或者比较生僻。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(e.word);
        String ipa = e.displayIpa();
        if (!ipa.isEmpty()) sb.append(" /").append(ipa).append("/");
        if (!e.def.isEmpty()) {
            sb.append("\n").append(e.def.replace("\n", "\n"));
        }
        List<Sentences.Item> ex = Sentences.items(e.word);
        if (!ex.isEmpty()) {
            sb.append("\n例句：");
            int n = Math.min(3, ex.size());
            for (int i = 0; i < n; i++) {
                Sentences.Item it = ex.get(i);
                sb.append("\n· ").append(it.en);
                if (it.hasZh()) sb.append("\n  ").append(it.zh);
            }
        }
        if (Vocab.get(e.word) == null) {
            Vocab.getOrCreate(e.word);
            Vocab.flush();
            sb.append("\n（已加入生词本）");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static boolean looksEnglish(String s) {
        int letters = 0, cjk = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c) && c < 128) letters++;
            else if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++;
        }
        return letters > cjk;
    }

    private static int words(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }

    private static String lastWord(String s) {
        List<String> hits = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c) && c < 128) {
                cur.append(c);
            } else {
                if (cur.length() >= 2) hits.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() >= 2) hits.add(cur.toString());
        if (hits.isEmpty()) return null;
        // 取最长的那个，通常才是用户想问的词
        String best = hits.get(0);
        for (String h : hits) if (h.length() > best.length()) best = h;
        return best.toLowerCase(Locale.US);
    }
}
