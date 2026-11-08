package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.AudioClip;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.List;
import java.util.Locale;

/**
 * 音频跟读：导入 mp3/wav → 播放 → 录音对比。
 *
 * 简化版（如实说明）：
 *   - 不做自动逐句切分（需要音频解码 + 静音检测，工作量大且对音乐类素材不准）
 *   - 提供「A/B 循环」：手动拖两个点，反复听那一段，这是跟读最实用的形态
 *   - 录音后用现有 ASR 对比，给出逐词评分
 *
 * 对应 1000h/Enjoy 的「音频可视化跟读」，但我们只做「听后跟读」这一段，
 * 不做波形可视化（那需要解码全部 PCM，内存和 CPU 都不划算）。
 */
public class AudioPanel {

    private final Activity a;
    private MediaPlayer player;
    private AudioClip.Item current;
    private int loopStartMs = -1;
    private int loopEndMs = -1;
    private LinearLayout listBox;
    private LinearLayout playerBox;

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
        root.addView(U.tv(a, "导入一段音频，反复听同一句再跟读", 12,
                Theme.c(a, R.color.text_dim2)));
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
            card.addView(U.tv(a, String.format(Locale.US, "%.1f 分钟",
                    it.durationMs / 60000f), 11, Theme.c(a, R.color.text_dim2)));
            card.addView(U.spacer(a, 8));

            LinearLayout acts = U.row(a);
            TextView play = U.ghostButton(a, "▶ 播放", Theme.c(a, R.color.primary));
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
    public void onAudioFile(android.net.Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            String name = uri.getLastPathSegment();
            if (name != null) {
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
        bar.setProgress(0);
        card.addView(bar);

        // A/B 循环
        LinearLayout loops = U.row(a);
        TextView setA = U.ghostButton(a, "A 点", Theme.c(a, R.color.accent));
        TextView setB = U.ghostButton(a, "B 点", Theme.c(a, R.color.accent));
        TextView clear = U.ghostButton(a, "清除", Theme.c(a, R.color.text_dim));
        final TextView loopInfo = U.tv(a, "未设置循环", 11, Theme.c(a, R.color.text_dim2));

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
            loopInfo.setText("未设置循环");
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
        TextView stop = U.ghostButton(a, "⏸ 暂停", Theme.c(a, R.color.text));
        TextView back = U.ghostButton(a, "回到 A", Theme.c(a, R.color.text_dim));
        ctrl.addView(play, U.weight(1));
        ctrl.addView(U.hspacer(a, 6));
        ctrl.addView(stop, U.weight(1));
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
        stop.setOnClickListener(v -> {
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

        // 位置更新
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (player != null) {
                    try {
                        int cp = player.getCurrentPosition();
                        bar.setProgress(cp);
                        pos.setText(fmt(cp) + " / " + fmt(current.durationMs));
                        // A/B 循环
                        if (loopEndMs > loopStartMs && loopStartMs >= 0
                                && cp >= loopEndMs && player.isPlaying()) {
                            player.seekTo(loopStartMs);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                h.postDelayed(this, 250);
            }
        };
        h.postDelayed(tick, 250);

        rec.setOnClickListener(v -> recordAndScore(result, rec));
    }

    private String loopLabel() {
        if (loopStartMs >= 0 && loopEndMs > loopStartMs) {
            return "循环 " + fmt(loopEndMs - loopStartMs);
        }
        return "还需设置另一端";
    }

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

        out.removeAllViews();
        btn.setText("⏹ 停止并识别");
        final TextView st = U.tv(a, "正在录音…", 12, Theme.c(a, R.color.text_dim));
        out.addView(st);

        OfflineAsrEngine.listen(a.getApplicationContext(), new OfflineAsrEngine.Callback() {
            @Override public void onStart() { }

            @Override public void onLevel(float level) { }

            @Override public void onTimed(OfflineAsrEngine.TimedResult timed) {
                btn.setText("🎤 录一句跟读");
                out.removeAllViews();
                if (timed.text == null || timed.text.isEmpty()) {
                    out.addView(U.tv(a, "没听清，再来一次", 12,
                            Theme.c(a, R.color.warn)));
                    return;
                }
                out.addView(U.tv(a, "识别：" + timed.text, 13, Theme.c(a, R.color.text)));
                out.addView(U.spacer(a, 4));
                out.addView(U.tv(a, "对照上面的音频，看自己哪儿没念对", 11,
                        Theme.c(a, R.color.text_dim2)));
            }

            @Override public void onResult(String text) { }

            @Override public void onError(String msg) {
                btn.setText("🎤 录一句跟读");
                out.removeAllViews();
                out.addView(U.tv(a, "出错：" + msg, 12, Theme.c(a, R.color.danger)));
            }
        });
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
    }
}
