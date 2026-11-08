package com.englishstudy.app.voice;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线语音模型仓库：负责模型落盘、资产解包、断点下载。
 *
 * 目录布局（filesDir/voice）：
 *   espeak-ng-data/            发音数据（assets 内置，首次解包 1.2MB）
 *   vits-amy/amy.onnx          英文 TTS 声学模型（下载 63MB）
 *   vits-amy/tokens.txt
 *   whisper-tiny.en/*.onnx     英文 ASR（下载，可选）
 *   .part/                     下载中间文件
 */
public final class ModelRepo {

    public static final String KIND_TTS = "tts";
    public static final String KIND_ASR = "asr";

    /** 单个可下载文件。 */
    public static final class Part {
        public final String name;      // 本地文件名
        public final String url;       // 直链
        public final long size;        // 期望字节数，-1 未知
        public final boolean gz;       // 服务器给的是 .gz（体积更小）

        Part(String name, String url, long size, boolean gz) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.gz = gz;
        }
    }

    /** 一套模型 = 一组文件。 */
    public static final class Pack {
        public final String kind;
        public final String id;
        public final String title;
        public final String dir;
        public final List<Part> parts = new ArrayList<>();

        Pack(String kind, String id, String title, String dir) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.dir = dir;
        }

        Pack add(String name, String url, long size) {
            parts.add(new Part(name, url, size, false));
            return this;
        }

        public long totalBytes() {
            long t = 0;
            for (Part p : parts) t += p.size < 0 ? 0 : p.size;
            return t;
        }
    }

    // ---------------------------------------------------------------- 目录

    public static File root(Context c) {
        File f = new File(c.getFilesDir(), "voice");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File packDir(Context c, Pack p) {
        File f = new File(root(c), p.dir);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File file(Context c, Pack p, String name) {
        return new File(packDir(c, p), name);
    }

    public static File espeakDir(Context c) {
        return new File(root(c), "espeak-ng-data");
    }

    // ---------------------------------------------------------------- 包定义

    private static final String HF = "https://hf-mirror.com/csukuangfj/";

    /** 英文 TTS：VITS / Piper amy（女声，16kHz，63MB，内置 espeak 音素化）。 */
    public static Pack ttsAmi() {
        Pack p = new Pack(KIND_TTS, "vits-amy", "英文女声 Amy（VITS 63MB）", "vits-amy");
        p.add("amy.onnx", HF + "vits-piper-en_US-amy-low/resolve/main/en_US-amy-low.onnx", 63104657L);
        p.add("tokens.txt", HF + "vits-piper-en_US-amy-low/resolve/main/tokens.txt", 763L);
        return p;
    }

    /** 英文 TTS：VITS lessac medium（男声更自然，63MB）。 */
    public static Pack ttsLessac() {
        Pack p = new Pack(KIND_TTS, "vits-lessac", "英文男声 Lessac（VITS 63MB）", "vits-lessac");
        p.add("model.onnx", HF + "vits-piper-en_US-lessac-medium/resolve/main/en_US-lessac-medium.onnx", 63201425L);
        p.add("tokens.txt", HF + "vits-piper-en_US-lessac-medium/resolve/main/tokens.txt", 921L);
        return p;
    }

    /** 英文 ASR：Whisper tiny.en int8（103MB，准确率高）。 */
    public static Pack asrWhisper() {
        Pack p = new Pack(KIND_ASR, "whisper-tiny.en", "英文识别 Whisper tiny.en（103MB）", "whisper-tiny.en");
        p.add("tiny.en-encoder.int8.onnx",
                HF + "sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-encoder.int8.onnx", 12937772L);
        p.add("tiny.en-decoder.int8.onnx",
                HF + "sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-decoder.int8.onnx", 89853865L);
        p.add("tiny.en-tokens.txt",
                HF + "sherpa-onnx-whisper-tiny.en/resolve/main/tiny.en-tokens.txt", 835554L);
        return p;
    }

    /** 通用 whisper 包构造：四档只差文件名和体积。 */
    private static Pack whisper(String size, String title, long enc, long dec) {
        Pack p = new Pack(KIND_ASR, "whisper-" + size + ".en", title,
                "whisper-" + size + ".en");
        p.add(size + ".en-encoder.int8.onnx",
                HF + "sherpa-onnx-whisper-" + size + ".en/resolve/main/"
                        + size + ".en-encoder.int8.onnx", enc);
        p.add(size + ".en-decoder.int8.onnx",
                HF + "sherpa-onnx-whisper-" + size + ".en/resolve/main/"
                        + size + ".en-decoder.int8.onnx", dec);
        p.add(size + ".en-tokens.txt",
                HF + "sherpa-onnx-whisper-" + size + ".en/resolve/main/"
                        + size + ".en-tokens.txt", 835554L);
        return p;
    }

    /** base.en：74M 参数，153MB。准确度明显好于 tiny，速度仍很快。 */
    public static Pack asrWhisperBase() {
        return whisper("base", "英文识别 Whisper base（153MB · 推荐）",
                29120534L, 130669978L);
    }

    /** small.en：244M 参数，358MB。日常视频字幕基本不出错。 */
    public static Pack asrWhisperSmall() {
        return whisper("small", "英文识别 Whisper small（358MB · 更准）",
                112442483L, 262223042L);
    }

    /** medium.en：769M 参数，902MB。专有名词/口音也能扛，速度慢。 */
    public static Pack asrWhisperMedium() {
        return whisper("medium", "英文识别 Whisper medium（902MB · 最准）",
                374196283L, 571055161L);
    }

    /** 英文 ASR：NeMo CTC conformer small int8（46MB，快）。 */
    public static Pack asrNemo() {
        Pack p = new Pack(KIND_ASR, "nemo-ctc-en", "英文识别 NeMo CTC small（46MB）", "nemo-ctc-en");
        p.add("model.int8.onnx",
                HF + "sherpa-onnx-nemo-ctc-en-conformer-small/resolve/main/model.int8.onnx", 46419854L);
        p.add("tokens.txt",
                HF + "sherpa-onnx-nemo-ctc-en-conformer-small/resolve/main/tokens.txt", 11611L);
        return p;
    }

    /**
     * 全部可选的英文识别模型，从快到准排列。
     *
     * 用户可自行下载并切换 —— 识别不准时换大模型是最直接的办法。
     */
    public static List<Pack> asrPacks() {
        List<Pack> l = new ArrayList<>();
        l.add(asrNemo());          // 46MB，最快，但准确度一般
        l.add(asrWhisper());       // 103MB tiny
        l.add(asrWhisperBase());   // 153MB base
        l.add(asrWhisperSmall());  // 358MB small
        l.add(asrWhisperMedium()); // 902MB medium
        return l;
    }

    /**
     * 按音色 id 找包：优先要的那个，没下载就退另一个，都没有返回 null。
     * 这样上层不用重复写兜底逻辑，也不会出现「想切男声但悄悄用了女声还不说」。
     */
    public static Pack packFor(Context c, String want) {
        Pack first = "lessac".equals(want) ? ttsLessac() : ttsAmi();
        Pack other = "lessac".equals(want) ? ttsAmi() : ttsLessac();
        if (ready(c, first)) return first;
        if (ready(c, other)) return other;
        return null;
    }

    public static List<Pack> all() {
        List<Pack> l = new ArrayList<>();
        l.add(ttsAmi());
        l.add(ttsLessac());
        l.add(asrWhisper());
        l.add(asrNemo());
        return l;
    }

    // ---------------------------------------------------------------- 状态

    /** 包内所有文件是否已就绪。 */
    public static boolean ready(Context c, Pack p) {
        for (Part part : p.parts) {
            File f = file(c, p, part.name);
            // 不能按 1KB 判空：tokens.txt 只有 763 字节
            if (!f.exists() || f.length() <= 0) return false;
            if (part.size > 0 && f.length() != part.size) return false;
        }
        return true;
    }

    /** 已下载字节数（含 .part 断点）。 */
    public static long doneBytes(Context c, Pack p) {
        long n = 0;
        for (Part part : p.parts) {
            File f = file(c, p, part.name);
            if (f.exists()) n += f.length();
            else {
                File t = partFile(c, p, part.name);
                if (t.exists()) n += t.length();
            }
        }
        return n;
    }

    /** renameTo 在部分机型上会静默失败，退回流式复制。 */
    static boolean moveTo(File src, File dst) {
        if (dst.exists()) dst.delete();
        if (src.renameTo(dst)) return true;
        try {
            InputStream in = new java.io.FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            if (dst.length() != src.length()) return false;
            src.delete();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static File partFile(Context c, Pack p, String name) {
        File d = new File(root(c), ".part");
        if (!d.exists()) d.mkdirs();
        return new File(d, p.id + "." + name + ".part");
    }

    public static void delete(Context c, Pack p) {
        for (Part part : p.parts) {
            file(c, p, part.name).delete();
            partFile(c, p, part.name).delete();
        }
    }

    // ---------------------------------------------------------------- espeak 数据

    /** 把 assets/voice/espeak-ng-data 解包到 filesDir（只做一次）。 */
    public static boolean ensureEspeak(Context c) {
        File dir = espeakDir(c);
        File marker = new File(dir, "phontab");
        if (marker.exists() && marker.length() > 1000) return true;
        try {
            copyAssetTree(c.getAssets(), "voice/espeak-ng-data", dir);
            return marker.exists();
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyAssetTree(AssetManager am, String from, File to) throws IOException {
        String[] kids = am.list(from);
        if (kids == null || kids.length == 0) {
            // 是文件
            if (!to.exists()) to.getParentFile().mkdirs();
            InputStream in = am.open(from);
            OutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return;
        }
        if (!to.exists()) to.mkdirs();
        for (String k : kids) copyAssetTree(am, from + "/" + k, new File(to, k));
    }

    // ---------------------------------------------------------------- 下载

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 断点续传下载整个包；失败抛 IOException。 */
    public static void download(Context c, Pack p, Progress cb, boolean[] cancel)
            throws IOException {
        // 单飞：同一个包不允许并发下载，否则两个流写同一个 .part 会把模型写坏
        synchronized (INFLIGHT) {
            if (INFLIGHT.contains(p.id)) throw new IOException("该模型包正在下载中");
            INFLIGHT.add(p.id);
        }
        try {
            downloadInner(c, p, cb, cancel);
        } finally {
            synchronized (INFLIGHT) {
                INFLIGHT.remove(p.id);
            }
        }
    }

    private static final java.util.HashSet<String> INFLIGHT = new java.util.HashSet<>();

    private static void downloadInner(Context c, Pack p, Progress cb, boolean[] cancel)
            throws IOException {
        long total = p.totalBytes();
        for (Part part : p.parts) {
            if (cancel != null && cancel[0]) throw new IOException("已取消");
            File dst = file(c, p, part.name);
            if (dst.exists() && (part.size <= 0 || dst.length() == part.size)) continue;
            File tmp = partFile(c, p, part.name);
            long have = tmp.exists() ? tmp.length() : 0;
            if (part.size > 0 && have == part.size) {
                if (!moveTo(tmp, dst)) throw new IOException("落盘失败 " + part.name);
                continue;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(part.url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "EnglishStudy/1.0");
            if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (code == 200 && have > 0) {
                // 服务器不支持续传，从头来
                tmp.delete();
                have = 0;
            }
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp, have > 0);
            byte[] buf = new byte[131072];
            long got = have;
            long lastCb = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel[0]) {
                    out.close();
                    in.close();
                    conn.disconnect();
                    throw new IOException("已取消");
                }
                out.write(buf, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                if (cb != null && now - lastCb > 250) {
                    lastCb = now;
                    cb.onProgress(doneBytes(c, p), total);
                }
            }
            out.close();
            in.close();
            conn.disconnect();
            if (part.size > 0 && tmp.length() != part.size) {
                throw new IOException("大小不符 " + part.name + " " + tmp.length() + "/" + part.size);
            }
            if (!moveTo(tmp, dst)) throw new IOException("落盘失败 " + part.name);
            if (cb != null) cb.onProgress(doneBytes(c, p), total);
        }
    }
}