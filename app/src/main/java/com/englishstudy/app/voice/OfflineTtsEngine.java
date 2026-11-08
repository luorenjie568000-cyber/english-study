package com.englishstudy.app.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线 TTS：sherpa-onnx + VITS(piper) 模型，纯本地合成，零网络。
 * 模型未就绪时静默降级到系统 TTS（仍然离线，只是音色由系统决定）。
 */
public final class OfflineTtsEngine {

    private static OfflineTts tts;
    private static String voiceId = "";
    private static volatile boolean loading;
    /**
     * 每次切换音色 +1。
     *
     * 旧版本没有这个：切音色时 shutdown() 只 release 了对象，POOL 里排队的合成任务
     * 仍然会跑，而且 loading 标志没重置导致下一次 init 直接 return，
     * 结果就是「切了男声但听到的还是女声」。所有异步任务都要先核对 generation。
     */
    private static volatile int generation = 0;
    private static boolean failed;
    private static float rate = 0.92f;

    private static TextToSpeech sys;
    private static boolean sysReady;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-synth");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile AudioTrack track;
    private static final AtomicBoolean speaking = new AtomicBoolean(false);

    private OfflineTtsEngine() {
    }

    // ------------------------------------------------------------ 加载

    public static synchronized void init(Context c) {
        if (tts != null) return;
        final Context app = c.getApplicationContext();
        final int gen = generation;
        loading = true;
        failed = false;
        POOL.execute(() -> {
            OfflineTts loaded = null;
            String id = "";
            try {
                ModelRepo.Pack pack = ModelRepo.packFor(app,
                        com.englishstudy.app.data.Store.get("voice.tts", "amy"));
                if (pack == null) {
                    if (gen == generation) failed = true;
                    return;
                }
                ModelRepo.ensureEspeak(app);

                String modelName = pack.parts.get(0).name;
                String tokName = pack.parts.get(1).name;
                File model = ModelRepo.file(app, pack, modelName);
                File tokens = ModelRepo.file(app, pack, tokName);

                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(model.getAbsolutePath());
                vits.setTokens(tokens.getAbsolutePath());
                vits.setDataDir(ModelRepo.espeakDir(app).getAbsolutePath());
                vits.setNoiseScale(0.667f);
                vits.setNoiseScaleW(0.8f);
                // 运行时语速由 generate 的 speed 参数控制；这里固定 1.0，否则会和 speed 相乘叠加
                vits.setLengthScale(1.0f);

                OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
                mc.setVits(vits);
                mc.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                mc.setDebug(false);
                mc.setProvider("cpu");

                OfflineTtsConfig cfg = new OfflineTtsConfig();
                cfg.setModel(mc);
                cfg.setMaxNumSentences(1);
                cfg.setSilenceScale(0.2f);

                OfflineTts t = new OfflineTts(null, cfg);
                if (t.sampleRate() <= 0) {
                    try {
                        t.release();
                    } catch (Throwable ignored) {
                    }
                } else {
                    loaded = t;
                    id = pack.id;
                }
            } catch (Throwable e) {
                loaded = null;
            } finally {
                loading = false;
            }

            // 期间用户又切了音色 → 这个刚加载的作废
            if (gen != generation) {
                if (loaded != null) {
                    try {
                        loaded.release();
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            if (loaded != null) {
                tts = loaded;
                voiceId = id;
            } else {
                failed = true;
            }
        });
    }

    /** 当前加载的音色 id（amy / lessac），未加载返回空串。 */
    public static String loadedVoice() {
        return voiceId;
    }

    public static boolean ready() {
        return tts != null;
    }

    public static boolean unavailable() {
        return failed;
    }

    public static void setRate(float r) {
        rate = Math.max(0.5f, Math.min(1.6f, r));
        if (sys != null && sysReady) sys.setSpeechRate(rate);
    }

    public static float rate() {
        return rate;
    }

    // ------------------------------------------------------------ 朗读

    public static void say(Context c, String text) {
        say(c, text, rate);
    }

    /** 指定语速朗读；语速在合成任务内部生效，不受调用方后续改动影响。 */
    public static void say(Context c, String text, float useRate) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        final float r = Math.max(0.5f, Math.min(1.6f, useRate));
        final Context app = c.getApplicationContext();
        if (tts == null) {
            init(app);
            // 正在加载就别急着退系统 TTS：那会突然冒出另一个声音（这一直是
            // 「切了男声却听到女声」的重要来源）。等一小会儿再念。
            if (loading && !failed) {
                MAIN.postDelayed(() -> say(app, t, r), 250);
                return;
            }
            diag("say text=" + t.length() + "ch engine=sys rate=" + r);
            sysSay(app, t);
            return;
        }
        stop();
        speaking.set(true);
        final OfflineTts engine = tts;
        final int gen = generation;
        POOL.execute(() -> {
            try {
                if (engine == null || gen != generation) return;
                long t0 = System.currentTimeMillis();
                GeneratedAudio audio = engine.generate(t, 0, r);   // 第三参是 speed
                if (audio == null) {
                    diag("say gen=null");
                    return;
                }
                float[] samples = audio.getSamples();
                int sr = audio.getSampleRate();
                if (samples != null && samples.length > 0 && speaking.get()) {
                    diag("say gen ok samples=" + samples.length + " sr=" + sr
                            + " rate=" + r + " synth=" + (System.currentTimeMillis() - t0) + "ms");
                    play(samples, sr);
                }
            } catch (Throwable e) {
                diag("say ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    /** 播放自检日志，写 files/tts-diag.txt（追加）。 */
    private static void diag(String line) {
        try {
            Context c = com.englishstudy.app.App.ctx();
            if (c == null) return;
            java.io.File f = new java.io.File(c.getFilesDir(), "tts-diag.txt");
            if (f.length() > 20000) f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            w.println(System.currentTimeMillis() % 100000 + " " + line);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void play(float[] samples, int sampleRate) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            pcm[i] = (short) (v * 32767f);
        }
        int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, pcm.length * 2);
        AudioTrack at;
        try {
            at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable e) {
            diag("play build ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        track = at;
        try {
            at.play();
            diag("play start sr=" + sampleRate + " pcm=" + pcm.length + " buf=" + bufSize);
            int off = 0;
            while (off < pcm.length && speaking.get()) {
                int n = at.write(pcm, off, Math.min(4096, pcm.length - off));
                if (n <= 0) {
                    diag("play write n=" + n);
                    break;
                }
                off += n;
            }
            diag("play wrote off=" + off);
            // 写进缓冲只是"排队"，必须等播放头走完再 stop，否则立刻截断成 0 秒
            long durMs = pcm.length * 1000L / Math.max(1, sampleRate);
            long deadline = System.currentTimeMillis() + durMs + 1500;
            while (speaking.get() && System.currentTimeMillis() < deadline) {
                int head = at.getPlaybackHeadPosition();
                if (head >= pcm.length) break;
                Thread.sleep(40);
            }
            diag("play done head=" + at.getPlaybackHeadPosition() + "/" + pcm.length);
            at.stop();
        } catch (Throwable e) {
            diag("play ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                at.release();
            } catch (Throwable ignored) {
            }
            if (track == at) track = null;
        }
    }

    // ------------------------------------------------------------ 系统兜底

    private static void sysSay(Context app, String text) {
        if (sys == null) {
            sys = new TextToSpeech(app, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    sysReady = true;
                    sys.setLanguage(Locale.US);
                    sys.setSpeechRate(rate);
                }
            });
        }
        if (!sysReady) {
            MAIN.postDelayed(() -> sysSay(app, text), 350);
            return;
        }
        try {
            sys.speak(text, TextToSpeech.QUEUE_FLUSH, null, "es");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 朗读单个音标：读 assets/ipa/<sym>.raw（16k 单声道 PCM16）。
     *
     * 不能把音素符号交给 TTS 去念——模型会把它当拼写读成怪音，
     * 所以 44 个音素都用 espeak-ng 预合成好打包在 assets 里。
     */
    public static void sayPhone(Context c, String sym) {
        if (sym == null || sym.isEmpty()) return;
        final Context app = c.getApplicationContext();
        final String name = "ipa/" + sym.replace("\u02d0", "_") + ".raw";
        stop();
        speaking.set(true);
        POOL.execute(() -> {
            try {
                java.io.InputStream in = app.getAssets().open(name);
                byte[] raw = readAll(in);
                in.close();
                if (raw.length < 2) {
                    diag("phone empty " + name);
                    return;
                }
                float[] pcm = new float[raw.length / 2];
                for (int i = 0; i < pcm.length; i++) {
                    int lo = raw[i * 2] & 0xff;
                    int hi = raw[i * 2 + 1];
                    pcm[i] = (short) ((hi << 8) | lo) / 32768f;
                }
                if (speaking.get()) play(pcm, SR);
            } catch (Throwable e) {
                diag("phone ERR " + name + " " + e.getClass().getSimpleName());
            }
        });
    }

    private static final int SR = 16000;

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 自检用：合成一句话写成 wav，返回 "ok samples=… sr=… dur=…s path=…"。 */    public static String probe(String text) {
        try {
            long t0 = System.currentTimeMillis();
            while (tts == null && !failed && System.currentTimeMillis() - t0 < 30000) {
                Thread.sleep(200);
            }
            if (tts == null) return "no-engine";
            GeneratedAudio audio = tts.generate(text, 0, 1.0f);
            if (audio == null) return "null-audio";
            float[] s = audio.getSamples();
            if (s == null || s.length == 0) return "empty-audio";
            int sr = audio.getSampleRate();
            java.io.File f = new java.io.File(
                    com.englishstudy.app.App.ctx().getFilesDir(), "tts-probe.wav");
            audio.save(f.getAbsolutePath());
            return "ok samples=" + s.length + " sr=" + sr
                    + " dur=" + String.format(java.util.Locale.US, "%.2f", s.length / (float) sr)
                    + "s wav=" + f.length() + "B";
        } catch (Throwable e) {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 自检用：按指定语速合成，返回采样。 */
    public static float[] probeSamplesAt(String text, float speed) {
        try {
            long t0 = System.currentTimeMillis();
            while (tts == null && !failed && System.currentTimeMillis() - t0 < 30000) {
                Thread.sleep(200);
            }
            if (tts == null) return null;
            GeneratedAudio audio = tts.generate(text, 0, speed);
            return audio == null ? null : audio.getSamples();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 自检用：返回合成音频的 float 采样（16k 单声道），失败返回 null。 */
    public static float[] probeSamples(String text) {
        try {
            long t0 = System.currentTimeMillis();
            while (tts == null && !failed && System.currentTimeMillis() - t0 < 30000) {
                Thread.sleep(200);
            }
            if (tts == null) return null;
            GeneratedAudio audio = tts.generate(text, 0, 1.0f);
            if (audio == null) return null;
            return audio.getSamples();
        } catch (Throwable e) {
            return null;
        }
    }

    public static void stop() {
        speaking.set(false);
        AudioTrack at = track;
        if (at != null) {
            try {
                at.pause();
                at.flush();
                at.stop();
            } catch (Throwable ignored) {
            }
            track = null;
        }
        if (sys != null && sysReady) {
            try {
                sys.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void shutdown() {
        stop();
        // 作废所有排队中的合成任务，并允许下一次 init 重新加载
        generation++;
        loading = false;
        failed = false;
        voiceId = "";
        if (tts != null) {
            try {
                tts.release();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        if (sys != null) {
            try {
                sys.shutdown();
            } catch (Throwable ignored) {
            }
            sys = null;
            sysReady = false;
        }
    }
}
