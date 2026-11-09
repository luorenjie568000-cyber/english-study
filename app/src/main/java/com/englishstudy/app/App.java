package com.englishstudy.app;

import android.app.Application;
import android.content.Context;

import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Scenes;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.voice.OfflineAsrEngine;
import com.englishstudy.app.voice.OfflineTtsEngine;
import com.englishstudy.app.voice.ModelRepo;

import java.io.File;
import java.io.PrintWriter;

/**
 * 全局入口：词典后台加载，生词本常驻内存。
 *
 * 自检开关：filesDir 下放 selftest.on 文件才跑全量自检，
 * 结果写 selftest.txt / voice-diag.txt，方便无界面验证。
 */
public class App extends Application {

    private static App sInst;

    public static App get() {
        return sInst;
    }

    public static Context ctx() {
        return sInst;
    }

    private boolean selfTestEnabled() {
        return new File(getFilesDir(), "selftest.on").exists();
    }

    // ---------------------------------------------------------------- 文本类自检

    private void selfTest() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("== writing ==\n");
            String sample = "Yesterday I go to a coffee shop near my home. I very like the quite place. "
                    + "Although it was crowded, but I found a seat. The waiter was very frienly. "
                    + "i will go there again.";
            com.englishstudy.app.engine.WritingChecker.Result r =
                    com.englishstudy.app.engine.WritingChecker.check(sample);
            sb.append("score=").append(r.score).append('\n');
            sb.append("verdict=").append(r.verdict).append('\n');
            sb.append("words=").append(r.words).append(" sentences=").append(r.sentences)
                    .append(" avg=").append(r.avgLen).append('\n');
            sb.append("issues=").append(r.issues.size()).append('\n');
            for (com.englishstudy.app.engine.WritingChecker.Issue it : r.issues) {
                sb.append(it.type).append('|').append(it.snippet).append('|')
                        .append(it.hint).append('\n');
            }

            // 干净文本不该被误报
            com.englishstudy.app.engine.WritingChecker.Result clean =
                    com.englishstudy.app.engine.WritingChecker.check(
                            "The weather is really nice today, so I walked to the park.");
            sb.append("clean.score=").append(clean.score)
                    .append(" clean.issues=").append(clean.issues.size()).append('\n');

            sb.append("== translate ==\n");
            com.englishstudy.app.data.Translate.load(this);
            sb.append("pairs=").append(com.englishstudy.app.data.Translate.pairCount()).append('\n');
            com.englishstudy.app.data.Translate.Result t1 =
                    com.englishstudy.app.data.Translate.translate(
                            "The best way to predict the future is to create it yourself.");
            sb.append("t1.exact=").append(t1.exact).append(" t1=").append(t1.translation).append('\n');

            sb.append("== dict ==\n");
            sb.append("size=").append(Dict.size()).append('\n');
            Dict.Entry e = Dict.lookup("gave");
            sb.append("gave->").append(e == null ? "null" : e.word + " ipa=" + e.displayIpa())
                    .append('\n');

            sb.append("== sentences ==\n");
            com.englishstudy.app.data.Sentences.load(this);
            sb.append("words=").append(com.englishstudy.app.data.Sentences.wordCount()).append('\n');
            sb.append("give.examples=")
                    .append(com.englishstudy.app.data.Sentences.of("give").size()).append('\n');

            sb.append("== scenes ==\n");
            int lines = 0;
            for (Scenes.Scene s : Scenes.all()) lines += s.lines.size();
            sb.append("scenes=").append(Scenes.all().size())
                    .append(" lines=").append(lines)
                    .append(" shadow_sets=").append(Scenes.SHADOW.length).append('\n');
            sb.append("shadow.first=").append(Scenes.SHADOW[0][1]).append('\n');

            sb.append("== scoring ==\n");
            String tgt = "The weather is really nice today.";
            sb.append("same=").append(OfflineAsrEngine.score(tgt, tgt)).append('\n');
            sb.append("near=").append(OfflineAsrEngine.score(tgt,
                    "the weather is really nice today")).append('\n');
            sb.append("half=").append(OfflineAsrEngine.score(tgt,
                    "the weather is nice")).append('\n');
            sb.append("junk=").append(OfflineAsrEngine.score(tgt, "banana")).append('\n');
        } catch (Throwable e) {
            sb.append("ERROR ").append(e.getClass().getName()).append(": ")
                    .append(e.getMessage()).append('\n');
        }
        write("selftest.txt", sb.toString());
    }

    // ---------------------------------------------------------------- 语音自检

    private void voiceSelfTest() {
        StringBuilder sb = new StringBuilder();
        try {
            boolean esp = ModelRepo.ensureEspeak(this);
            sb.append("espeak=").append(esp).append('\n');
            File espDir = ModelRepo.espeakDir(this);
            sb.append("espeak.files=").append(countFiles(espDir)).append('\n');

            for (ModelRepo.Pack p : ModelRepo.all()) {
                sb.append("pack.").append(p.id).append(".ready=")
                        .append(ModelRepo.ready(this, p)).append('\n');
            }

            OfflineTtsEngine.init(this);
            OfflineAsrEngine.init(this);

            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 60000) {
                if (OfflineTtsEngine.ready() || OfflineTtsEngine.unavailable()) break;
                Thread.sleep(300);
            }
            sb.append("tts.ready=").append(OfflineTtsEngine.ready())
                    .append(" tts.failed=").append(OfflineTtsEngine.unavailable()).append('\n');

            t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 60000) {
                if (OfflineAsrEngine.ready() || OfflineAsrEngine.unavailable()) break;
                Thread.sleep(300);
            }
            sb.append("asr.ready=").append(OfflineAsrEngine.ready())
                    .append(" asr.pack=").append(OfflineAsrEngine.loadedPack()).append('\n');

            // 语速自检：0.7 应该比 1.0 长，1.3 应该比 1.0 短
            String sp = "The weather is really nice today.";
            for (float r : new float[]{0.7f, 1.0f, 1.3f}) {
                float[] pcm = OfflineTtsEngine.probeSamplesAt(sp, r);
                sb.append("speed[").append(r).append("] samples=")
                        .append(pcm == null ? "null" : pcm.length).append('\n');
            }

            String wav = OfflineTtsEngine.probe("The weather is really nice today.");
            sb.append("tts.probe=").append(wav).append('\n');

            // 闭环：合成 → 回灌识别 → 打分。证明发音与听音两端都真在工作
            String[] phrases = {
                    "The weather is really nice today.",
                    "Can I have a cup of coffee, please?",
                    "I usually take the bus to work."
            };
            for (String ph : phrases) {
                float[] pcm = OfflineTtsEngine.probeSamples(ph);
                if (pcm == null) {
                    sb.append("loop[").append(ph).append("]=no-pcm\n");
                    continue;
                }
                String heard = OfflineAsrEngine.recognize(pcm, 16000);
                sb.append("loop[").append(ph).append("]=").append(heard)
                        .append(" score=").append(OfflineAsrEngine.score(ph, heard)).append('\n');

                // 逐词评分（带时间戳）。这一步证明 Pronunciation 引擎真的在工作
                OfflineAsrEngine.TimedResult timed = OfflineAsrEngine.recognizeTimed(pcm, 16000);
                com.englishstudy.app.engine.Pronunciation.Result pr =
                        com.englishstudy.app.engine.Pronunciation.score(
                                ph, timed, pcm.length / 16000f);
                sb.append("  timed.words=").append(timed.words.size())
                        .append(" pron.score=").append(pr.score)
                        .append(" wpm=").append(pr.wpm)
                        .append(" advice=").append(pr.advice).append('\n');
                for (com.englishstudy.app.engine.Pronunciation.Word tw : pr.words) {
                    sb.append("    ").append(tw.target)
                            .append(tw.correct ? " ok" : " miss(" + tw.heard + ")")
                            .append(String.format(java.util.Locale.US,
                                    " %.2fs-%.2fs", tw.start, tw.end))
                            .append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("ERROR ").append(e.getClass().getName()).append(": ")
                    .append(e.getMessage()).append('\n');
        }
        write("voice-diag.txt", sb.toString());
    }

    private int countFiles(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    private void write(String name, String content) {
        try {
            PrintWriter w = new PrintWriter(new File(getFilesDir(), name), "UTF-8");
            w.print(content);
            w.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        Vocab.init(this);
        final boolean st = selfTestEnabled();
        new Thread(() -> {
            Dict.load(this);
            com.englishstudy.app.engine.Pos.load(this);
            if (selfTestEnabled()) {
                com.englishstudy.app.engine.CheckerEval.runAndSave(this);
            }
            if (st) selfTest();
        }, "loader").start();
        if (st) new Thread(this::voiceSelfTest, "voice-diag").start();
        if (st) new Thread(() -> com.englishstudy.app.voice.LocalLlm.selfTest(this), "llm-diag").start();
        // 用户选了「本地优先」就把模型预热起来，第一次提问不用等加载
        if (com.englishstudy.app.data.Store.getBool("ai.local", false)) {
            com.englishstudy.app.voice.LocalLlm.warmup(this);
        }
    }
}
