package com.englishstudy.app.engine;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 从视频/音频文件里抽出音轨 → 16k 单声道 PCM。
 *
 * 为什么必须自己写：
 *   - MediaPlayer 不给 PCM
 *   - MediaMetadataRetriever 只能给时长
 *   - 要喂给 ASR 就必须是裸 PCM
 *
 * 做法：MediaExtractor 取 audio track → MediaCodec 硬件解码 → 重采样到 16k 单声道。
 * 重采样用「线性插值 + 声道平均」，够 ASR 用（它不是音乐分析）。
 */
public final class AudioExtract {

    private static final String TAG = "AudioExtract";
    public static final int SR = 16000;

    /** 抽出结果。 */
    public static final class Pcm {
        public float[] samples = new float[0];
        public int sampleRate = SR;
        public String error = "";

        public float seconds() {
            return samples.length / (float) sampleRate;
        }

        public boolean ok() {
            return error.isEmpty() && samples.length > 0;
        }
    }

    private AudioExtract() {
    }

    /** 从 Uri 抽音轨。阻塞，必须放后台线程。 */
    public static Pcm extract(Context c, Uri uri) {
        Pcm out = new Pcm();
        MediaExtractor ex = null;
        MediaCodec codec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(c, uri, null);

            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) {
                out.error = "这个文件里没有音轨";
                return out;
            }
            ex.selectTrack(track);

            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int srcCh = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            List<short[]> chunks = new ArrayList<>();
            int total = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        int size = ex.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = ex.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, size, pts, 0);
                            ex.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            short[] pcm = toMono16(ob, srcCh);
                            if (pcm.length > 0) {
                                chunks.add(pcm);
                                total += pcm.length;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcCh = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }

            if (total == 0) {
                out.error = "没解出音频数据（可能是纯视频或加密）";
                return out;
            }

            // 合并 + 重采样到 16k
            short[] all = new short[total];
            int off = 0;
            for (short[] ch : chunks) {
                System.arraycopy(ch, 0, all, off, ch.length);
                off += ch.length;
            }
            float[] resampled = resample(all, srcRate, SR);
            out.samples = resampled;
            out.sampleRate = SR;
            return out;
        } catch (Throwable e) {
            Log.e(TAG, "extract failed", e);
            out.error = String.valueOf(e.getMessage());
            return out;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (ex != null) ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** ByteBuffer → 单声道 short[]（多声道取平均）。 */
    private static short[] toMono16(ByteBuffer buf, int channels) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = buf.asShortBuffer();
        int n = sb.remaining();
        if (channels <= 1) {
            short[] out = new short[n];
            sb.get(out);
            return out;
        }
        int frames = n / channels;
        short[] out = new short[frames];
        short[] tmp = new short[n];
        sb.get(tmp);
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += tmp[i * channels + c];
            out[i] = (short) (sum / channels);
        }
        return out;
    }

    /** 线性插值重采样。 */
    private static float[] resample(short[] src, int from, int to) {
        if (from == to) {
            float[] out = new float[src.length];
            for (int i = 0; i < src.length; i++) out[i] = src[i] / 32768f;
            return out;
        }
        int n = (int) ((long) src.length * to / from);
        float[] out = new float[n];
        double step = from / (double) to;
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, src.length - 1);
            double f = pos - i0;
            double v = src[i0] * (1 - f) + src[i1] * f;
            out[i] = (float) (v / 32768.0);
        }
        return out;
    }

    // ---------------------------------------------------------------- 切句

    /** 一个句子片段。 */
    public static final class Seg {
        public int startMs;
        public int endMs;
        public String text = "";
        public String zh = "";

        public Seg(int s, int e) {
            startMs = s;
            endMs = e;
        }
    }

    /**
     * 按能量做 VAD 切句。
     *
     * 原理：10ms 一帧算 RMS，低于阈值算静音；连续静音 > minGapMs 就切一刀。
     * 太长（> maxMs）强制在最近的静音点切，太短（< minMs）与下一段合并。
     *
     * 这不是「语义切句」，但对朗读类素材够准 —— 人说话本来就有停顿。
     */
    public static List<Seg> segment(Pcm pcm) {
        List<Seg> out = new ArrayList<>();
        if (!pcm.ok()) return out;

        float[] s = pcm.samples;
        int frame = SR / 100;                 // 10ms
        int nFrames = s.length / frame;
        if (nFrames < 5) return out;

        float[] rms = new float[nFrames];
        float peak = 0;
        for (int i = 0; i < nFrames; i++) {
            double sum = 0;
            int base = i * frame;
            for (int k = 0; k < frame; k++) {
                double v = s[base + k];
                sum += v * v;
            }
            float r = (float) Math.sqrt(sum / frame);
            rms[i] = r;
            if (r > peak) peak = r;
        }
        if (peak <= 0.001f) return out;

        // 阈值：峰值的 8%，且不低于绝对底噪
        float th = Math.max(peak * 0.08f, 0.004f);

        final int minGap = 35;    // 连续 350ms 静音 → 断句（太短会切碎）
        final int minLen = 50;    // 至少 500ms
        final int maxLen = 800;   // 最多 8s（一句话的合理上限）

        int start = -1;
        int silent = 0;
        List<int[]> raw = new ArrayList<>();

        for (int i = 0; i < nFrames; i++) {
            boolean voiced = rms[i] > th;
            if (voiced) {
                if (start < 0) start = i;
                silent = 0;
                if (i - start >= maxLen) {
                    raw.add(new int[]{start, i});
                    start = -1;
                }
            } else {
                if (start >= 0) {
                    silent++;
                    if (silent >= minGap) {
                        int end = i - silent + 1;
                        if (end - start >= minLen) {
                            raw.add(new int[]{start, end});
                        } else if (!raw.isEmpty()) {
                            // 太短 → 并到前一段
                            int[] prev = raw.get(raw.size() - 1);
                            prev[1] = end;
                        }
                        start = -1;
                        silent = 0;
                    }
                }
            }
        }
        if (start >= 0 && nFrames - start >= minLen / 2) {
            raw.add(new int[]{start, nFrames});
        }

        for (int[] r : raw) {
            int st = r[0] * frame * 1000 / SR;
            int en = r[1] * frame * 1000 / SR;
            // 前后各留 80ms 余量，避免切掉首尾音
            out.add(new Seg(Math.max(0, st - 80), en + 80));
        }
        return out;
    }

    /** 取一段 PCM（按毫秒）。 */
    public static float[] slice(Pcm pcm, int startMs, int endMs) {
        int a = Math.max(0, startMs * pcm.sampleRate / 1000);
        int b = Math.min(pcm.samples.length, endMs * pcm.sampleRate / 1000);
        if (b <= a) return new float[0];
        float[] out = new float[b - a];
        System.arraycopy(pcm.samples, a, out, 0, out.length);
        return out;
    }
}
