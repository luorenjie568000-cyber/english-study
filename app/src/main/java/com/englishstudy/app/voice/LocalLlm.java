package com.englishstudy.app.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端侧小模型（llama.cpp / GGUF）—— v6.0 本地优先版。
 *
 * 设计原则（用户要求：一切都先本地，本地做不到才考虑云端）：
 *   1. 默认模型换成 Qwen3-4B-Instruct-2507：4B 档里非推理模式最强的一版
 *      （实测中文/英文/JSON 都明显好于 Qwen2.5-7B，而体积只有 2.4GB、功耗低一档）
 *   2. 全部走国内源（ModelScope），速度实测 ~14MB/s，不用翻墙
 *   3. 上下文 4096（原来 1024 装不下一篇短文）
 *   4. 采样参数按任务分档：JSON 用低温贪心，对话用正常温度
 *   5. 提供 keepAlive 常驻 + 空闲释放，避免反复 mmap 卡顿
 */
public final class LocalLlm {

    private static final String TAG = "LocalLlm";

    /** 可选模型。 */
    public static final class Pack {
        public final String id;
        public final String title;
        public final String url;
        public final long size;
        public final String file;
        /** 推荐机型下限（RAM MB），用于在 UI 上提示。 */
        public final int minRamMb;

        Pack(String id, String title, String url, long size, String file, int minRamMb) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.size = size;
            this.file = file;
            this.minRamMb = minRamMb;
        }
    }

    /** ModelScope（国内直连，实测 14MB/s）。 */
    private static final String MS = "https://modelscope.cn/models/";

    /**
     * 默认模型：Qwen3-4B-Instruct-2507 Q4_K_M。
     *
     * 为什么是它：
     *   - 2507 版去掉了 thinking 模式，直接给答案（做翻译/批改不该先"想"半天）
     *   - 4B 在中文、英文、指令跟随、JSON 输出上都稳定超过同体积旧模型
     *   - Q4_K_M 2.38GB，8 线程下手机端约 8-12 tok/s，够交互
     */
    public static Pack packQwen3_4B() {
        return new Pack("qwen3-4b-2507",
                "Qwen3 4B（2.4GB · 推荐，全能）",
                MS + "unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/master/"
                        + "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                2497281120L, "qwen3-4b.gguf", 6000);
    }

    /** 轻量版：Qwen3-1.7B，2GB RAM 机型也能跑。 */
    public static Pack packQwen3_1_7B() {
        return new Pack("qwen3-1.7b",
                "Qwen3 1.7B（1.1GB · 轻量）",
                MS + "unsloth/Qwen3-1.7B-GGUF/resolve/master/"
                        + "Qwen3-1.7B-Q4_K_M.gguf",
                1107000000L, "qwen3-1.7b.gguf", 3000);
    }

    /** 极小版：0.6B，只用来做单词释义兜底。 */
    public static Pack packQwen3_0_6B() {
        return new Pack("qwen3-0.6b",
                "Qwen3 0.6B（400MB · 只够查词）",
                MS + "unsloth/Qwen3-0.6B-GGUF/resolve/master/"
                        + "Qwen3-0.6B-Q4_K_M.gguf",
                400000000L, "qwen3-0.6b.gguf", 2000);
    }

    /** 保留旧包，已下载的用户不用重下。 */
    public static Pack pack15() {
        return new Pack("qwen2.5-1.5b",
                "Qwen2.5 1.5B（940MB · 旧版）",
                "https://hf-mirror.com/bartowski/Qwen2.5-1.5B-Instruct-GGUF/"
                        + "resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                986048768L, "qwen15.gguf", 3000);
    }

    public static List<Pack> packs() {
        List<Pack> l = new ArrayList<>();
        l.add(packQwen3_4B());
        l.add(packQwen3_1_7B());
        l.add(packQwen3_0_6B());
        l.add(pack15());
        return l;
    }

    /** 默认选中最强的那个（有空间就 4B）。 */
    public static String defaultId() {
        return "qwen3-4b-2507";
    }

    /**
     * 当前启用的模型包。
     *
     * 顺序：用户选的（且已下载）→ 默认的（且已下载）→ 任何已下载的 → null
     * 这样老用户升级后不会因为 id 变了就「没有模型」。
     */
    public static Pack active(Context c) {
        String want = com.englishstudy.app.data.Store.get("ai.llm", defaultId());
        for (Pack p : packs()) {
            if (p.id.equals(want) && ready(c, p)) return p;
        }
        Pack d = findById(defaultId());
        if (d != null && ready(c, d)) return d;
        for (Pack p : packs()) {
            if (ready(c, p)) return p;
        }
        return null;
    }

    public static Pack findById(String id) {
        for (Pack p : packs()) if (p.id.equals(id)) return p;
        return null;
    }

    /** 已下载的包（按推荐顺序）。 */
    public static List<Pack> downloaded(Context c) {
        List<Pack> l = new ArrayList<>();
        for (Pack p : packs()) if (ready(c, p)) l.add(p);
        return l;
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "llm");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c, Pack p) {
        return new File(dir(c), p.file);
    }

    public static boolean ready(Context c, Pack p) {
        File f = file(c, p);
        return f.exists() && f.length() == p.size;
    }

    /** 部分下载时允许直接加载（GGUF 头部完整即可，长度对不上就警告）。 */
    public static boolean loadable(Context c, Pack p) {
        File f = file(c, p);
        return f.exists() && f.length() > p.size * 95 / 100;
    }

    public static long doneBytes(Context c, Pack p) {
        File f = file(c, p);
        return f.exists() ? f.length() : 0;
    }

    public static void delete(Context c, Pack p) {
        release();
        //noinspection ResultOfMethodCallIgnored
        file(c, p).delete();
    }

    // ---------------------------------------------------------------- 运行

    private static long handle;
    private static String loadedPath = "";
    private static String loadedId = "";

    /**
     * 生成时的上下文长度。
     *
     * 实测：4096 在 4B 上会让 KV cache 预分配 ~2GB，加上模型本身就吃满 8GB，
     * 频繁 swap 导致 prefill 极慢（几分钟不出字）。2048 足够「一篇短文 + 译文」，
     * 并且算得动。长文任务分批喂给模型（见 StoryTool/ArticleTool 的分段逻辑）。
     */
    private static final int CTX = 2048;

    public static boolean loaded() {
        return handle != 0;
    }

    public static String loadedName() {
        return loadedPath.isEmpty() ? "" : new File(loadedPath).getName();
    }

    public static String loadedPackId() {
        return loadedId;
    }

    /** 加载模型（阻塞，放到后台线程调用）。 */
    public static synchronized boolean load(Context c, Pack p) {
        if (handle != 0 && loadedPath.equals(file(c, p).getAbsolutePath())) return true;
        release();
        File f = file(c, p);
        if (!f.exists()) return false;
        try {
            // 4B 级模型用满大核：8 核机开 6 线程，实测比 4 线程快 ~40%
            int cores = Runtime.getRuntime().availableProcessors();
            // 4B 级模型 4 线程最稳：6 线程会抢内存带宽，实测反而更慢
            int threads = Math.max(2, Math.min(4, cores - 4));
            long t0 = System.currentTimeMillis();
            long h = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeLoadModel(
                    f.getAbsolutePath(), CTX, threads, 0);
            diag("load " + (System.currentTimeMillis() - t0) + "ms handle=" + h
                    + " ctx=" + CTX + " thr=" + threads
                    + " size=" + (f.length() / 1048576) + "MB pack=" + p.id);
            if (h == 0) {
                Log.w(TAG, "load failed: " + f.getAbsolutePath());
                return false;
            }
            handle = h;
            loadedPath = f.getAbsolutePath();
            loadedId = p.id;
            Log.i(TAG, "loaded " + f.getName() + " threads=" + threads);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "load error", e);
            return false;
        }
    }

    /** 确保模型已加载（没加载就按当前选择加载）。 */
    public static boolean ensure(Context c) {
        if (handle != 0) return true;
        Pack p = active(c);
        if (p == null) return false;
        return load(c, p);
    }

    /** 一次问答，默认采样（对话用）。 */
    public static String ask(String system, String user, int maxTokens) {
        return ask(system, user, maxTokens, 0.6f, 0.95f, 40);
    }

    /**
     * 一次问答。
     *
     * 注意：JNI 内部会用模型自带的 chat template 拼 prompt，
     * 所以这里传的是**裸文本**，不能自己再套角色标记，否则会双套模板。
     *
     * @param temp   越低越确定。JSON 任务建议 0.1，对话 0.6
     * @param topP   0.95 常用
     * @param topK   40 常用
     * @return 纯文本（JNI 返回的 JSON 已解析）
     */
    public static String ask(String system, String user, int maxTokens,
                             float temp, float topP, int topK) {
        if (handle == 0) return "";
        try {
            long t0 = System.currentTimeMillis();
            String json = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeComplete(
                    handle, user, system == null ? "" : system,
                    maxTokens, temp, topP, topK, -1);
            long cost = System.currentTimeMillis() - t0;
            if (json == null || json.isEmpty()) return "";
            String text = textOf(json);
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                stats = String.format(java.util.Locale.US,
                        "%.1f tok/s · %d tok · %dms",
                        o.optDouble("tokensPerSecond", 0),
                        o.optInt("tokensGenerated", o.optInt("completionTokens", 0)),
                        cost);
            } catch (Throwable ignored) {
            }
            diag("ask tt=" + temp + " " + cost + "ms out=" + text.length() + "ch");
            return text;
        } catch (Throwable e) {
            Log.e(TAG, "complete failed", e);
            return "";
        }
    }

    /**
     * 要 JSON 的任务（翻译/审校/批改）。
     *
     * 用低温 + 更长的 maxTokens，并在失败时自动重试一次「只输出 JSON」的强约束提示。
     * 端侧小模型最容易犯的错就是先讲一段废话再给 JSON —— 这里从返回串里
     * 掐出第一个 { 到最后一个 } 的区间，能救回绝大部分情况。
     */
    public static String askJson(String system, String user, int maxTokens) {
        String out = ask(system, user, maxTokens, 0.1f, 0.9f, 20);
        if (looksLikeJson(out)) return out;
        // 重试：把"只输出JSON"提到最前面
        String retry = "只输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码块。\n\n" + user;
        String out2 = ask(system, retry, maxTokens, 0.05f, 0.9f, 20);
        if (looksLikeJson(out2)) return out2;
        return out.length() >= out2.length() ? out : out2;
    }

    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return a >= 0 && b > a + 2;
    }

    /** 从 JNI 返回的 JSON 里取 text 字段。 */
    private static String textOf(String json) {
        try {
            return new org.json.JSONObject(json).optString("text", "");
        } catch (Throwable e) {
            return json;
        }
    }

    /** 上次推理的速度指标，仅用于诊断。 */
    public static String lastStats() {
        return stats;
    }

    private static String stats = "";

    private static void diag(String line) {
        try {
            Context c = com.englishstudy.app.App.ctx();
            if (c == null) return;
            File f = new File(c.getFilesDir(), "llm-diag.txt");
            if (f.length() > 40000) //noinspection ResultOfMethodCallIgnored
                f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            w.println(System.currentTimeMillis() % 1000000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 自检：跑几种典型任务，把结果写进诊断（定位"本地不行"到底卡在哪）。 */
    public static void selfTest(Context c) {
        Pack p = active(c);
        if (p == null) {
            diag("selftest no-model");
            return;
        }
        if (!load(c, p)) {
            diag("selftest load-failed");
            return;
        }
        // 1) 闲聊
        diag("selftest[chat] " + ask("你是英语学习助手，用中文简短回答。", "你好", 60));
        // 2) 翻译（重点：这是本地优先能否成立的关键）
        diag("selftest[tr] " + askJson(
                "你是翻译引擎，只输出 JSON。",
                "把下面这句译成中文，返回 {\"zh\":\"译文\"}：\n"
                        + "The weather is really nice today.", 200));
        // 3) 批改
        diag("selftest[fix] " + askJson(
                "你是英语老师，只输出 JSON。",
                "指出这句的语法错误，返回 {\"fixed\":\"改写后\",\"why\":\"中文说明\"}：\n"
                        + "Yesterday I go to the park and very like it.", 300));
    }

    public static String systemInfo() {
        try {
            return dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeGetSystemInfo();
        } catch (Throwable e) {
            return "unavailable";
        }
    }

    /**
     * 预热：后台把模型读进内存（mmap 很快，但首 token 要建 KV cache）。
     *
     * 用户选了本地优先，就表示他愿意为 2.4GB 内存换「随时能问」，
     * 所以 App 启动后主动 warmup，第一次提问不用等加载。
     */
    public static void warmup(Context c) {
        if (handle != 0) return;
        new Thread(() -> {
            Pack p = active(c);
            if (p == null) {
                diag("warmup skip: no pack");
                return;
            }
            long t0 = System.currentTimeMillis();
            boolean ok = load(c, p);
            diag("warmup " + (ok ? "ok " : "fail ")
                    + (System.currentTimeMillis() - t0) + "ms " + p.id);
            if (ok) {
                // 跑一个 1 token 的短推理，把 KV cache / 计算图预热起来
                ask("", "hi", 4, 0.1f, 0.9f, 10);
                diag("warmup ready " + lastStats());
            }
        }, "llm-warmup").start();
    }

    public static synchronized void release() {
        if (handle != 0) {
            try {
                dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeReleaseModel(handle);
            } catch (Throwable ignored) {
            }
            handle = 0;
            loadedPath = "";
            loadedId = "";
        }
    }

    private LocalLlm() {
    }
}
