package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 学伴 Agent：OpenAI 兼容接口 + 本地工具调用。
 *
 * 设计要点：
 *   - 不内置任何 API Key，用户自己在「对话 → 设置」里填 baseUrl / key / model
 *   - 模型可以调用本地工具（查词、翻译、加入生词本、取待复习词），
 *     工具全部在手机上执行，不额外联网
 *   - 没配置 key 时走 {@link OfflineTutor}，完全离线
 */
public final class Agent {

    private static final String TAG = "Agent";

    public static final class Msg {
        public final String role;      // system / user / assistant / tool
        public final String content;
        /** 仅 tool 角色使用：对应哪一次 tool_call。 */
        public final String toolCallId;
        /** 仅 assistant 角色使用：原始的 tool_calls JSON（协议要求回传）。 */
        public final JSONArray toolCalls;

        public Msg(String role, String content) {
            this(role, content, null, null);
        }

        public Msg(String role, String content, String toolCallId, JSONArray toolCalls) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
            this.toolCalls = toolCalls;
        }
    }

    public interface Listener {
        void onDone(String text);
        void onError(String message);
    }

    private static final List<Msg> HISTORY = new ArrayList<>();
    private static final int MAX_HISTORY = 24;

    private Agent() {
    }

    // ---------------------------------------------------------------- 配置

    public static boolean configured() {
        return !Store.get("ai.key", "").isEmpty()
                && !Store.get("ai.base", "").isEmpty();
    }

    public static String baseUrl() {
        String b = Store.get("ai.base", "");
        if (b == null || b.isEmpty()) return "";
        String t = b.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        if (!t.endsWith("/v1") && !t.contains("/api/")) t = t + "/v1";
        return t;
    }

    public static String modelName() {
        String m = Store.get("ai.model", "");
        return m == null || m.isEmpty() ? "gpt-4o-mini" : m;
    }

    public static List<Msg> history() {
        return HISTORY;
    }

    public static synchronized void reset() {
        HISTORY.clear();
    }

    private static synchronized void remember(Msg m) {
        HISTORY.add(m);
        while (HISTORY.size() > MAX_HISTORY) HISTORY.remove(0);
    }

    private static synchronized void remember(String role, String content) {
        remember(new Msg(role, content));
    }

    /** 历史里如果有没配齐 tool_call_id 的 tool 消息，接口会直接 400。 */
    private static void pruneDanglingTools() {
        List<Msg> drop = new ArrayList<>();
        for (Msg m : HISTORY) {
            if ("tool".equals(m.role) && (m.toolCallId == null || m.toolCallId.isEmpty())) {
                drop.add(m);
            }
        }
        HISTORY.removeAll(drop);
    }

    // ---------------------------------------------------------------- 发送

    /** 同步发送（调用方负责放到后台线程）。返回最终文本。 */
    public static String chat(String userText) throws Exception {
        if (!configured()) return OfflineTutor.reply(userText);

        remember("user", userText);
        String answer = runLoop();
        remember("assistant", answer);
        return answer;
    }

    private static String runLoop() throws Exception {
        String last = "";
        // 最多 5 轮：模型可能连续调多个工具
        for (int round = 0; round < 5; round++) {
            JSONObject body = new JSONObject();
            body.put("model", modelName());
            body.put("temperature", 0.6);

            pruneDanglingTools();
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
            for (Msg m : HISTORY) {
                JSONObject o = new JSONObject().put("role", m.role).put("content", m.content);
                if ("tool".equals(m.role) && m.toolCallId != null) {
                    o.put("tool_call_id", m.toolCallId);
                }
                if ("assistant".equals(m.role) && m.toolCalls != null && m.toolCalls.length() > 0) {
                    o.put("tool_calls", m.toolCalls);
                }
                msgs.put(o);
            }
            body.put("messages", msgs);
            body.put("tools", toolsJson());
            body.put("tool_choice", "auto");

            JSONObject resp = post(body.toString());
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new Exception("接口没有返回内容");
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) throw new Exception("接口返回格式不对");

            String content = msg.optString("content", "");
            JSONArray calls = msg.optJSONArray("tool_calls");

            if (calls == null || calls.length() == 0) {
                return content;
            }

            // assistant 的 tool_calls 必须原样带回历史，每条 tool 结果必须带 tool_call_id
            remember(new Msg("assistant", content, null, calls));
            last = content;
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                String id = call.optString("id", "call_" + i);
                JSONObject fn = call.optJSONObject("function");
                String name = fn == null ? "" : fn.optString("name", "");
                String args = fn == null ? "{}" : fn.optString("arguments", "{}");
                String result = runTool(name, args);
                diag("tool " + name + "(" + trim(args, 60) + ") -> " + trim(result, 100));
                remember(new Msg("tool", result, id, null));
            }
        }
        return last.isEmpty() ? "（工具调用次数过多，先停一下）" : last;
    }

    private static JSONObject post(String payload) throws Exception {
        String url = baseUrl() + "/chat/completions";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + Store.get("ai.key", ""));

        OutputStream out = conn.getOutputStream();
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.close();

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        conn.disconnect();

        android.util.Log.i(TAG, "POST " + url + " -> " + code + " body=" + trim(sb.toString(), 200));
        diag("POST " + url + " -> " + code + " " + trim(sb.toString(), 200));

        if (code >= 400) {
            throw new Exception("HTTP " + code + "：" + trim(sb.toString(), 160));
        }
        return new JSONObject(sb.toString());
    }

    /** 调试日志落盘，方便无界面排查。 */
    private static void diag(String line) {
        try {
            android.content.Context c = com.englishstudy.app.App.ctx();
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "agent-diag.txt");
            if (f.length() > 60000) f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            w.println(System.currentTimeMillis() % 1000000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ---------------------------------------------------------------- 工具

    private static final String SYSTEM_PROMPT =
            "你是「学伴」，一个英语学习助手，服务对象是中文母语的学习者。\n"
                    + "规则：\n"
                    + "1. 用中文解释，例句给英文并配中文翻译。\n"
                    + "2. 解释单词前先调用 lookup_word，不要凭记忆编造音标和释义。\n"
                    + "3. 用户要翻译句子时调用 translate_sentence。\n"
                    + "4. 用户说想记某个词时调用 add_to_vocab。\n"
                    + "5. 用户想复习时调用 due_words 拿真实待复习词，不要虚构。\n"
                    + "6. 回答简短，控制在 120 字以内，除非用户要求展开。\n"
                    + "7. 用户写英文句子时，先肯定，再指出 1-2 处可改进，给出改后的句子。";

    private static JSONArray toolsJson() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(fn("lookup_word", "查英语单词的音标、中文释义、词形变化和例句",
                prop("word", "string", "要查的单词，原形或变形都行")));
        arr.put(fn("translate_sentence", "把英文句子翻译成中文（本地句库，查不到给逐词对照）",
                prop("text", "string", "要翻译的英文句子")));
        arr.put(fn("add_to_vocab", "把单词加入生词本，纳入间隔重复复习",
                prop("word", "string", "要加入生词本的单词")));
        arr.put(fn("due_words", "取当前到期该复习的单词列表",
                prop("limit", "integer", "最多返回几个，默认 10")));
        return arr;
    }

    private static JSONObject prop(String name, String type, String desc) throws Exception {
        return new JSONObject().put(name, new JSONObject().put("type", type).put("description", desc));
    }

    private static JSONObject fn(String name, String desc, JSONObject props) throws Exception {
        JSONArray required = new JSONArray();
        java.util.Iterator<String> it = props.keys();
        while (it.hasNext()) required.put(it.next());
        JSONObject params = new JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", required);
        return new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", name).put("description", desc)
                        .put("parameters", params));
    }

    /** 执行工具，返回给模型看的纯文本结果。 */
    private static String runTool(String name, String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
            switch (name) {
                case "lookup_word": {
                    String w = a.optString("word", "").trim();
                    Dict.Entry e = Dict.lookup(w);
                    if (e == null) return "词典里没有查到「" + w + "」。";
                    StringBuilder sb = new StringBuilder();
                    sb.append(e.word);
                    String ipa = e.displayIpa();
                    if (!ipa.isEmpty()) sb.append(" /").append(ipa).append("/");
                    sb.append("\n释义：").append(e.def.replace("\n", "；"));
                    List<Sentences.Item> ex = Sentences.items(e.word);
                    if (!ex.isEmpty()) {
                        sb.append("\n例句：");
                        int n = Math.min(3, ex.size());
                        for (int i = 0; i < n; i++) {
                            Sentences.Item it = ex.get(i);
                            sb.append("\n- ").append(it.en);
                            if (it.hasZh()) sb.append("（").append(it.zh).append("）");
                        }
                    }
                    return sb.toString();
                }
                case "translate_sentence": {
                    String t = a.optString("text", "").trim();
                    Translate.Result r = Translate.translate(t);
                    if (r.hasTranslation()) return r.translation;
                    if (r.exact) return r.translation;
                    StringBuilder sb = new StringBuilder("句库里没有现成译文，逐词对照：");
                    for (Translate.Word w : r.words) {
                        sb.append("\n").append(w.text).append(" — ").append(
                                w.def.isEmpty() ? "（未收录）" : w.def);
                    }
                    return sb.toString();
                }
                case "add_to_vocab": {
                    String w = a.optString("word", "").trim().toLowerCase();
                    if (w.isEmpty()) return "缺少单词。";
                    if (Vocab.get(w) != null) return "「" + w + "」已经在生词本里了。";
                    Vocab.getOrCreate(w);
                    Vocab.flush();
                    return "已把「" + w + "」加入生词本。";
                }
                case "due_words": {
                    int limit = a.optInt("limit", 10);
                    List<com.englishstudy.app.engine.Card> due = Vocab.due(System.currentTimeMillis(),
                            Math.max(1, Math.min(50, limit)));
                    if (due.isEmpty()) return "现在没有到期的词。";
                    StringBuilder sb = new StringBuilder("今天到期的词：");
                    for (com.englishstudy.app.engine.Card c : due) sb.append("\n- ").append(c.w);
                    return sb.toString();
                }
                default:
                    return "没有这个工具：" + name;
            }
        } catch (Throwable e) {
            Log.w(TAG, "tool failed: " + name, e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    /** 供界面展示的"我有哪些能力"。 */
    public static String[] capabilities() {
        return new String[]{
                "查词", "翻译", "改作文", "加入生词本", "取待复习词"
        };
    }

    public static void init(Context c) {
        // 目前无需预热；保留入口方便后面加本地模型
    }
}
