package com.englishstudy.app.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批改引擎自测（在 App 内部跑，输出干净的评估报告）。
 *
 * 为什么要在 App 里跑：
 *   之前在外部用 adb 点按钮 + 读诊断文件，结果被 CLI 的
 *   stdout/combined 双份输出污染，同一个问题被统计两次。
 *   引擎正确率评估必须可信，否则改了半天不知道有没有变好。
 *
 * 每条用例：原文 / 应报出的片段 / 绝不能报的片段。
 * 统计：命中(tp) 漏报(fn) 误报(fp) → 精确率、召回率。
 */
public final class CheckerEval {

    private CheckerEval() {
    }

    /** 一条用例。 */
    private static final class Case {
        final String text;
        final String[] expect;
        final String[] forbid;

        Case(String t, String[] e, String[] f) {
            text = t;
            expect = e;
            forbid = f;
        }
    }

    private static final List<Case> CASES = new ArrayList<>();

    static {
        add("A lot of people ared Asking about jailbreak progress.",
                new String[]{"ared", "Asking"}, new String[]{"jailbreak"});
        add("No major news yet.", new String[]{}, new String[]{});
        add("I am still working on stabilizing kernel read/write.",
                new String[]{}, new String[]{"kernel"});
        add("I still use mine team.", new String[]{"mine"}, new String[]{"team"});
        add("The students is very happy.", new String[]{"is"}, new String[]{});
        add("I very like this movie.", new String[]{"very like"}, new String[]{});
        add("He bought an university book.", new String[]{"an"}, new String[]{});
        add("This is more better than that.",
                new String[]{"more better"}, new String[]{});
        add("I am boring in the afternoon.", new String[]{"boring"}, new String[]{});
        add("We discuss about the problem.", new String[]{"about"}, new String[]{});
        add("My team is working on sptm bypass.",
                new String[]{}, new String[]{"sptm", "bypass", "my"});
        add("The kernel read/write is stable now.",
                new String[]{}, new String[]{"kernel"});
        add("I will go to the store tomorrow.", new String[]{}, new String[]{});
        add("An hour ago I saw an elephant.", new String[]{}, new String[]{"hour"});
        add("People is waiting outside.", new String[]{"is"}, new String[]{});
        add("I am interesting in this topic.",
                new String[]{"interesting"}, new String[]{});
        add("Calif's primitives are not public yet.", new String[]{}, new String[]{"calif"});
        add("They was very tired.", new String[]{"was"}, new String[]{});
        add("The dog run in the park.", new String[]{"run"}, new String[]{});
        add("I have went to Beijing last year.",
                new String[]{}, new String[]{});
        add("She don't know the answer.", new String[]{"don't"}, new String[]{});
        add("There is many books on the desk.", new String[]{"is"}, new String[]{});
        add("He is a honest man.", new String[]{"a"}, new String[]{});
        add("We are go to school.", new String[]{}, new String[]{});
        add("The weather is really nice today.", new String[]{}, new String[]{});
        // ---- 以下全是正确英语，用来验证「不误报」----
        add("I have been working on this project for three months.",
                new String[]{}, new String[]{});
        add("She asked me whether I could help her with the kernel patch.",
                new String[]{}, new String[]{});
        add("The exploit relies on a use-after-free in the driver.",
                new String[]{}, new String[]{});
        add("We are going to release the new version next week.",
                new String[]{}, new String[]{});
        add("If it rains tomorrow, we will stay at home.",
                new String[]{}, new String[]{});
        add("He has already finished his homework.", new String[]{}, new String[]{});
        add("The server crashed because of a memory leak.",
                new String[]{}, new String[]{});
        add("I would rather stay home than go out tonight.",
                new String[]{}, new String[]{});
        add("Neither of them knew the answer.", new String[]{}, new String[]{});
        add("The results were better than we had expected.",
                new String[]{}, new String[]{});
        add("This is the book that I told you about.",
                new String[]{}, new String[]{});
        add("She suggested that we should leave early.",
                new String[]{}, new String[]{});
        add("Despite the heavy rain, the match continued.",
                new String[]{}, new String[]{});
        add("I am used to getting up early now.",
                new String[]{}, new String[]{});
        add("He is the only person who can solve this.",
                new String[]{}, new String[]{});
        add("The company was founded in 1998 by two engineers.",
                new String[]{}, new String[]{});
        add("I look forward to hearing from you soon.",
                new String[]{}, new String[]{});
        add("There are several ways to approach this problem.",
                new String[]{}, new String[]{});
    }

    private static void add(String t, String[] e, String[] f) {
        CASES.add(new Case(t, e, f));
    }

    /**
     * 跑全部用例，返回报告文本。
     *
     * @param verbose true 时逐条列出，false 只给汇总
     */
    public static String run(Context c, boolean verbose) {
        if (!Pos.ready()) Pos.load(c);

        StringBuilder sb = new StringBuilder();
        int tp = 0, fn = 0, fp = 0;

        for (int i = 0; i < CASES.size(); i++) {
            Case cs = CASES.get(i);
            WritingChecker.Result r = WritingChecker.check(cs.text);

            List<String> got = new ArrayList<>();
            for (WritingChecker.Issue it : r.issues) {
                if (it.style) continue;      // 风格建议不算错误，不参与评估
                got.add(it.snippet.toLowerCase(java.util.Locale.US));
            }

            List<String> missed = new ArrayList<>();
            for (String e : cs.expect) {
                boolean hit = false;
                for (String g : got) {
                    if (g.contains(e.toLowerCase(java.util.Locale.US))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) missed.add(e);
            }

            List<String> wrong = new ArrayList<>();
            for (String g : got) {
                for (String f : cs.forbid) {
                    if (g.contains(f.toLowerCase(java.util.Locale.US))) {
                        wrong.add(g);
                        break;
                    }
                }
            }

            tp += cs.expect.length - missed.size();
            fn += missed.size();
            fp += wrong.size();

            if (verbose) {
                sb.append(i + 1).append(". ").append(cs.text).append('\n');
                sb.append("   实报: ").append(got).append('\n');
                if (!missed.isEmpty()) sb.append("   漏报: ").append(missed).append('\n');
                if (!wrong.isEmpty()) sb.append("   误报: ").append(wrong).append('\n');
            }
        }

        int prec = (tp + fp) == 0 ? 100 : tp * 100 / (tp + fp);
        int rec = (tp + fn) == 0 ? 100 : tp * 100 / (tp + fn);
        sb.append("\n命中 ").append(tp)
                .append(" · 漏报 ").append(fn)
                .append(" · 误报 ").append(fp).append('\n');
        sb.append("精确率 ").append(prec).append("%  召回率 ").append(rec).append("%\n");
        return sb.toString();
    }

    /** 用例总数（供界面展示）。 */
    public static int caseCount() {
        return CASES.size();
    }

    /** 跑评估并写入 files/checker-eval.txt。 */
    public static void runAndSave(Context c) {
        try {
            String report = run(c, true);
            File f = new File(c.getFilesDir(), "checker-eval.txt");
            PrintWriter w = new PrintWriter(f, "UTF-8");
            w.print(report);
            w.close();
            Log.i("CheckerEval", "saved " + f.getAbsolutePath());
        } catch (Throwable e) {
            Log.w("CheckerEval", "failed", e);
        }
    }
}
