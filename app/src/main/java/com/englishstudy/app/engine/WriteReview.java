package com.englishstudy.app.engine;

import com.englishstudy.app.data.Agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 写作批改 · 语义层（本地 LLM）。
 *
 * 为什么规则层不够：
 *   规则只能抓「有固定形态」的错误（a/an、重复词、mine team）。
 *   但 "A lot of people ared Asking about jailbreak progress" 这种，
 *   要同时看懂：ared 是手滑、Asking 不该大写、整体时态对不对 —— 这需要语言模型。
 *
 * 和规则层分工：
 *   - 规则层负责「确定性错误」，快速、零成本、不会瞎编
 *   - 语义层负责「理解性错误」，慢一点但能抓真正的问题
 *   两者合并去重后统一评分。
 */
public final class WriteReview {

    private WriteReview() {
    }

    /** 一条语义建议。 */
    public static final class Item {
        public String original = "";   // 原句里的片段（用于定位）
        public String better = "";     // 改写
        public String why = "";        // 中文说明
        public String type = "表达";    // 语法/搭配/表达/逻辑
    }

    /** 批改结果。 */
    public static final class Result {
        public final List<Item> items = new ArrayList<>();
        public String summary = "";     // 总评
        public String rewritten = "";   // 整段改写
        public String error = "";
        public boolean ok() {
            return error.isEmpty();
        }
    }

    /**
     * 调用本地模型做语义批改。阻塞，必须放后台线程。
     *
     * @param text 原文
     * @param level "gentle" 只改错 / "normal" 改错+润色 / "strict" 全面打磨
     */
    public static Result review(String text, String level) {
        Result r = new Result();
        if (text == null || text.trim().isEmpty()) {
            r.error = "没内容";
            return r;
        }

        String tone;
        String howMany;
        if ("gentle".equals(level)) {
            tone = "只改真正的错误（语法、拼写、搭配），不要改动表达风格；没问题的句子不要碰。";
            howMany = "最多 5 条";
        } else if ("strict".equals(level)) {
            tone = "全面打磨：语法、搭配、用词、句式、逻辑衔接都看，"
                    + "在保留原意的前提下给出更地道的写法。";
            howMany = "最多 12 条";
        } else {
            tone = "改掉语法和搭配错误，顺带把明显不地道的表达换成更自然的说法；"
                    + "作者的个人风格要保留。";
            howMany = "最多 8 条";
        }

        String prompt =
                "你是英语写作老师，请批改下面这段学生习作。\n"
                        + tone + "\n"
                        + "注意：技术名词、专有名词、缩写（如 jailbreak、kernel、sptm）"
                        + "不是拼写错误，不要改它们。\n"
                        + "每条问题必须指出**原文中的具体片段**，便于定位。\n"
                        + howMany + "。\n\n"
                        + "只输出一个 JSON 对象，不要任何解释文字：\n"
                        + "{\n"
                        + "  \"issues\": [\n"
                        + "    {\"original\":\"原文片段\",\"better\":\"改写\","
                        + "\"why\":\"中文说明为什么\",\"type\":\"语法|搭配|表达|逻辑\"}\n"
                        + "  ],\n"
                        + "  \"summary\":\"一句话总评（中文）\",\n"
                        + "  \"rewritten\":\"整段改写后的版本\"\n"
                        + "}\n\n"
                        + "原文：\n" + text;

        String reply;
        try {
            reply = Agent.askRaw(prompt, 1800);
        } catch (Throwable e) {
            r.error = "模型调用失败：" + e.getMessage();
            return r;
        }
        if (reply == null || reply.trim().isEmpty()) {
            r.error = "模型没有返回内容";
            return r;
        }

        parse(reply, r, text);
        return r;
    }

    private static void parse(String reply, Result r, String src) {
        String s = reply.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            r.error = "模型返回格式不对";
            return;
        }
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            r.summary = o.optString("summary", "").trim();
            r.rewritten = o.optString("rewritten", "").trim();

            JSONArray arr = o.optJSONArray("issues");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it = arr.optJSONObject(i);
                    if (it == null) continue;
                    Item one = new Item();
                    one.original = it.optString("original", "").trim();
                    one.better = it.optString("better", "").trim();
                    one.why = it.optString("why", "").trim();
                    String t = it.optString("type", "表达").trim();
                    one.type = t.isEmpty() ? "表达" : t;

                    if (one.original.isEmpty() && one.better.isEmpty()) continue;
                    // 模型瞎编的情况：原文里根本没有这个片段 → 丢掉
                    if (!one.original.isEmpty()
                            && !src.toLowerCase(java.util.Locale.US)
                            .contains(one.original.toLowerCase(java.util.Locale.US))) {
                        // 宽松一点：忽略大小写和多余空格后仍找不到就丢
                        String norm = src.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.US);
                        String probe = one.original.replaceAll("\\s+", " ")
                                .toLowerCase(java.util.Locale.US);
                        if (!norm.contains(probe)) continue;
                    }
                    r.items.add(one);
                }
            }
        } catch (Throwable e) {
            r.error = "解析失败：" + e.getMessage();
        }
    }

    /** 把改写版本按句子对齐回原文，供界面做「逐句对照」。 */
    public static List<String[]> alignSentences(String src, String rewritten) {
        List<String[]> out = new ArrayList<>();
        List<String> a = ArticleTool.splitSentences(src, "en");
        List<String> b = ArticleTool.splitSentences(rewritten, "en");
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = i < a.size() ? a.get(i).trim() : "";
            String y = i < b.size() ? b.get(i).trim() : "";
            if (x.isEmpty() && y.isEmpty()) continue;
            out.add(new String[]{x, y});
        }
        return out;
    }
}
