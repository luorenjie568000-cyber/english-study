package com.englishstudy.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Scenes;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 口语：跟读打分 + 场景对话。
 *
 * 全部本地：朗读走 OfflineTtsEngine，识别走 OfflineAsrEngine（sherpa-onnx）。
 * 模型没下载时给出明确指引，不崩溃。
 */
public class SpeakView {

    private static final int REQ_MIC = 0x51;

    private final Activity a;
    private LinearLayout root;
    private int tab;    // 0 跟读 / 1 场景 / 2 对话
    private long recStart;              // 录音起始时刻，用于显示时长
    private String target = Scenes.SHADOW[0][1];
    private TextView targetTv;          // 练习句展示，选句时原地更新不重建页面
    private ScrollView scroll;

    // 场景态
    private Scenes.Scene scene;
    private int lineIdx;
    private final List<String[]> sceneLog = new ArrayList<>();  // {你, 命中率}
    private ChatView chat;   // 懒加载：字段初始化时 a 还没赋值
    private IpaView ipa;     // 同样懒加载
    private AudioPanel audio;
    private VideoPanel videoPanel;

    public SpeakView(Activity a) {
        this.a = a;
        this.tab = com.englishstudy.app.data.Store.getInt("speak.tab", 0);
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        scroll = sv;
        root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));
        render();
        sv.addView(root);
        return sv;
    }

    private void render() {
        root.removeAllViews();
        root.addView(U.bold(a, "口语", 24, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "本地发音 + 本地识别，念完立刻打分", 12,
                Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 14));

        LinearLayout tabs = U.row(a);
        tabs.addView(tabBtn("跟读", tab == 0, () -> {
            tab = 0;
            scene = null;
            saveTab();
            render();
        }), U.weight(1));
        tabs.addView(U.hspacer(a, 6));
        tabs.addView(tabBtn("场景", tab == 1, () -> {
            tab = 1;
            saveTab();
            render();
        }), U.weight(1));
        tabs.addView(U.hspacer(a, 6));
        tabs.addView(tabBtn("对话", tab == 2, () -> {
            tab = 2;
            saveTab();
            render();
        }), U.weight(1));
        tabs.addView(U.hspacer(a, 6));
        tabs.addView(tabBtn("音标", tab == 3, () -> {
            tab = 3;
            saveTab();
            render();
        }), U.weight(1));
        root.addView(tabs);
        root.addView(U.spacer(a, 14));

        if (tab == 0) shadowPanel();
        else if (tab == 1) scenePanel();
        else if (tab == 2) chatPanel();
        else ipaPanel();
    }

    /** 由 MainActivity 转发音频文件选择结果。 */
    public void onAudioFile(android.net.Uri uri) {
        if (audio == null) audio = new AudioPanel(a);
        audio.onAudioFile(uri);
        render();
    }

    /** 由 MainActivity 转发视频文件选择结果。 */
    public void onVideoFile(android.net.Uri uri) {
        if (videoPanel == null) videoPanel = new VideoPanel(a);
        videoPanel.onVideoFile(uri);
    }

    private void saveTab() {
        com.englishstudy.app.data.Store.putInt("speak.tab", tab);
    }

    private View tabBtn(String text, boolean active, Runnable onClick) {
        TextView t = U.bold(a, text, 14,
                Theme.c(a, active ? R.color.white : R.color.text_dim));
        t.setBackground(active ? U.primaryBg(a) : U.tileBg(a));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, U.px(a, 10), 0, U.px(a, 10));
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    /** 音频跟读：导入 mp3/wav，A/B 循环 + 录音对比。 */
    private void audioPanel() {
        if (audio == null) audio = new AudioPanel(a);
        root.addView(audio.build(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** 音标：复用 IpaView（44 个音素，点音标听单音）。 */
    private void ipaPanel() {
        if (ipa == null) {
            ipa = new IpaView(a);
            ipa.setOnAudio(new IpaView.OnAudioEntry() {
                @Override public void onOpenAudio() {
                    openAudio();
                }

                @Override public void onOpenVideo() {
                    openVideo();
                }
            });
        }
        root.addView(ipa.build(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** 视频跟读：弹窗打开（同样避免挤 tab）。 */
    private void openVideo() {
        if (videoPanel == null) videoPanel = new VideoPanel(a);
        LinearLayout box = U.col(a);
        box.setPadding(U.px(a, 4), U.px(a, 4), U.px(a, 4), U.px(a, 4));
        box.addView(videoPanel.build(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72)));
        new android.app.AlertDialog.Builder(a)
                .setTitle("视频跟读")
                .setView(box)
                .setPositiveButton("关闭", (d, w) -> videoPanel.release())
                .setOnDismissListener(d -> videoPanel.release())
                .show();
    }

    /** 音频跟读：从音标页底部的按钮进入（用弹窗，避免再挤一个 tab）。 */
    private void openAudio() {
        if (audio == null) audio = new AudioPanel(a);
        LinearLayout box = U.col(a);
        box.setPadding(U.px(a, 4), U.px(a, 4), U.px(a, 4), U.px(a, 4));
        box.addView(audio.build(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.7)));
        new android.app.AlertDialog.Builder(a)
                .setTitle("音频跟读")
                .setView(box)
                .setPositiveButton("关闭", (d, w) -> audio.stopPlayer())
                .setOnDismissListener(d -> audio.stopPlayer())
                .show();
    }

    /** 对话：直接复用 ChatView 的界面（学伴 Agent）。 */
    private void chatPanel() {
        if (chat == null) chat = new ChatView(a);
        View v = chat.build();
        root.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    // ------------------------------------------------------------ 跟读打分

    private void shadowPanel() {
        LinearLayout pick = U.card(a);
        pick.addView(U.bold(a, "选一句练", 13, Theme.c(a, R.color.text_dim)));
        for (String[] set : Scenes.SHADOW) {
            pick.addView(U.spacer(a, 8));
            LinearLayout head = U.row(a);
            TextView lv = U.tv(a, set[0], 10, Theme.c(a, R.color.primary));
            lv.setPadding(U.px(a, 7), U.px(a, 2), U.px(a, 7), U.px(a, 2));
            lv.setBackgroundResource(R.drawable.bg_pill_soft);
            head.addView(lv);
            pick.addView(head);
            for (int i = 1; i < set.length; i++) {
                final String s = set[i];
                TextView t = U.tv(a, s, 13, Theme.c(a, R.color.text));
                t.setPadding(U.px(a, 2), U.px(a, 8), U.px(a, 2), U.px(a, 8));
                t.setOnClickListener(v -> {
                    target = s;
                    if (targetTv != null) targetTv.setText(s);
                    if (scroll != null) scroll.smoothScrollTo(0, U.px(a, 560));
                });
                pick.addView(t);
            }
        }
        root.addView(pick);

        root.addView(U.spacer(a, 12));

        LinearLayout box = U.card(a);
        box.addView(U.bold(a, "练习句", 13, Theme.c(a, R.color.text_dim)));
        box.addView(U.spacer(a, 8));
        TextView tgt = U.tv(a, target, 17, Theme.c(a, R.color.text));
        tgt.setLineSpacing(U.dp(a, 5), 1f);
        box.addView(tgt);
        targetTv = tgt;

        EditText custom = new EditText(a);
        custom.setHint("也可以粘贴任意一句英文…");
        custom.setHintTextColor(Theme.c(a, R.color.text_dim2));
        custom.setTextColor(Theme.c(a, R.color.text));
        custom.setTextSize(13);
        custom.setBackground(null);
        custom.setMinLines(1);
        box.addView(custom);
        TextView use = U.tv(a, "用这句 →", 12, Theme.c(a, R.color.primary));
        use.setPadding(0, U.px(a, 4), 0, U.px(a, 4));
        use.setOnClickListener(v -> {
            String s = custom.getText().toString().trim();
            if (!s.isEmpty()) {
                target = s;
                if (targetTv != null) targetTv.setText(s);
            }
        });
        box.addView(use);

        box.addView(U.spacer(a, 10));
        LinearLayout btns = U.row(a);
        TextView normal = U.ghostButton(a, "🔊 正常", Theme.c(a, R.color.text));
        normal.setOnClickListener(v -> Tts.say(a, target, 1.0f));
        btns.addView(normal, U.weight(1));
        btns.addView(U.hspacer(a, 8));
        TextView slow = U.ghostButton(a, "🐢 慢速", Theme.c(a, R.color.text));
        slow.setOnClickListener(v -> Tts.say(a, target, 0.7f));
        btns.addView(slow, U.weight(1));
        box.addView(btns);

        box.addView(U.spacer(a, 8));
        TextView mic = U.primaryButton(a, "🎤 点一下，然后念");
        box.addView(mic);

        TextView status = U.tv(a, "", 12, Theme.c(a, R.color.text_dim));
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, U.px(a, 8), 0, 0);
        box.addView(status);

        LinearLayout result = U.col(a);
        box.addView(result);

        mic.setOnClickListener(v -> {
            if (!ensureMic(status)) return;
            if (!OfflineAsrEngine.ready()) {
                status.setText("识别模型未就绪：去「我的 → 离线语音」下载一个");
                return;
            }
            if (OfflineAsrEngine.isRecording()) {
                OfflineAsrEngine.stopListening();
                return;
            }
            recStart = System.currentTimeMillis();
            status.setText("正在录音 0s  ▯▯▯▯▯▯▯▯▯▯");
            mic.setText("⏹ 停止并识别");
            result.removeAllViews();
            OfflineAsrEngine.listen(a.getApplicationContext(), new OfflineAsrEngine.Callback() {
                @Override public void onStart() { }

                @Override public void onLevel(float level) {
                    long sec = (System.currentTimeMillis() - recStart) / 1000;
                    int bars = (int) Math.min(10, level * 14);
                    StringBuilder sb = new StringBuilder("正在录音 ");
                    sb.append(sec).append("s  ");
                    for (int i = 0; i < 10; i++) sb.append(i < bars ? '▮' : '▯');
                    status.setText(sb.toString());
                }

                @Override public void onResult(String text) {
                    mic.setText("🎤 点一下，然后念");
                    if (text == null || text.isEmpty()) {
                        status.setText("没听清，再来一次");
                        return;
                    }
                    int sc = OfflineAsrEngine.score(target, text);
                    status.setText("识别：" + text);
                    showShadowScore(result, sc, target, text);
                    dump("shadow", sc, target, text);
                }

                @Override public void onError(String msg) {
                    mic.setText("🎤 点一下，然后念");
                    status.setText("出错：" + msg);
                }
            });
        });
        root.addView(box);

        root.addView(U.spacer(a, 12));
        LinearLayout tip = U.card(a);
        tip.addView(U.bold(a, "怎么用", 13, Theme.c(a, R.color.text_dim)));
        tip.addView(U.spacer(a, 6));
        TextView how = U.tv(a, "先点 🔊 听一遍，再点 🎤 念。念完自动打分，念错的词会标红。"
                + "建议先慢速跟两遍，再正常速度念一次。识别是纯本地的，断网也能用。",
                12, Theme.c(a, R.color.text_dim2));
        how.setLineSpacing(U.dp(a, 4), 1f);
        tip.addView(how);
        root.addView(tip);
    }

    private void showShadowScore(LinearLayout box, int score, String tgt, String heard) {
        box.removeAllViews();
        box.addView(U.spacer(a, 12));

        LinearLayout row = U.row(a);
        RingView ring = new RingView(a);
        ring.setProgress(score / 100f, String.valueOf(score), "分");
        int size = U.px(a, 76);
        row.addView(ring, new LinearLayout.LayoutParams(size, size));
        row.addView(U.hspacer(a, 14));

        LinearLayout info = U.col(a);
        String verdict = score >= 90 ? "非常准" : score >= 75 ? "不错"
                : score >= 55 ? "还能更清楚" : "再慢一点";
        info.addView(U.bold(a, verdict, 15,
                a.getColor(score >= 90 ? R.color.ok : score >= 55 ? R.color.warn : R.color.danger)));
        info.addView(U.spacer(a, 4));
        info.addView(U.tv(a, "词命中 " + hit(tgt, heard) + " · 字符相似 "
                + charSim(tgt, heard) + "%", 11, Theme.c(a, R.color.text_dim2)));
        row.addView(info, U.weight(1));
        box.addView(row);

        box.addView(U.spacer(a, 10));
        box.addView(U.tv(a, "目标句（错的词标红）", 11, Theme.c(a, R.color.text_dim2)));
        TextView marked = new TextView(a);
        marked.setTextSize(15);
        marked.setTextColor(Theme.c(a, R.color.text));
        marked.setLineSpacing(U.dp(a, 5), 1f);
        marked.setText(markMissed(tgt, heard));
        box.addView(marked);
    }

    /** 把目标句里没念对的词标红。 */
    private CharSequence markMissed(String tgt, String heard) {
        SpannableString sp = new SpannableString(tgt);
        String h = " " + OfflineAsrEngine.norm(heard) + " ";
        int i = 0;
        while (i < tgt.length()) {
            char c = tgt.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            int j = i;
            while (j < tgt.length() && (Character.isLetter(tgt.charAt(j)) || tgt.charAt(j) == '\'')) j++;
            String w = tgt.substring(i, j).toLowerCase(Locale.US);
            if (!h.contains(" " + w + " ") && !h.contains(w)) {
                sp.setSpan(new BackgroundColorSpan(Theme.c(a, R.color.danger_soft)),
                        i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new StyleSpan(Typeface.BOLD), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = j;
        }
        return sp;
    }

    private int hit(String tgt, String heard) {
        String[] aw = OfflineAsrEngine.norm(tgt).split(" ");
        String[] bw = OfflineAsrEngine.norm(heard).split(" ");
        int n = 0;
        for (String w : aw) {
            if (w.isEmpty()) continue;
            for (String x : bw) {
                if (x.equals(w)) {
                    n++;
                    break;
                }
            }
        }
        int total = 0;
        for (String w : aw) if (!w.isEmpty()) total++;
        return total == 0 ? 0 : n * 100 / total;
    }

    private int charSim(String tgt, String heard) {
        return OfflineAsrEngine.score(tgt, heard);
    }

    // ------------------------------------------------------------ 场景对话

    private void scenePanel() {
        if (scene == null) {
            LinearLayout card = U.card(a);
            card.addView(U.bold(a, "选一个场景", 13, Theme.c(a, R.color.text_dim)));
            card.addView(U.spacer(a, 6));
            for (Scenes.Scene s : Scenes.all()) {
                LinearLayout row = U.row(a);
                row.setPadding(0, U.px(a, 12), 0, U.px(a, 12));
                LinearLayout col = U.col(a);
                LinearLayout head = U.row(a);
                head.addView(U.tv(a, s.title, 15, Theme.c(a, R.color.text)));
                head.addView(U.hspacer(a, 8));
                TextView lv = U.tv(a, s.level, 10, Theme.c(a, R.color.accent));
                lv.setPadding(U.px(a, 6), U.px(a, 2), U.px(a, 6), U.px(a, 2));
                lv.setBackgroundResource(R.drawable.bg_pill_soft);
                head.addView(lv);
                col.addView(head);
                TextView g = U.tv(a, s.goal, 12, Theme.c(a, R.color.text_dim2));
                g.setPadding(0, U.px(a, 3), 0, 0);
                col.addView(g);
                row.addView(col, U.weight(1));
                row.addView(U.tv(a, " ›", 16, Theme.c(a, R.color.text_dim2)));
                final Scenes.Scene ss = s;
                row.setOnClickListener(v -> {
                    scene = ss;
                    lineIdx = 0;
                    sceneLog.clear();
                    render();
                });
                card.addView(row);
                if (!s.id.equals("smalltalk")) card.addView(U.divider(a, Theme.c(a, R.color.line)));
            }
            root.addView(card);
            return;
        }

        // 场景内
        LinearLayout head = U.row(a);
        TextView back = U.tv(a, "‹ 全部场景", 13, Theme.c(a, R.color.primary));
        back.setOnClickListener(v -> {
            scene = null;
            render();
        });
        head.addView(back, U.weight(1));
        head.addView(U.tv(a, scene.level + " · " + (lineIdx + 1) + "/" + scene.lines.size(),
                11, Theme.c(a, R.color.text_dim2)));
        root.addView(head);
        root.addView(U.spacer(a, 10));

        // 已完成的对白
        for (String[] e : sceneLog) {
            LinearLayout card = U.card(a);
            card.addView(U.tv(a, "你", 11, Theme.c(a, R.color.text_dim2)));
            TextView you = U.tv(a, e[0], 14, Theme.c(a, R.color.text));
            you.setLineSpacing(U.dp(a, 4), 1f);
            card.addView(you);
            card.addView(U.spacer(a, 4));
            card.addView(U.tv(a, "命中 " + e[1], 11, Theme.c(a, R.color.text_dim2)));
            root.addView(card);
            root.addView(U.spacer(a, 8));
        }

        if (lineIdx < scene.lines.size()) {
            final Scenes.Line ln = scene.lines.get(lineIdx);

            LinearLayout npc = U.card(a);
            npc.addView(U.bold(a, "对方", 11, Theme.c(a, R.color.text_dim2)));
            npc.addView(U.spacer(a, 6));
            TextView line = U.tv(a, ln.npc, 16, Theme.c(a, R.color.text));
            line.setLineSpacing(U.dp(a, 5), 1f);
            npc.addView(line);
            npc.addView(U.spacer(a, 8));
            TextView play = U.tv(a, "🔊 听一遍", 12, Theme.c(a, R.color.primary));
            play.setPadding(0, U.px(a, 4), 0, U.px(a, 4));
            play.setOnClickListener(v -> Tts.say(a, ln.npc, 0.9f));
            npc.addView(play);
            root.addView(npc);

            root.addView(U.spacer(a, 10));
            LinearLayout say = U.card(a);
            say.addView(U.bold(a, "该你说", 11, Theme.c(a, R.color.text_dim2)));
            say.addView(U.spacer(a, 6));
            TextView hint = U.tv(a, "💡 " + ln.hint, 13, Theme.c(a, R.color.warn));
            hint.setLineSpacing(U.dp(a, 4), 1f);
            say.addView(hint);

            TextView status = U.tv(a, "", 12, Theme.c(a, R.color.text_dim));
            status.setGravity(Gravity.CENTER);
            status.setPadding(0, U.px(a, 8), 0, 0);

            TextView mic = U.primaryButton(a, "🎤 回答");
            say.addView(U.spacer(a, 10));
            say.addView(mic);
            say.addView(status);

            TextView skip = U.tv(a, "跳过这句 ›", 12, Theme.c(a, R.color.text_dim2));
            skip.setGravity(Gravity.CENTER);
            skip.setPadding(0, U.px(a, 10), 0, 0);
            skip.setOnClickListener(v -> {
                lineIdx++;
                render();
            });
            say.addView(skip);

            mic.setOnClickListener(v -> {
                if (!ensureMic(status)) return;
                if (!OfflineAsrEngine.ready()) {
                    status.setText("识别模型未就绪：去「我的 → 离线语音」下载");
                    return;
                }
                if (OfflineAsrEngine.isRecording()) {
                    OfflineAsrEngine.stopListening();
                    return;
                }
                recStart = System.currentTimeMillis();
                status.setText("正在录音 0s  ▯▯▯▯▯▯▯▯▯▯");
                mic.setText("⏹ 停止并识别");
                OfflineAsrEngine.listen(a.getApplicationContext(), new OfflineAsrEngine.Callback() {
                    @Override public void onStart() { }

                    @Override public void onLevel(float level) { }

                    @Override public void onResult(String text) {
                        mic.setText("🎤 回答");
                        if (text == null || text.isEmpty()) {
                            status.setText("没听清，再来一次");
                            return;
                        }
                        String low = text.toLowerCase(Locale.US);
                        int ok = 0, need = 0;
                        StringBuilder miss = new StringBuilder();
                        for (String k : ln.keys) {
                            need++;
                            if (low.contains(k.toLowerCase(Locale.US))) ok++;
                            else if (miss.length() == 0) miss.append(k);
                        }
                        String rate = need == 0 ? "—" : ok + "/" + need + " 关键词";
                        sceneLog.add(new String[]{text, rate});
                        dump("scene:" + scene.id, need == 0 ? 100 : ok * 100 / need,
                                ln.npc, text);
                        lineIdx++;
                        render();
                        if (lineIdx >= scene.lines.size()) finishScene();
                    }

                    @Override public void onError(String msg) {
                        mic.setText("🎤 回答");
                        status.setText("出错：" + msg);
                    }
                });
            });
            root.addView(say);
        }

        root.addView(U.spacer(a, 12));
        LinearLayout ph = U.card(a);
        ph.addView(U.bold(a, "本场景常用句", 13, Theme.c(a, R.color.text_dim)));
        for (String p : scene.phrases) {
            TextView t = U.tv(a, "· " + p, 13, Theme.c(a, R.color.text));
            t.setPadding(0, U.px(a, 8), 0, 0);
            t.setOnClickListener(v -> Tts.say(a, p, 0.9f));
            ph.addView(t);
        }
        root.addView(ph);
    }

    private void finishScene() {
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, "这一轮走完了", 15, Theme.c(a, R.color.ok)));
        card.addView(U.spacer(a, 6));
        card.addView(U.tv(a, "回了 " + sceneLog.size() + " 句。再来一轮或者换场景。",
                12, Theme.c(a, R.color.text_dim2)));
        card.addView(U.spacer(a, 10));
        TextView again = U.primaryButton(a, "再来一轮");
        again.setOnClickListener(v -> {
            lineIdx = 0;
            sceneLog.clear();
            render();
        });
        card.addView(again);
        root.addView(U.spacer(a, 12));
        root.addView(card);
    }

    // ------------------------------------------------------------ 工具

    private boolean ensureMic(TextView status) {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        if (status != null) status.setText("请先允许麦克风权限，再点一次");
        return false;
    }

    /** 把一次练习结果写进 files/speak-diag.txt，便于无界面校验。 */
    private void dump(String kind, int score, String target, String heard) {
        try {
            java.io.File f = new java.io.File(a.getFilesDir(), "speak-diag.txt");
            boolean fresh = !f.exists() || f.length() == 0;
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.FileWriter(f, true));
            if (fresh) w.println("# kind|score|target|heard");
            w.println(kind + "|" + score + "|" + target + "|" + heard);
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
