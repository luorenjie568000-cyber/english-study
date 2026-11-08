package com.englishstudy.app.engine;

import com.englishstudy.app.data.Agent;
import com.englishstudy.app.data.Translate;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章处理：全文翻译 + 语言合理性二审。
 *
 * 两条路：
 *   1. 有本地句库 → 逐句查（快、准、零成本）
 *   2. 查不到 → 走大模型（本地 Qwen 或用户配置的云端）
 *
 * 「二审」的设计动机（用户要求）：
 *   导入的文章可能是机器翻的、抄的、或者自己写的，语言表达未必地道。
 *   所以除了翻译，还要让模型检查「这段英文是不是人话」——
 *   指出不自然的地方并给出改法。这比单纯翻译更有学习价值。
 */
public final class ArticleTool {

    /** 一句的翻译结果。 */
    public static final class Line {
        public final String en;
        public String zh = "";
        public boolean fromLibrary;
        public String issue = "";      // 二审发现的问题（空 = 没问题）
        public String gloss = "";      // 非英文语言的逐词对照（模型不可用时兜底）

        Line(String en) {
            this.en = en;
        }
    }

    /** 整篇结果。 */
    public static final class Result {
        public final List<Line> lines = new ArrayList<>();
        public String summary = "";     // 二审总评
        public String error = "";
        public int libraryHits;
        public int modelHits;
        public String lang = "en";

        public boolean ok() {
            return error.isEmpty() && !lines.isEmpty();
        }

        public String fullTranslation() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                if (l.zh != null && !l.zh.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(l.zh);
                }
            }
            return sb.toString();
        }
    }

    private ArticleTool() {
    }

    /** 断句：按 . ! ? 切，保留缩写和数字里的小数点。 */
    public static List<String> splitSentences(String text) {
        return splitSentences(text, "en");
    }

    public static List<String> splitSentences(String text, String lang) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        if (!"en".equals(lang)) {
            // 中日韩：按 。！？ 切，不需要空格
            for (String s : text.split("(?<=[。！？!?])")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        String[] raw = text.split("(?<=[.!?])\\s+");
        for (String s : raw) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            // 太短的碎片（比如 "Mr." 被切开）并回上一句
            if (t.length() < 4 && !out.isEmpty()) {
                int last = out.size() - 1;
                out.set(last, out.get(last) + " " + t);
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 全文翻译（本地句库优先）。
     * 阻塞调用，放到后台线程。
     */
    public static Result translate(String text) {
        return translate(text, "en");
    }

    /**
     * 全文翻译（按语言分流）。
     *
     * 英文：本地 71,642 对句库优先 → 缺的交给模型
     * 中日韩等：本地没有句库，直接交给模型；没有模型时退回「词典逐词对照」
     */
    public static Result translate(String text, String lang) {
        Result r = new Result();
        r.lang = lang;
        List<String> sents = splitSentences(text, lang);
        if (sents.isEmpty()) {
            r.error = "文章是空的";
            return r;
        }

        boolean english = "en".equals(lang);
        if (english) Translate.load(com.englishstudy.app.App.ctx());

        for (String s : sents) {
            Line line = new Line(s);
            if (english) {
                Translate.Result tr = Translate.translate(s);
                if (tr.hasTranslation()) {
                    line.zh = tr.translation;
                    line.fromLibrary = true;
                    r.libraryHits++;
                }
            } else {
                // 非英文：先从多语言词典拼一个「逐词对照」，作为模型不可用时的兜底
                line.gloss = multiGloss(s, lang);
            }
            r.lines.add(line);
        }
        return r;
    }

    /** 用多语言词典给一句话做逐词对照（中文/日文/韩文）。 */
    private static String multiGloss(String sentence, String lang) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = sentence.length();
        while (i < n) {
            char ch = sentence.charAt(i);
            boolean cjk = (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3040 && ch <= 0x30FF)
                    || (ch >= 0xAC00 && ch <= 0xD7AF);
            if (!cjk) {
                i++;
                continue;
            }
            int[] hl = new int[1];
            com.englishstudy.app.data.MultiDict.Entry e =
                    com.englishstudy.app.data.MultiDict.longestAt(lang, sentence, i, hl);
            if (e != null && hl[0] > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(e.word).append('=');
                String tr = e.trans;
                int semi = tr.indexOf('；');
                sb.append(semi > 0 ? tr.substring(0, semi) : tr);
                i += hl[0];
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 用大模型补全译文 + 做语言合理性二审。
     *
     * @param r 已经跑过 translate() 的结果（会就地填充）
     * @param onProgress 进度回调（0-100），可为 null
     */
    public static void refineWithModel(Result r, Progress onProgress) {
        if (r == null || r.lines.isEmpty()) return;

        // 把没译出来的句子 + 全部原文一起交给模型，一次调用解决
        StringBuilder src = new StringBuilder();
        for (Line l : r.lines) src.append(l.en).append('\n');

        boolean english = "en".equals(r.lang);
        boolean chinese = "zh".equals(r.lang);
        String what = english ? "英语" : chinese ? "中文" : "外语";

        String task1;
        if (english) {
            task1 = "逐句翻译成自然的中文，填进 zh 字段（不要逐词直译）";
        } else if (chinese) {
            task1 = "把每句译成地道的英文，填进 zh 字段（这是给英语学习者的对照译文）";
        } else {
            task1 = "逐句翻译成自然的中文，填进 zh 字段（不要逐词直译）";
        }

        String prompt =
                "下面是一篇" + what + "文章。请做两件事，用严格 JSON 返回，不要任何解释文字：\n"
                        + "1. " + task1 + "\n"
                        + "2. 用中文指出哪些句子表达不地道/有语法问题，给出改写\n\n"
                        + "返回格式：\n"
                        + "{\"lines\":[{\"en\":\"原句\",\"zh\":\"译文\",\"issue\":\"问题说明或空\"}],"
                        + "\"summary\":\"整篇语言质量总评（一句话）\"}\n\n"
                        + "文章：\n" + src;

        if (chinese) {
            prompt += "\n\n注意：原文是中文，zh 字段要填**英文译文**（不是中文），"
                    + "issue 和 summary 用中文写。";
        } else if (!english) {
            prompt += "\n\n注意：原文是" + what + "，zh 字段填中文译文，"
                    + "issue 和 summary 也用中文写。";
        }

        String reply;
        try {
            // 本地优先：4B 端侧模型能稳定吐 JSON；本地没装/失败才走云端
            reply = Agent.askRaw(prompt, 2000);
        } catch (Throwable e) {
            r.error = "模型调用失败：" + e.getMessage();
            return;
        }
        if (reply == null || reply.trim().isEmpty()) {
            r.error = "模型没有返回内容";
            return;
        }

        parse(reply, r);
        if (onProgress != null) onProgress.onProgress(100);
    }

    /** 解析模型返回的 JSON（容错：模型常带 ```json 包裹）。 */
    private static void parse(String reply, Result r) {
        String s = reply.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            r.error = "模型返回格式不对";
            return;
        }
        s = s.substring(a, b + 1);
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            org.json.JSONArray arr = o.optJSONArray("lines");
            if (arr != null) {
                // 优先按索引对位：模型是按原文顺序返回的，这比文本匹配可靠得多
                // （短句用前缀 80% 匹配会错位，比如三句中文互相串）。
                boolean indexAligned = arr.length() == r.lines.size();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject it = arr.getJSONObject(i);
                    String en = it.optString("en", "");
                    String zh = it.optString("zh", "");
                    String issue = it.optString("issue", "");

                    Line target = null;
                    if (indexAligned) {
                        target = r.lines.get(i);
                    } else {
                        for (Line l : r.lines) {
                            if (sameSentence(l.en, en)) {
                                target = l;
                                break;
                            }
                        }
                    }
                    if (target == null) continue;
                    if (zh != null && !zh.isEmpty()) target.zh = zh;
                    target.issue = issue;
                }
                int filled = 0;
                for (Line l : r.lines) {
                    if (l.zh != null && !l.zh.isEmpty()) filled++;
                }
                r.modelHits = filled;
            }
            r.summary = o.optString("summary", "");
        } catch (Throwable e) {
            r.error = "解析模型结果失败：" + e.getMessage();
        }
    }

    /** 两句是否指同一句（忽略标点/空白/大小写差异）。 */
    private static boolean sameSentence(String a, String b) {
        if (a == null || b == null) return false;
        String x = norm(a), y = norm(b);
        if (x.equals(y)) return true;
        // 允许模型轻微改写：前缀匹配 80%
        int n = Math.min(x.length(), y.length());
        if (n < 10) return false;
        int same = 0;
        for (int i = 0; i < n; i++) if (x.charAt(i) == y.charAt(i)) same++;
        return same > n * 0.8;
    }

    private static String norm(String s) {
        return s.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public interface Progress {
        void onProgress(int percent);
    }
}
