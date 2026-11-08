package com.englishstudy.app.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线 ASR：sherpa-onnx + Whisper tiny.en（或 NeMo CTC），全程本地。
 * 录音 → 16k 单声道 float → 识别 → 回调文本。
 */
public final class OfflineAsrEngine {

    public static final int SR = 16000;
    /** 兜底上限：30 秒，正常由用户手动停止。 */
    private static final long MAX_MS = 30000;

    public interface Callback {
        /** 带时间戳的结果（默认空实现，老调用方不用改）。 */
        default void onTimed(TimedResult r) { }

        /** 录音开始。 */
        void onStart();

        /** 实时音量（0~1），用于波形。 */
        void onLevel(float level);

        /** 识别完成，text 可能为空串。 */
        void onResult(String text);

        /** 出错。 */
        void onError(String msg);
    }

    private static OfflineRecognizer rec;
    private static String loadedId;
    private static boolean loading;
    private static boolean failed;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "asr");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean recording;
    private static volatile boolean stopRequested;

    private OfflineAsrEngine() {
    }

    // ------------------------------------------------------------ 加载

    public static synchronized void init(Context c) {
        if (rec != null || loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            try {
                OfflineRecognizer r = build(app);
                if (r != null) rec = r;
                else failed = true;
            } catch (Throwable e) {
                failed = true;
            } finally {
                loading = false;
            }
        });
    }

    /** 可选的 whisper 档位，按「用户选择 → 从大到小」挑第一个已下载的。 */
    private static ModelRepo.Pack pickWhisper(Context app) {
        String want = com.englishstudy.app.data.Store.get("voice.asr", "whisper-tiny.en");
        List<ModelRepo.Pack> all = new ArrayList<>();
        all.add(ModelRepo.asrWhisperMedium());
        all.add(ModelRepo.asrWhisperSmall());
        all.add(ModelRepo.asrWhisperBase());
        all.add(ModelRepo.asrWhisper());
        for (ModelRepo.Pack p : all) {
            if (p.id.equals(want) && ModelRepo.ready(app, p)) return p;
        }
        for (ModelRepo.Pack p : all) {
            if (ModelRepo.ready(app, p)) return p;
        }
        return null;
    }

    /** 当前加载的模型（供设置页显示）。 */
    public static String loadedPackId() {
        return loadedId == null ? "" : loadedId;
    }

    private static OfflineRecognizer build(Context app) {
        OfflineModelConfig mc = new OfflineModelConfig();
        mc.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        mc.setDebug(false);
        mc.setProvider("cpu");

        // 按用户在「我的 → 离线语音」选的档位加载；没选/没下载就依次往下退。
        // 这让「换更大的模型」真的生效，而不是写死 tiny。
        ModelRepo.Pack wp = pickWhisper(app);
        if (wp != null) {
            OfflineWhisperModelConfig w = new OfflineWhisperModelConfig();
            String sz = wp.id.replace("whisper-", "").replace(".en", "");
            w.setEncoder(ModelRepo.file(app, wp, sz + ".en-encoder.int8.onnx")
                    .getAbsolutePath());
            w.setDecoder(ModelRepo.file(app, wp, sz + ".en-decoder.int8.onnx")
                    .getAbsolutePath());
            w.setLanguage("en");
            w.setTask("transcribe");
            w.setTailPaddings(1000);
            mc.setWhisper(w);
            mc.setTokens(ModelRepo.file(app, wp,
                    sz + ".en-tokens.txt").getAbsolutePath());
            mc.setModelType("whisper");
            loadedId = wp.id;
        } else {
            ModelRepo.Pack np = ModelRepo.asrNemo();
            if (!ModelRepo.ready(app, np)) return null;
            OfflineNemoEncDecCtcModelConfig n = new OfflineNemoEncDecCtcModelConfig();
            n.setModel(ModelRepo.file(app, np, "model.int8.onnx").getAbsolutePath());
            mc.setNemo(n);
            mc.setTokens(ModelRepo.file(app, np, "tokens.txt").getAbsolutePath());
            mc.setModelType("nemo_ctc");
            loadedId = np.id;
        }

        FeatureConfig fc = new FeatureConfig();
        fc.setSampleRate(SR);
        fc.setFeatureDim(80);

        OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
        rc.setFeatConfig(fc);
        rc.setModelConfig(mc);
        rc.setDecodingMethod("greedy_search");

        return new OfflineRecognizer(null, rc);
    }

    public static boolean ready() {
        return rec != null;
    }

    public static boolean unavailable() {
        return failed;
    }

    public static String loadedPack() {
        return loadedId;
    }

    public static void release() {
        if (rec != null) {
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
            rec = null;
        }
    }

    // ------------------------------------------------------------ 录音 + 识别

    /** 开始录音，直到静音 1.2s 或调用 stopListening()。 */
    public static void listen(Context c, Callback cb) {
        final Context app = c.getApplicationContext();
        if (recording) return;
        if (rec == null) {
            init(app);
            if (cb != null) cb.onError("模型未就绪");
            return;
        }
        recording = true;
        stopRequested = false;
        POOL.execute(() -> doListen(app, cb));
    }

    public static void stopListening() {
        stopRequested = true;
    }

    public static boolean isRecording() {
        return recording;
    }

    private static void doListen(Context app, Callback cb) {
        int minBuf = AudioRecord.getMinBufferSize(SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, SR / 5 * 2);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable e) {
            recording = false;
            postErr(cb, "无法打开麦克风");
            return;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            recording = false;
            postErr(cb, "麦克风被占用");
            return;
        }

        List<short[]> chunks = new ArrayList<>();
        int totalSamples = 0;
        float maxRms = 0;
        short[] buf = new short[1600]; // 100ms
        long started = System.currentTimeMillis();
        try {
            ar.startRecording();
            post(cb, () -> cb.onStart());
            while (!stopRequested) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) continue;
                short[] copy = new short[n];
                System.arraycopy(buf, 0, copy, 0, n);
                chunks.add(copy);
                totalSamples += n;

                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double) buf[i] * buf[i];
                float rms = (float) Math.sqrt(sum / n) / 32768f;
                if (rms > maxRms) maxRms = rms;
                final float lvl = Math.min(1f, rms * 8f);
                post(cb, () -> cb.onLevel(lvl));

                // 只做电平上报，不自动截断：由用户点「停止」才结束
                if (System.currentTimeMillis() - started > MAX_MS) break;  // 兜底上限
            }
        } catch (Throwable e) {
            postErr(cb, "录音失败");
        } finally {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
            ar.release();
            recording = false;
        }

        if (totalSamples < SR / 5) {
            diag("listen short samples=" + totalSamples + " maxRms=" + maxRms);
            post(cb, () -> cb.onResult(""));
            return;
        }
        diag("listen done samples=" + totalSamples + " maxRms=" + maxRms
                + " ms=" + (System.currentTimeMillis() - started));

        float[] pcm = new float[totalSamples];
        int off = 0;
        for (short[] ch : chunks) {
            for (short s : ch) pcm[off++] = s / 32768f;
        }

        String text = "";
        try {
            OfflineStream st = rec.createStream();
            st.acceptWaveform(pcm, SR);
            rec.decode(st);
            OfflineRecognizerResult r = rec.getResult(st);
            if (r != null && r.getText() != null) text = clean(r.getText());
            st.release();
        } catch (Throwable e) {
            postErr(cb, "识别失败");
            return;
        }
        final String out = text;
        // 再跑一次带时间戳的识别（同一段 PCM，成本约等于一次识别）
        final TimedResult timed = recognizeTimed(pcm, SR);
        if (timed.text == null || timed.text.isEmpty()) timed.text = out;
        post(cb, () -> cb.onTimed(timed));
        post(cb, () -> cb.onResult(out));
    }

    /** 录音诊断日志，写 files/asr-diag.txt（追加）。 */
    private static void diag(String line) {
        try {
            Context c = com.englishstudy.app.App.ctx();
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "asr-diag.txt");
            if (f.length() > 20000) f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            w.println(System.currentTimeMillis() % 100000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void post(Callback cb, Runnable r) {        if (cb == null) return;
        MAIN.post(r);
    }

    private static void postErr(Callback cb, String m) {
        if (cb == null) return;
        MAIN.post(() -> cb.onError(m));
    }

    // ------------------------------------------------------------ 自检：TTS 音频回灌识别

    /** 一个识别出的词及其时间区间（秒）。 */
    public static final class TimedWord {
        public final String text;
        public final float start;
        public final float end;

        TimedWord(String t, float s, float e) {
            text = t;
            start = s;
            end = e;
        }
    }

    /** 带时间戳的识别结果。 */
    public static final class TimedResult {
        public String text = "";
        public final List<TimedWord> words = new ArrayList<>();
        public float duration;
    }

    /**
     * 识别并返回词级时间戳（sherpa 的 OfflineRecognizerResult.getTimestamps()）。
     *
     * 有逐词时间就能做「哪个词念得慢/卡住/漏掉」的判断，
     * 比纯文本比对（现在的 score()）准确得多。
     */
    public static TimedResult recognizeTimed(float[] pcm, int sr) {
        TimedResult out = new TimedResult();
        if (rec == null || pcm == null || pcm.length == 0) return out;
        out.duration = pcm.length / (float) sr;
        try {
            OfflineStream st = rec.createStream();
            st.acceptWaveform(pcm, sr);
            rec.decode(st);
            OfflineRecognizerResult r = rec.getResult(st);
            if (r == null) return out;
            out.text = clean(r.getText());
            String[] toks = r.getTokens();
            float[] ts = r.getTimestamps();
            if (toks == null) return out;
            for (int i = 0; i < toks.length; i++) {
                String t = toks[i] == null ? "" : toks[i].trim();
                if (t.isEmpty()) continue;
                // 时间戳是 token 级，词的边界靠「前面是空格」判断（BPE 记法）
                float s0 = (ts != null && i < ts.length) ? ts[i] : 0f;
                float s1 = (ts != null && i + 1 < ts.length) ? ts[i + 1] : out.duration;
                String piece = t.startsWith("\u2581") ? t.substring(1) : t;
                if (piece.isEmpty()) continue;
                out.words.add(new TimedWord(piece, s0, s1));
            }
            st.release();
        } catch (Throwable e) {
            diag("timed ERR " + e);
        }
        return out;
    }

    /** 把一段 16k 单声道 float PCM 直接喂给识别器（绕过麦克风，用于闭环自检）。 */
    public static String recognize(float[] pcm, int sr) {
        try {
            long t0 = System.currentTimeMillis();
            while (rec == null && !failed && System.currentTimeMillis() - t0 < 30000) {
                Thread.sleep(200);
            }
            if (rec == null) return "no-engine";
            OfflineStream st = rec.createStream();
            st.acceptWaveform(pcm, sr);
            rec.decode(st);
            OfflineRecognizerResult r = rec.getResult(st);
            String text = r == null || r.getText() == null ? "" : clean(r.getText());
            st.release();
            return text;
        } catch (Throwable e) {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ 评分

    /** 去掉 Whisper 的占位标记（[BLANK_AUDIO]、[MUSIC]、(笑声) 等）。 */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("\\([^)]{0,20}\\)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.equalsIgnoreCase("blank_audio")) return "";
        return t;
    }

    /** 归一化：小写、去标点、压空白。 */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.US).replaceAll("[^a-z' ]", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** 简单发音相似度 0~100：词级命中 + 字符级编辑距离。 */
    public static int score(String target, String heard) {
        String a = norm(target);
        String b = norm(heard);
        if (a.isEmpty()) return 0;
        if (b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        String[] aw = a.split(" ");
        String[] bw = b.split(" ");
        int hit = 0;
        for (String w : aw) {
            for (String h : bw) {
                if (h.equals(w)) {
                    hit++;
                    break;
                }
            }
        }
        double wordScore = aw.length == 0 ? 0 : (double) hit / aw.length;
        double charScore = 1.0 - (double) edit(a, b) / Math.max(a.length(), b.length());
        if (charScore < 0) charScore = 0;
        double s = wordScore * 0.65 + charScore * 0.35;
        return (int) Math.round(Math.max(0, Math.min(1, s)) * 100);
    }

    private static int edit(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }
}
