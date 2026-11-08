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
        List<String> out = new ArrayList<>();
        if (text == null) return out;
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
        Result r = new Result();
        List<String> sents = splitSentences(text);
        if (sents.isEmpty()) {
            r.error = "文章是空的";
            return r;
        }
        Translate.load(com.englishstudy.app.App.ctx());
        for (String s : sents) {
            Line line = new Line(s);
            Translate.Result tr = Translate.translate(s);
            if (tr.hasTranslation()) {
                line.zh = tr.translation;
                line.fromLibrary = true;
                r.libraryHits++;
            }
            r.lines.add(line);
        }
        return r;
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

        String prompt =
                "下面是一篇英语文章。请做两件事，用严格 JSON 返回，不要任何解释文字：\n"
                        + "1. 逐句翻译成自然的中文（不要逐词直译）\n"
                        + "2. 指出哪些句子表达不地道/有语法问题，给出改写\n\n"
                        + "返回格式：\n"
                        + "{\"lines\":[{\"en\":\"原句\",\"zh\":\"译文\",\"issue\":\"问题说明或空\"}],"
                        + "\"summary\":\"整篇语言质量总评（一句话）\"}\n\n"
                        + "文章：\n" + src;

        String reply;
        try {
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
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject it = arr.getJSONObject(i);
                    String en = it.optString("en", "");
                    String zh = it.optString("zh", "");
                    String issue = it.optString("issue", "");
                    // 按原文顺序对位（模型可能改标点）
                    for (Line l : r.lines) {
                        if (sameSentence(l.en, en)) {
                            if (l.zh == null || l.zh.isEmpty()) l.zh = zh;
                            l.issue = issue;
                            break;
                        }
                    }
                }
                int filled = 0;
                for (Line l : r.lines) {
                    if (l.zh != null && !l.zh.isEmpty() && !l.fromLibrary) filled++;
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
