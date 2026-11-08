package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 视频跟读：导入本地视频 → 播放 → 按时间段反复听 → 录音对比。
 *
 * 和音频跟读的区别：
 *   - 用 VideoView 播放（原生，无需解码库）
 *   - 支持「字幕式分段」：手动给每个片段起止点打一个标记，
 *     切出来的片段可点播、可循环、可跟读
 *
 * 为什么不做自动字幕识别：
 *   需要「视频 → 抽音轨 → PCM → ASR 逐句切分」，抽音轨在纯 Java 里要 MediaCodec
 *   + MediaExtractor 手写，工作量大。手动打点 5 秒能标一句，更实用。
 */
public class VideoPanel {

    private final Activity a;
    private VideoView video;
    private LinearLayout segBox;
    private LinearLayout playerBox;
    private TextView posTv;
    private SeekBar bar;

    /** 当前视频的分段：{start, end, 文本}。 */
    private final List<long[]> segs = new ArrayList<>();
    private final List<String> segTexts = new ArrayList<>();
    private int loopIdx = -1;
    private String videoPath;

    public VideoPanel(Activity a) {
        this.a = a;
    }

    public View build() {
        ScrollView sv = new ScrollView(a);
        LinearLayout root = U.col(a);
        int p = U.px(a, 16);
        root.setPadding(p, p, p, U.px(a, 24));

        root.addView(U.bold(a, "视频跟读", 20, Theme.c(a, R.color.text)));
        root.addView(U.spacer(a, 4));
        root.addView(U.tv(a, "导入一段视频，标出想练的片段，反复听再跟读",
                12, Theme.c(a, R.color.text_dim2)));
        root.addView(U.spacer(a, 12));

        TextView add = U.primaryButton(a, "＋ 导入视频（mp4 / mkv / webm）");
        add.setOnClickListener(v -> ((com.englishstudy.app.MainActivity) a).pickFile(0xA2));
        root.addView(add);

        root.addView(U.spacer(a, 14));
        playerBox = U.col(a);
        root.addView(playerBox);

        sv.addView(root);
        return sv;
    }

    /** 由 MainActivity 转发文件选择。 */
    public void onVideoFile(Uri uri) {
        if (uri == null) return;
        videoPath = uri.toString();
        segs.clear();
        segTexts.clear();
        loopIdx = -1;
        renderPlayer();
    }

    private void renderPlayer() {
        playerBox.removeAllViews();
        if (videoPath == null) return;

        LinearLayout card = U.card(a);
        card.setPadding(0, 0, 0, U.px(a, 12));

        // 视频区域：固定 16:9
        FrameLayout holder = new FrameLayout(a);
        holder.setBackgroundColor(0xFF000000);
        video = new VideoView(a);
        holder.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        int h = (int) (a.getResources().getDisplayMetrics().widthPixels * 9 / 16f);
        card.addView(holder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));

        LinearLayout ctrls = U.col(a);
        int cp = U.px(a, 14);
        ctrls.setPadding(cp, U.px(a, 10), cp, 0);

        posTv = U.tv(a, "00:00 / 00:00", 12, Theme.c(a, R.color.text_dim));
        ctrls.addView(posTv);

        bar = new SeekBar(a);
        ctrls.addView(bar);

        LinearLayout row = U.row(a);
        TextView play = U.primaryButton(a, "▶ 播放");
        TextView pause = U.ghostButton(a, "⏸", Theme.c(a, R.color.text));
        TextView mark = U.ghostButton(a, "＋标一句", Theme.c(a, R.color.accent));
        row.addView(play, U.weight(1));
        row.addView(U.hspacer(a, 6));
        row.addView(pause, U.weight(1));
        row.addView(U.hspacer(a, 6));
        row.addView(mark, U.weight(1));
        ctrls.addView(row);
        card.addView(ctrls);
        playerBox.addView(card);

        playerBox.addView(U.spacer(a, 12));

        LinearLayout segCard = U.card(a);
        segCard.addView(U.bold(a, "片段", 13, Theme.c(a, R.color.text_dim)));
        segCard.addView(U.tv(a, "播放到起点点「＋标一句」，再播放到终点点一次 —— 就切出一段",
                11, Theme.c(a, R.color.text_dim2)));
        segCard.addView(U.spacer(a, 8));
        segBox = U.col(a);
        segCard.addView(segBox);
        playerBox.addView(segCard);

        // ---- 播放器 ----
        video.setVideoURI(Uri.parse(videoPath));
        video.setOnPreparedListener(mp -> {
            bar.setMax(Math.max(1, mp.getDuration()));
            posTv.setText("00:00 / " + fmt(mp.getDuration()));
            mp.setOnCompletionListener(m -> {
                if (loopIdx >= 0 && loopIdx < segs.size()) {
                    long[] s = segs.get(loopIdx);
                    video.seekTo((int) s[0]);
                    video.start();
                }
            });
        });

        play.setOnClickListener(v -> {
            if (loopIdx >= 0 && loopIdx < segs.size()) {
                video.seekTo((int) segs.get(loopIdx)[0]);
            }
            video.start();
        });
        pause.setOnClickListener(v -> {
            if (video.isPlaying()) video.pause();
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) video.seekTo(p);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }

            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        pivot[0] = -1;
        mark.setOnClickListener(v -> {
            int cp2 = video.getCurrentPosition();
            if (pivot[0] < 0) {
                pivot[0] = cp2;
                android.widget.Toast.makeText(a, "起点 " + fmt(cp2) + "，播到终点点一次",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                if (cp2 <= pivot[0] + 500) {
                    android.widget.Toast.makeText(a, "终点太近，往后播一点",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                segs.add(new long[]{pivot[0], cp2});
                segTexts.add("");
                pivot[0] = -1;
                renderSegs();
            }
        });

        renderSegs();

        // 位置刷新
        final android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
        hd.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (video != null && video.isPlaying()) {
                        int cur = video.getCurrentPosition();
                        bar.setProgress(cur);
                        posTv.setText(fmt(cur) + " / " + fmt(video.getDuration()));
                        if (loopIdx >= 0 && loopIdx < segs.size()) {
                            long[] s = segs.get(loopIdx);
                            if (cur >= s[1]) video.seekTo((int) s[0]);
                        }
                    }
                } catch (Throwable ignored) {
                }
                hd.postDelayed(this, 200);
            }
        }, 200);
    }

    private final int[] pivot = {-1};

    private void renderSegs() {
        if (segBox == null) return;
        segBox.removeAllViews();
        if (segs.isEmpty()) {
            segBox.addView(U.tv(a, "还没有片段", 12, Theme.c(a, R.color.text_dim2)));
            return;
        }
        for (int i = 0; i < segs.size(); i++) {
            final int idx = i;
            long[] s = segs.get(i);
            LinearLayout row = U.row(a);
            row.setPadding(0, U.px(a, 8), 0, U.px(a, 8));

            TextView num = U.tv(a, String.valueOf(i + 1), 12, Theme.c(a, R.color.primary));
            num.setPadding(0, 0, U.px(a, 10), 0);
            row.addView(num);

            LinearLayout col = U.col(a);
            col.addView(U.tv(a, fmt(s[0]) + " – " + fmt(s[1])
                    + "  (" + String.format(Locale.US, "%.1fs", (s[1] - s[0]) / 1000f) + ")",
                    13, Theme.c(a, R.color.text)));
            col.addView(U.tv(a, "点播放循环这一段", 10, Theme.c(a, R.color.text_dim2)));
            row.addView(col, U.weight(1));

            TextView del = U.tv(a, "删除", 11, Theme.c(a, R.color.danger));
            del.setPadding(U.px(a, 8), 0, 0, 0);
            del.setOnClickListener(v -> {
                segs.remove(idx);
                if (loopIdx >= segs.size()) loopIdx = -1;
                renderSegs();
            });
            row.addView(del);

            final boolean active = loopIdx == i;
            row.setBackground(active ? U.primaryBg(a) : null);
            row.setOnClickListener(v -> {
                loopIdx = idx;
                video.seekTo((int) s[0]);
                video.start();
                renderSegs();
            });
            segBox.addView(row);
        }
        segBox.addView(U.spacer(a, 6));
        TextView clear = U.tv(a, "清除循环", 11, Theme.c(a, R.color.primary));
        clear.setOnClickListener(v -> {
            loopIdx = -1;
            renderSegs();
        });
        segBox.addView(clear);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public void release() {
        if (video != null) {
            try {
                video.stopPlayback();
            } catch (Throwable ignored) {
            }
        }
    }
}
