package com.englishstudy.app;

import android.app.Application;
import android.content.Context;

import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Vocab;

/** 全局入口：词典后台加载，生词本常驻内存。 */
public class App extends Application {

    private static App sInst;

    public static App get() {
        return sInst;
    }

    public static Context ctx() {
        return sInst;
    }

    /** 启动自检：跑一遍批改与翻译，把结果写文件，便于无 UI 验证。 */
    private void selfTest() {
        try {
            String sample = "Yesterday I go to a coffee shop near my home. I very like the quite place. "
                    + "Although it was crowded, but I found a seat. The waiter was very frienly. "
                    + "i will go there again.";
            com.englishstudy.app.engine.WritingChecker.Result r =
                    com.englishstudy.app.engine.WritingChecker.check(sample);

            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.File(getFilesDir(), "selftest.txt"), "UTF-8");
            w.println("== writing ==");
            w.println("score=" + r.score);
            w.println("verdict=" + r.verdict);
            w.println("words=" + r.words + " sentences=" + r.sentences + " avg=" + r.avgLen);
            w.println("issues=" + r.issues.size());
            for (com.englishstudy.app.engine.WritingChecker.Issue it : r.issues) {
                w.println(it.type + "|" + it.snippet + "|" + it.hint);
            }

            com.englishstudy.app.data.Translate.load(this);
            w.println("== translate ==");
            w.println("pairs=" + com.englishstudy.app.data.Translate.pairCount());
            com.englishstudy.app.data.Translate.Result t1 =
                    com.englishstudy.app.data.Translate.translate(
                            "The best way to predict the future is to create it yourself.");
            w.println("t1.exact=" + t1.exact + " t1=" + t1.translation);
            com.englishstudy.app.data.Translate.Result t2 =
                    com.englishstudy.app.data.Translate.translate(
                            "My neighbour bought a curious gadget yesterday.");
            w.println("t2.hasTranslation=" + t2.hasTranslation()
                    + " t2.words=" + t2.words.size());
            StringBuilder sb = new StringBuilder();
            for (com.englishstudy.app.data.Translate.Word wd : t2.words) {
                sb.append(wd.text).append(':').append(wd.pos).append('=').append(wd.def).append("  ");
            }
            w.println("t2.detail=" + sb);

            w.println("== dict ==");
            w.println("size=" + com.englishstudy.app.data.Dict.size());
            com.englishstudy.app.data.Dict.Entry e =
                    com.englishstudy.app.data.Dict.lookup("gave");
            w.println("gave->" + (e == null ? "null" : e.word + " ipa=" + e.displayIpa()));

            com.englishstudy.app.data.Sentences.load(this);
            w.println("== sentences ==");
            w.println("words=" + com.englishstudy.app.data.Sentences.wordCount());
            java.util.List<String> ex = com.englishstudy.app.data.Sentences.of("give");
            w.println("give.examples=" + ex.size());
            for (String x : ex) w.println("  " + x);

            w.close();
        } catch (Throwable e) {
            try {
                java.io.PrintWriter w = new java.io.PrintWriter(
                        new java.io.File(getFilesDir(), "selftest.txt"), "UTF-8");
                w.println("ERROR " + e.getClass().getName() + ": " + e.getMessage());
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInst = this;
        Vocab.init(this);
        new Thread(() -> {
            Dict.load(this);
            selfTest();
        }, "loader").start();
    }
}
