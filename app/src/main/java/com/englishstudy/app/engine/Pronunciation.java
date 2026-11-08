package com.englishstudy.app.engine;

import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 逐词发音评分（基于 ASR 的词级时间戳）。
 *
 * 比「整句字符串相似度」好在哪：
 *   - 能指出**具体哪个词**念错了（红色标记）
 *   - 能算出**语速**（词/分），判断是不是念太快或太慢
 *   - 能看出**停顿**在哪（两个词间隔 > 0.5s）
 *
 * 局限（必须说清楚）：
 *   这不是音素级 GOP（Goodness of Pronunciation）对齐 —— 那需要声学模型输出每帧
 *   音素后验概率，sherpa 的离线识别器不暴露这个。所以：
 *   - 能判断「词对不对」（识别结果 vs 目标）
 *   - **不能**判断「音素准不准」（比如把 /θ/ 念成 /s/，只要识别成同一个词就算对）
 *
 * 想要真 GOP 需要换 wav2vec2 + 强制对齐，模型 300MB+，不在离线 App 的预算内。
 */
public final class Pronunciation {

    /** 一个词的评分。 */
    public static final class Word {
        public final String target;
        public String heard = "";
        public boolean correct;
        public float start;     // 秒
        public float end;
        /** 该词之前的停顿（秒），0 表示没有明显停顿。 */
        public float pauseBefore;

        Word(String t) {
            target = t;
        }
    }

    public static final class Result {
        public final List<Word> words = new ArrayList<>();
        public int score;              // 0-100
        public int correctCount;
        public int total;
        /** 语速：词/分钟。 */
        public int wpm;
        /** 停顿位置（词的索引）。 */
        public final List<Integer> pauses = new ArrayList<>();
        public String advice = "";

        public boolean ok() {
            return total > 0;
        }
    }

    private Pronunciation() {
    }

    /**
     * 对齐 + 评分。
     *
     * @param target 目标句
     * @param heard  识别结果（带时间戳）
     * @param duration 录音时长（秒）
     */
    public static Result score(String target, OfflineAsrEngine.TimedResult heard,
                               float duration) {
        Result r = new Result();
        if (target == null || target.trim().isEmpty()) return r;

        List<String> tgt = splitWords(target);
        r.total = tgt.size();
        if (r.total == 0) return r;

        // 把识别到的词按顺序铺开，用「最长公共子序列」思路做贪心对齐
        List<String> heardWords = new ArrayList<>();
        List<float[]> heardSpan = new ArrayList<>();
        for (OfflineAsrEngine.TimedWord w : heard.words) {
            heardWords.add(OfflineAsrEngine.norm(w.text));
            heardSpan.add(new float[]{w.start, w.end});
        }

        // 模型没给时间戳（Whisper 就是这样，全是 0）→ 按词数均分时长估算。
        // 这样至少能算出语速和「哪几个词漏了」，虽然停顿检测会不准。
        boolean noTiming = heardSpan.isEmpty();
        if (!noTiming) {
            noTiming = true;
            for (float[] sp : heardSpan) {
                if (sp[1] > sp[0] + 0.01f) {
                    noTiming = false;
                    break;
                }
            }
        }
        if (noTiming && !heardWords.isEmpty() && duration > 0) {
            float per = duration / heardWords.size();
            for (int i = 0; i < heardSpan.size(); i++) {
                heardSpan.set(i, new float[]{i * per, (i + 1) * per});
            }
        }

        int hi = 0;
        float lastEnd = 0f;
        for (int ti = 0; ti < tgt.size(); ti++) {
            String t = OfflineAsrEngine.norm(tgt.get(ti));
            Word w = new Word(tgt.get(ti));

            // 往后找 3 个窗口内匹配的词（容忍漏词/多词）
            int found = -1;
            for (int k = hi; k < Math.min(heardWords.size(), hi + 3); k++) {
                if (similar(heardWords.get(k), t)) {
                    found = k;
                    break;
                }
            }
            if (found >= 0) {
                w.correct = true;
                w.heard = heardWords.get(found);
                w.start = heardSpan.get(found)[0];
                w.end = heardSpan.get(found)[1];
                if (!noTiming && w.start - lastEnd > 0.5f) {
                    w.pauseBefore = w.start - lastEnd;
                    r.pauses.add(ti);
                }
                lastEnd = w.end;
                hi = found + 1;
                r.correctCount++;
            } else {
                w.correct = false;
                // 没匹配上：位置插在当前位置附近
                if (hi < heardWords.size()) {
                    w.heard = heardWords.get(hi);
                    w.start = heardSpan.get(hi)[0];
                    w.end = heardSpan.get(hi)[1];
                }
            }
            r.words.add(w);
        }

        r.score = Math.round(100f * r.correctCount / r.total);
        if (duration > 0) {
            r.wpm = Math.round(r.total / (duration / 60f));
        }
        r.advice = advice(r);
        return r;
    }

    private static String advice(Result r) {
        if (r.score >= 95) return "发音很准，节奏也对。";
        if (r.score >= 80) {
            if (!r.pauses.isEmpty()) return "大部分对，注意 " + r.pauses.size() + " 处停顿。";
            return "大部分对，再顺一遍就更好了。";
        }
        if (r.score >= 60) {
            return "有几个词没念对，先慢速跟一遍再录。";
        }
        if (r.wpm > 0 && r.wpm < 60) return "念得太慢，先熟悉整句再加速。";
        if (r.wpm > 200) return "念得太快，注意每个词都念清楚。";
        return "先听标准音，逐词跟读。";
    }

    /** 宽松匹配：完全相等，或编辑距离 ≤ 1（容忍识别误差）。 */
    private static boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        if (a.isEmpty() || b.isEmpty()) return false;
        if (Math.abs(a.length() - b.length()) > 2) return false;
        return edit(a, b) <= 1;
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

    private static List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c) || c == '\'') {
                cur.append(c);
            } else {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
