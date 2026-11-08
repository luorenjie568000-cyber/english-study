package com.englishstudy.app.engine;

import com.englishstudy.app.data.Agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 字幕纠错：用**本地 LLM** 把 ASR 的识别错误改回来。
 *
 * 为什么这一步比换大模型更划算：
 *   tiny.en 把 "off to a Statue of The Seven" 听成 "up to a statue of the students"，
 *   单看音素是有道理的（off/up、Seven/students 音近）。但**句子层面**它说不通 ——
 *   "statue of the students" 不是英语里会出现的搭配，而 "Statue of The Seven"
 *   是有名的专有名词。这正是语言模型的强项：用上下文判据推翻声学判据。
 *
 * 换 base/small 能把错误率压下来，但压不到零；一层便宜的 LLM 审校能兜住剩下的。
 * 代价：一批 20 句约 15-25 秒（4B 端侧），比重新识别快得多。
 */
public final class AsrFix {

    private AsrFix() {
    }

    /** 纠错结果。 */
    public static final class Fixed {
        public String text;
        public boolean changed;
        public String why;

        Fixed(String t, boolean c, String w) {
            text = t;
            changed = c;
            why = w;
        }
    }

    /**
     * 批量纠错。保持条数一一对应，任何一条失败都退回原文。
     *
     * @param lines 原始识别文本
     * @return 与输入等长的结果列表
     */
    public static List<Fixed> fix(List<String> lines) {
        List<Fixed> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) return out;

        // 太短的（单词/碎片）模型也说不出什么，直接放过，省时间
        List<String> todo = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i) == null ? "" : lines.get(i).trim();
            if (t.split("\\s+").length >= 3) {
                todo.add(t);
                idx.add(i);
            }
        }
        if (todo.isEmpty()) {
            for (String t : lines) out.add(new Fixed(t == null ? "" : t, false, ""));
            return out;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < todo.size(); i++) {
            sb.append(i + 1).append(". ").append(todo.get(i)).append('\n');
        }

        String prompt =
                "下面是英语视频的自动语音识别结果，可能有同音词错误（比如 Seven 听成 students）。\n"
                        + "请根据上下文改正明显不合语法或不合逻辑的词，保持原有句意和长度。\n"
                        + "只改确实有错的地方；没错的句子原样返回。\n"
                        + "输出严格 JSON：{\"1\":\"改正后的句子\",\"2\":\"...\"}，"
                        + "键是行号，不要任何解释。\n\n"
                        + sb;

        String reply = "";
        try {
            reply = Agent.askRaw(prompt, Math.min(2400, 160 * todo.size() + 300));
        } catch (Throwable ignored) {
        }

        java.util.Map<Integer, String> got = parseNumbered(reply, todo.size());

        // 组装：改过的才标记 changed
        for (int i = 0; i < lines.size(); i++) {
            String orig = lines.get(i) == null ? "" : lines.get(i).trim();
            int at = idx.indexOf(i);
            if (at < 0) {
                out.add(new Fixed(orig, false, ""));
                continue;
            }
            String fixed = got.get(at + 1);
            if (fixed == null || fixed.trim().isEmpty()) {
                out.add(new Fixed(orig, false, ""));
                continue;
            }
            fixed = clean(fixed);
            // 变得太离谱（长度差一倍以上）就当作模型瞎编，退回原文
            if (fixed.equalsIgnoreCase(orig)
                    || fixed.split("\\s+").length > orig.split("\\s+").length * 2
                    || fixed.split("\\s+").length * 2 < orig.split("\\s+").length) {
                out.add(new Fixed(orig, false, ""));
            } else {
                out.add(new Fixed(fixed, true, "本地模型按上下文修正"));
            }
        }
        return out;
    }

    /** 解析 {"1":"..."}；失败退回按行解析「1. ...」。 */
    private static java.util.Map<Integer, String> parseNumbered(String raw, int expect) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;

        String s = raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(s.substring(a, b + 1));
                for (int i = 1; i <= expect; i++) {
                    String v = o.optString(String.valueOf(i), "");
                    if (!v.isEmpty()) out.put(i, v);
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {
            }
        }
        // 退路：按行「1. xxx」
        int k = 1;
        for (String line : s.split("\\n")) {
            String t = line.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\d+\\s*[.、)\\]]\\s*(.+)$").matcher(t);
            if (!m.find()) continue;
            if (k > expect) break;
            out.put(k++, m.group(1).replaceAll("[\"{}]", "").trim());
        }
        return out;
    }

    private static String clean(String s) {
        String t = s.trim();
        t = t.replaceAll("^[\"「『]+|[\"」』]+$", "");
        return t.trim();
    }
}
