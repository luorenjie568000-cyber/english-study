package com.englishstudy.app.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 端侧小模型（llama.cpp / GGUF）。
 *
 * 直接调 JNI 层（LlamaJNI 是同步方法），绕开 Kotlin 协程那一层，
 * 这样普通 Java 线程就能用，不需要引入 kotlinx-coroutines。
 *
 * 模型文件放在 filesDir/llm/qwen.gguf，用户从「对话 → 设置 → 本地模型」下载。
 */
public final class LocalLlm {

    private static final String TAG = "LocalLlm";

    /** 可选模型清单。 */
    public static final class Pack {
        public final String id;
        public final String title;
        public final String url;
        public final long size;
        public final String file;

        Pack(String id, String title, String url, long size, String file) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.size = size;
            this.file = file;
        }
    }

    private static final String HF = "https://hf-mirror.com/bartowski/";

    public static Pack pack() {
        return new Pack("qwen2.5-0.5b",
                "Qwen2.5 0.5B（380MB · 最快，适合查词）",
                HF + "Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
                397808192L, "qwen.gguf");
    }

    /** 1.5B：能真正组织语言，质量明显好；手机需要 ~1.2GB 空闲内存。 */
    public static Pack pack15() {
        return new Pack("qwen2.5-1.5b",
                "Qwen2.5 1.5B（940MB · 回答质量好）",
                HF + "Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
                986048768L, "qwen15.gguf");
    }

    public static Pack packSmol() {
        return new Pack("smollm2-360m",
                "SmolLM2 360M（约 258MB，更快）",
                HF + "SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
                270590880L, "smol.gguf");
    }

    public static List<Pack> packs() {
        List<Pack> l = new ArrayList<>();
        l.add(pack());
        l.add(pack15());
        l.add(packSmol());
        return l;
    }

    /** 当前启用的模型包：优先用户选的，没下就退能用的，都没有返回 null。 */
    public static Pack active(Context c) {
        String want = com.englishstudy.app.data.Store.get("ai.llm", "qwen2.5-1.5b");
        for (Pack p : packs()) {
            if (p.id.equals(want) && ready(c, p)) return p;
        }
        for (Pack p : packs()) {
            if (ready(c, p)) return p;
        }
        return null;
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

    public static boolean loaded() {
        return handle != 0;
    }

    public static String loadedName() {
        File f = new File(loadedPath);
        return loadedPath.isEmpty() ? "" : f.getName();
    }

    /** 加载模型（阻塞，放到后台线程调用）。 */
    public static synchronized boolean load(Context c, Pack p) {
        if (handle != 0 && loadedPath.equals(file(c, p).getAbsolutePath())) return true;
        release();
        File f = file(c, p);
        if (!f.exists()) return false;
        try {
            int threads = Math.max(2, Math.min(4,
                    Runtime.getRuntime().availableProcessors() / 2));
            // nativeLoadModel(path, contextSize, threads, gpuLayers)
            long t0 = System.currentTimeMillis();
            long h = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeLoadModel(
                    f.getAbsolutePath(), 1024, threads, 0);
            diag("load " + (System.currentTimeMillis() - t0) + "ms handle=" + h
                    + " size=" + (f.length() / 1048576) + "MB");
            if (h == 0) {
                Log.w(TAG, "load failed: " + f.getAbsolutePath());
                return false;
            }
            handle = h;
            loadedPath = f.getAbsolutePath();
            Log.i(TAG, "loaded " + f.getName() + " threads=" + threads);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "load error", e);
            return false;
        }
    }

    /**
     * 一次问答（阻塞，放到后台线程调用）。
     *
     * 注意：JNI 内部会用模型自带的 chat template 拼 prompt，
     * 所以这里传的是**裸文本**，不能自己再套 &lt;|im_start|&gt;，否则会双套模板。
     * 返回值是 JSON（含 text / tokensPerSecond 等字段），这里只取 text。
     */
    public static String ask(String system, String user, int maxTokens) {
        if (handle == 0) return "";
        try {
            String json = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeComplete(
                    handle, user, system, maxTokens, 0.7f, 0.9f, 40, 42);
            diag("raw=" + (json == null ? "null" : json.length() + "ch " + json.substring(0, Math.min(160, json.length()))));
            if (json == null || json.isEmpty()) return "";
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                stats = String.format(java.util.Locale.US, "%.1f tok/s · prompt %d",
                        o.optDouble("tokensPerSecond", 0), o.optInt("promptTokens", 0));
            } catch (Throwable ignored) {
            }
            return textOf(json);
        } catch (Throwable e) {
            Log.e(TAG, "complete failed", e);
            return "";
        }
    }

    /** 从 JNI 返回的 JSON 里取 text 字段（不引入 org.json 之外的东西）。 */
    private static String textOf(String json) {
        try {
            return new org.json.JSONObject(json).optString("text", "");
        } catch (Throwable e) {
            return json;
        }
    }

    /** 上次合成的速度指标，仅用于诊断。 */
    public static String lastStats() {
        return stats;
    }

    private static String stats = "";

    private static void diag(String line) {
        try {
            android.content.Context c = com.englishstudy.app.App.ctx();
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "llm-diag.txt");
            if (f.length() > 40000) f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            w.println(System.currentTimeMillis() % 1000000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 自检：用几种不同长度的 prompt 各跑一次，把每步的 token 数写进诊断。 */
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
        String[] users = {"hi", "用中文说一句话", "apple 是什么意思"};
        for (String u : users) {
            String json = "";
            try {
                json = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeComplete(
                        handle, u, "你是助手", 60, 0.7f, 0.9f, 40, 42);
            } catch (Throwable e) {
                json = "ERR " + e;
            }
            diag("selftest[" + u + "] " + json);
        }
        // 再试一次不带 system，看看是否和模板有关
        try {
            String json = dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeComplete(
                    handle, "hi", "", 60, 0.7f, 0.9f, 40, 42);
            diag("selftest-nosys " + json);
        } catch (Throwable e) {
            diag("selftest-nosys ERR " + e);
        }
    }

    public static String systemInfo() {
        try {
            return dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeGetSystemInfo();
        } catch (Throwable e) {
            return "unavailable";
        }
    }

    public static synchronized void release() {
        if (handle != 0) {
            try {
                dev.ffmpegkit.llama.LlamaJNI.INSTANCE.nativeReleaseModel(handle);
            } catch (Throwable ignored) {
            }
            handle = 0;
            loadedPath = "";
        }
    }

    private LocalLlm() {
    }
}
