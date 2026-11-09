package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.AudioClip;
import com.englishstudy.app.engine.Pronunciation;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.List;
import java.util.Locale;

/**
 * 音频跟读（v6.0 重写）。
 *
 * v5.x 的问题：录完只显示「识别到 XXX」，既没有对照评分，也不能回放自己念的，
 * 练了不知道对不对。这次补齐三件事：
 *   1. 与目标句逐词对齐打分（对/漏/多）+ 语速 + 停顿
 *   2. 录下来的音能回放，和原声交替听
 *   3. 自动把整段音频切成句子，点哪句练哪句（不用手动 A/B）
 */
public class AudioPanel {

    private final Activity a;
    private LinearLayout listBox;
    private LinearLayout playerBox;

    private AudioClip.Item current;
    private MediaPlayer player;

    private int loopStartMs = -1;
    private int loopEndMs = -1;

    /** 自动切出的句子（缓存在 AudioClip 里）。 */
    private List<AudioClip.Sent> sents;
    private int sentIdx = -1;

    /** 上一次录音（用于回放自己念的）。 */
    private String lastRecordPath;
    private MediaPlayer lastPlayer;

    public AudioPanel(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "音频跟读", 20, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "导入一段音频 → 自动切句 → 点一句听，再录一遍跟读打分",
                12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 12));

        TextView add = U.primaryButton(a, "＋ 导入音频（mp3 / wav / m4a）");
        add.setOnClickListener(v -> ((com.englishstudy.app.MainActivity) a).pickFile(0xA1));
        root.addView(add);

        root.addView(U.spacer(a, 14));
        playerBox = U.col(a);
        root.addView(playerBox);

        root.addView(U.spacer(a, 14));
        listBox = U.col(a);
        root.addView(listBox);

        renderList();
        sv.addView(root);
        return sv;
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        List<AudioClip.Item> list = AudioClip.all(a);
        if (list.isEmpty()) {
            listBox.addView(U.tv(a, "还没有音频。点上面导入一段。", 13,
                    Theme.c(a, R.color.text_dim)));
            return;
        }
        for (AudioClip.Item it : list) {
            LinearLayout card = U.card(a);
            card.addView(U.bold(a, it.title, 15, Theme.c(a, R.color.text)));
            card.addView(U.spacer(a, 4));
            String sub = String.format(Locale.US, "%.1f 分钟", it.durationMs / 60000f);
            if (it.sents != null && !it.sents.isEmpty()) {
                sub += " · " + it.sents.size() + " 句";
            }
            card.addView(U.tv(a, sub, 11, Theme.c(a, R.color.text_dim2)));
            card.addView(U.spacer(a, 8));

            LinearLayout acts = U.row(a);
            TextView play = U.ghostButton(a, "▶ 打开", Theme.c(a, R.color.primary));
            play.setOnClickListener(v -> openPlayer(it));
            acts.addView(play, U.weight(1));
            acts.addView(U.hspacer(a, 8));
            TextView del = U.ghostButton(a, "删除", Theme.c(a, R.color.danger));
            del.setOnClickListener(v -> confirmDelete(it));
            acts.addView(del, U.weight(1));
            card.addView(acts);

            card.setOnLongClickListener(v -> {
                confirmDelete(it);
                return true;
            });

            listBox.addView(card);
            listBox.addView(U.spacer(a, 10));
        }
    }

    private void confirmDelete(AudioClip.Item it) {
        new AlertDialog.Builder(a)
                .setTitle("删除这段音频？")
                .setMessage(it.title)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    stopPlayer();
                    AudioClip.remove(a, it);
                    if (current == it) current = null;
                    renderList();
                    renderPlayer();
                }).show();
    }

    /** 由 MainActivity 转发文件选择结果。 */
    public void onAudioFile(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            String name = null;
            try {
                android.database.Cursor cur = a.getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null);
                if (cur != null) {
                    if (cur.moveToFirst()) name = cur.getString(0);
                    cur.close();
                }
            } catch (Throwable ignored) {
            }
            if (name == null) name = uri.getLastPathSegment();
            if (name != null) {
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
            }

            AudioClip.Item it = AudioClip.importAudio(a, uri, name);
            a.runOnUiThread(() -> {
                if (it == null) {
                    android.widget.Toast.makeText(a, "导入失败，换个格式试试",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                current = it;
                renderList();
                renderPlayer();
            });
        }, "audio-import").start();
    }

    // ------------------------------------------------------------ 播放器

    private void openPlayer(AudioClip.Item it) {
        current = it;
        loopStartMs = -1;
        loopEndMs = -1;
        sentIdx = -1;
        sents = it.sents;
        stopPlayer();
        renderPlayer();
    }

    private void renderPlayer() {
        if (playerBox == null) return;
        playerBox.removeAllViews();
        if (current == null) return;

        LinearLayout card = U.card(a);
        card.addView(U.bold(a, current.title, 16, Theme.c(a, R.color.text)));
        card.addView(U.spacer(a, 10));

        final TextView pos = U.tv(a, "00:00 / " + fmt(current.durationMs), 12,
                Theme.c(a, R.color.text_dim));
        card.addView(pos);

        SeekBar bar = new SeekBar(a);
        bar.setMax(Math.max(1, (int) current.durationMs));
        card.addView(bar);

        // ---- 句子列表（自动切句） ----
        if (sents == null || sents.isEmpty()) {
            LinearLayout tip = U.row(a);
            tip.setPadding(0, U.px(a, 10), 0, 0);
            tip.addView(U.tv(a, "还没切句 —— 切成句子后可以一句一句练", 11,
                    Theme.c(a, R.color.text_dim2)), U.weight(1));
            TextView cut = U.tv(a, "切句", 11, Theme.c(a, R.color.primary));
            cut.setOnClickListener(v -> doSegment());
            tip.addView(cut);
            card.addView(tip);
        } else {
            card.addView(U.divider(a, Theme.c(a, R.color.line)));
            card.addView(U.spacer(a, 6));
            LinearLayout head = U.row(a);
            head.addView(U.bold(a, "句子 · " + sents.size(), 12,
                    Theme.c(a, R.color.text_dim)), U.weight(1));
            TextView re = U.tv(a, "重新切", 11, Theme.c(a, R.color.text_dim2));
            re.setOnClickListener(v -> doSegment());
            head.addView(re);
            card.addView(head);
            card.addView(U.spacer(a, 6));

            // 最多显示到 40 句，避免一屏太长
            int show = Math.min(sents.size(), 40);
            for (int i = 0; i < show; i++) {
                card.addView(sentRow(i));
            }
            if (sents.size() > show) {
                card.addView(U.tv(a, "（还有 " + (sents.size() - show)
                        + " 句，完整列表在「媒体」页可看）", 10,
                        Theme.c(a, R.color.text_dim2)));
            }
        }

        card.addView(U.spacer(a, 12));

        // ---- A/B 循环（保留，手动精听用） ----
        LinearLayout loops = U.row(a);
        TextView setA = U.ghostButton(a, "A 点", Theme.c(a, R.color.accent));
        TextView setB = U.ghostButton(a, "B 点", Theme.c(a, R.color.accent));
        TextView clear = U.ghostButton(a, "清除", Theme.c(a, R.color.text_dim));
        final TextView loopInfo = U.tv(a, "未设置 A/B", 11, Theme.c(a, R.color.text_dim2));
        loopInfoRef = loopInfo;

        setA.setOnClickListener(v -> {
            loopStartMs = player != null ? player.getCurrentPosition() : 0;
            loopInfo.setText("A=" + fmt(loopStartMs) + " · " + loopLabel());
        });
        setB.setOnClickListener(v -> {
            loopEndMs = player != null ? player.getCurrentPosition() : (int) current.durationMs;
            loopInfo.setText("B=" + fmt(loopEndMs) + " · " + loopLabel());
        });
        clear.setOnClickListener(v -> {
            loopStartMs = -1;
            loopEndMs = -1;
            loopInfo.setText("未设置 A/B");
        });
        loops.addView(setA, U.weight(1));
        loops.addView(U.hspacer(a, 6));
        loops.addView(setB, U.weight(1));
        loops.addView(U.hspacer(a, 6));
        loops.addView(clear, U.weight(1));
        card.addView(loops);
        card.addView(U.spacer(a, 6));
        card.addView(loopInfo);

        card.addView(U.spacer(a, 12));

        LinearLayout ctrl = U.row(a);
        TextView play = U.primaryButton(a, "▶ 播放");
        TextView pause = U.ghostButton(a, "⏸ 暂停", Theme.c(a, R.color.text));
        TextView back = U.ghostButton(a, "回到 A", Theme.c(a, R.color.text_dim));
        ctrl.addView(play, U.weight(1));
        ctrl.addView(U.hspacer(a, 6));
        ctrl.addView(pause, U.weight(1));
        ctrl.addView(U.hspacer(a, 6));
        ctrl.addView(back, U.weight(1));
        card.addView(ctrl);

        card.addView(U.spacer(a, 10));
        TextView rec = U.primaryButton(a, "🎤 录一句跟读");
        card.addView(rec);

        LinearLayout result = U.col(a);
        card.addView(result);

        playerBox.addView(card);

        // ---- 播放器实例 ----
        try {
            player = new MediaPlayer();
            player.setDataSource(AudioClip.file(a, current).getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(mp -> {
                if (loopEndMs > loopStartMs && loopStartMs >= 0) {
                    mp.seekTo(loopStartMs);
                    mp.start();
                }
            });
        } catch (Throwable e) {
            card.addView(U.tv(a, "打不开这段音频：" + e.getMessage(), 12,
                    Theme.c(a, R.color.danger)));
            return;
        }

        play.setOnClickListener(v -> {
            if (player == null) return;
            if (loopStartMs >= 0) player.seekTo(loopStartMs);
            player.start();
        });
        pause.setOnClickListener(v -> {
            if (player != null && player.isPlaying()) player.pause();
        });
        back.setOnClickListener(v -> {
            if (player != null && loopStartMs >= 0) player.seekTo(loopStartMs);
        });

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(prog);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        rec.setOnClickListener(v -> recordAndScore(result, rec));

        // 位置刷新 + A/B 循环
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    try {
                        int cp = player.getCurrentPosition();
                        bar.setProgress(cp);
                        pos.setText(fmt(cp) + " / " + fmt(current.durationMs));
                        if (loopEndMs > loopStartMs && loopStartMs >= 0
                                && cp >= loopEndMs && player.isPlaying()) {
                            player.seekTo(loopStartMs);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                h.postDelayed(this, 150);
            }
        }, 150);

        if (sents == null || sents.isEmpty()) doSegmentQuiet();
    }

    private View sentRow(int i) {
        AudioClip.Sent s = sents.get(i);
        LinearLayout row = U.row(a);
        int pad = U.px(a, 6);
        row.setPadding(0, pad, 0, pad);

        TextView num = U.tv(a, String.valueOf(i + 1), 10, Theme.c(a, R.color.primary));
        num.setPadding(0, 0, U.px(a, 8), 0);
        row.addView(num);

        LinearLayout col = U.col(a);
        String text = s.text == null || s.text.isEmpty() ? "（未识别）" : s.text;
        TextView t = U.tv(a, text, 13, Theme.c(a, R.color.text));
        t.setLineSpacing(U.dp(a, 3), 1f);
        col.addView(t);
        col.addView(U.tv(a, fmt(s.startMs) + " – " + fmt(s.endMs)
                + String.format(Locale.US, "  %.1fs", (s.endMs - s.startMs) / 1000f),
                10, Theme.c(a, R.color.text_dim2)));
        row.addView(col, U.weight(1));

        final int idx = i;
        final TextView say = U.tv(a, "读", 11, Theme.c(a, R.color.accent));
        say.setPadding(U.px(a, 6), 0, U.px(a, 6), 0);
        say.setOnClickListener(v -> {
            if (s.text != null && !s.text.isEmpty()) Tts.say(a, s.text);
        });
        row.addView(say);

        TextView recBtn = U.tv(a, "🎤", 13, Theme.c(a, R.color.primary));
        recBtn.setPadding(U.px(a, 4), 0, 0, 0);
        recBtn.setOnClickListener(v -> {
            sentIdx = idx;
            if (player != null) {
                player.seekTo(s.startMs);
                player.start();
            }
            android.widget.Toast.makeText(a, "听一遍，然后点下面「录一句跟读」",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        row.addView(recBtn);

        row.setBackground(sentIdx == i ? U.primaryBg(a) : null);
        row.setOnClickListener(v -> {
            sentIdx = idx;
            loopStartMs = s.startMs;
            loopEndMs = s.endMs;
            if (player != null) {
                player.seekTo(s.startMs);
                player.start();
            }
            loopInfoUpdate();
            renderPlayer();
        });
        return row;
    }

    private TextView loopInfoRef;

    private void loopInfoUpdate() {
        if (loopInfoRef != null) {
            loopInfoRef.setText("A=" + fmt(loopStartMs) + " · B=" + fmt(loopEndMs));
        }
    }

    // ------------------------------------------------------------ 切句

    private void doSegment() {
        if (current == null) return;
        android.widget.Toast.makeText(a, "正在切句…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<AudioClip.Sent> list = AudioClip.segment(a, current);
            a.runOnUiThread(() -> {
                if (list == null || list.isEmpty()) {
                    android.widget.Toast.makeText(a, "没检测到人声（可能纯音乐）",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                sents = list;
                sentIdx = -1;
                renderPlayer();
                renderList();
                android.widget.Toast.makeText(a, "切出 " + list.size() + " 句",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }, "seg").start();
    }

    private void doSegmentQuiet() {
        // 第一次打开时静默切句，界面不阻塞
        new Thread(() -> {
            List<AudioClip.Sent> list = AudioClip.segment(a, current);
            if (list == null || list.isEmpty()) return;
            a.runOnUiThread(() -> {
                if (current != null && (sents == null || sents.isEmpty())) {
                    sents = list;
                    renderPlayer();
                }
            });
        }, "segq").start();
    }

    // ------------------------------------------------------------ 录音评分

    private void recordAndScore(LinearLayout out, TextView btn) {
        if (!OfflineAsrEngine.ready()) {
            android.widget.Toast.makeText(a, "识别模型未就绪：去「我的 → 离线语音」下载",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (OfflineAsrEngine.isRecording()) {
            OfflineAsrEngine.stopListening();
            return;
        }
        if (player != null && player.isPlaying()) player.pause();

        // 有选中句子就按这句评，否则用 A/B 段，再否则整段
        final String target;
        if (sentIdx >= 0 && sents != null && sentIdx < sents.size()) {
            target = sents.get(sentIdx).text;
        } else if (loopStartMs >= 0 && loopEndMs > loopStartMs) {
            target = null;
        } else {
            target = null;
        }

        out.removeAllViews();
        btn.setText("⏹ 停止并识别");
        final TextView st = U.tv(a, "正在录音…（说完整句再点停止）", 12,
                Theme.c(a, R.color.text_dim));
        out.addView(st);

        OfflineAsrEngine.listen(a.getApplicationContext(), new OfflineAsrEngine.Callback() {
            @Override public void onStart() { }

            @Override public void onLevel(float level) { }

            @Override public void onTimed(OfflineAsrEngine.TimedResult timed) {
                btn.setText("🎤 录一句跟读");
                out.removeAllViews();
                if (timed.text == null || timed.text.isEmpty()) {
                    out.addView(U.tv(a, "没听清，再来一次", 12, Theme.c(a, R.color.warn)));
                    return;
                }
                // 有目标句 → 逐词评分
                if (target != null && !target.isEmpty()) {
                    showScore(out, target, timed);
                } else {
                    out.addView(U.tv(a, "识别：" + timed.text, 13, Theme.c(a, R.color.text)));
                    out.addView(U.spacer(a, 4));
                    out.addView(U.tv(a, "点上面的句子选中它，再录就能逐词打分", 11,
                            Theme.c(a, R.color.text_dim2)));
                }
            }

            @Override public void onResult(String text) { }

            @Override public void onError(String msg) {
                btn.setText("🎤 录一句跟读");
                out.removeAllViews();
                out.addView(U.tv(a, "出错：" + msg, 12, Theme.c(a, R.color.danger)));
            }
        });
    }

    /** 逐词对照评分（这是 v5.x 完全没做的事）。 */
    private void showScore(LinearLayout out, String target,
                           OfflineAsrEngine.TimedResult timed) {
        float dur = timed.duration > 0 ? timed.duration : 1f;
        Pronunciation.Result r = Pronunciation.score(target, timed, dur);
        if (!r.ok()) {
            out.addView(U.tv(a, "识别：" + timed.text, 13, Theme.c(a, R.color.text)));
            return;
        }

        int scoreColor = r.score >= 85 ? Theme.c(a, R.color.accent)
                : r.score >= 65 ? Theme.c(a, R.color.warn)
                : Theme.c(a, R.color.danger);

        LinearLayout head = U.row(a);
        head.setPadding(0, U.px(a, 10), 0, 0);
        TextView sc = U.bold(a, String.valueOf(r.score), 30, scoreColor);
        head.addView(sc);
        head.addView(U.tv(a, " 分", 13, scoreColor));
        head.addView(U.hspacer(a, 12));

        LinearLayout meta = U.col(a);
        meta.addView(U.tv(a, r.correctCount + "/" + r.total + " 词正确", 11,
                Theme.c(a, R.color.text_dim)));
        if (r.wpm > 0) {
            meta.addView(U.tv(a, "语速 " + r.wpm + " wpm", 11,
                    Theme.c(a, R.color.text_dim2)));
        }
        head.addView(meta, U.weight(1));
        out.addView(head);

        // 逐词色块
        LinearLayout chips = U.row(a);
        chips.setPadding(0, U.px(a, 8), 0, 0);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int count = 0;
        for (Pronunciation.Word w : r.words) {
            if (count++ >= 14) break;
            LinearLayout chip = U.pill(a, w.target,
                    Theme.c(a, w.correct ? R.color.accent : R.color.danger));
            chips.addView(chip);
            chips.addView(U.hspacer(a, 4));
        }
        out.addView(chips);

        // 不满意的词
        StringBuilder miss = new StringBuilder();
        for (Pronunciation.Word w : r.words) {
            if (!w.correct) {
                if (miss.length() > 0) miss.append(" · ");
                miss.append(w.target);
                if (w.heard != null && !w.heard.isEmpty()) {
                    miss.append("(听成 ").append(w.heard).append(")");
                }
            }
        }
        if (miss.length() > 0) {
            TextView m = U.tv(a, "注意：" + miss, 12, Theme.c(a, R.color.danger));
            m.setPadding(0, U.px(a, 6), 0, 0);
            m.setLineSpacing(U.dp(a, 3), 1f);
            out.addView(m);
        }
        if (r.advice != null && !r.advice.isEmpty()) {
            out.addView(U.tv(a, r.advice, 11, Theme.c(a, R.color.text_dim2)));
        }

        // 回放自己念的（原来的实现没有录音留存，这次加上）
        LinearLayout replay = U.row(a);
        replay.setPadding(0, U.px(a, 8), 0, 0);
        TextView mine = U.ghostButton(a, "▶ 回放我念的", Theme.c(a, R.color.primary));
        mine.setOnClickListener(v -> replayLast());
        replay.addView(mine, U.weight(1));
        replay.addView(U.hspacer(a, 6));
        TextView orig = U.ghostButton(a, "▶ 再听原声", Theme.c(a, R.color.accent));
        orig.setOnClickListener(v -> {
            if (player != null) {
                if (loopStartMs >= 0) player.seekTo(loopStartMs);
                player.start();
            }
        });
        replay.addView(orig, U.weight(1));
        out.addView(replay);
    }

    private void replayLast() {
        if (lastRecordPath == null) {
            android.widget.Toast.makeText(a, "还没录过", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (lastPlayer != null) {
                lastPlayer.release();
                lastPlayer = null;
            }
            lastPlayer = new MediaPlayer();
            lastPlayer.setDataSource(lastRecordPath);
            lastPlayer.prepare();
            lastPlayer.start();
        } catch (Throwable e) {
            android.widget.Toast.makeText(a, "回放失败：" + e.getMessage(),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String loopLabel() {
        if (loopStartMs < 0) return "还需设置 A";
        if (loopEndMs <= loopStartMs) return "还需设置 B";
        return String.format(Locale.US, "循环 %.1fs",
                (loopEndMs - loopStartMs) / 1000f);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        if (lastPlayer != null) {
            try {
                lastPlayer.release();
            } catch (Throwable ignored) {
            }
            lastPlayer = null;
        }
    }
}
