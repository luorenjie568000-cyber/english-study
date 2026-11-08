package com.englishstudy.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Media;

import java.util.Locale;

/**
 * 单句视频播放：进来就定位到该句起点，播到终点自动暂停。
 *
 * 为什么单独一个 Activity 而不是弹窗：
 *   VideoView 在 Dialog 里尺寸和生命周期都容易出问题（旋转/切后台），
 *   独立 Activity 更稳，且能全屏看。
 */
public class MediaPlayerActivity extends Activity {

    private VideoView video;
    private Media.Item item;
    private int startMs, endMs;
    private boolean loop;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String id = getIntent().getStringExtra("id");
        startMs = getIntent().getIntExtra("start", 0);
        endMs = getIntent().getIntExtra("end", 0);
        Media.load(this);
        item = Media.byId(this, id);
        if (item == null) {
            finish();
            return;
        }

        LinearLayout root = U.col(this);
        root.setBackgroundColor(0xFF000000);

        // 顶栏
        LinearLayout top = U.row(this);
        top.setPadding(U.px(this, 16), U.px(this, 14), U.px(this, 16), U.px(this, 8));
        TextView back = U.tv(this, "‹", 26, 0xFFE6E8EB);
        back.setPadding(U.px(this, 4), 0, U.px(this, 12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.tv(this, item.title, 14, 0xFFA8ADB5), U.weight(1));
        root.addView(top);

        // 视频区
        FrameLayout holder = new FrameLayout(this);
        video = new VideoView(this);
        holder.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        int hgt = (int) (getResources().getDisplayMetrics().widthPixels * 9 / 16f);
        root.addView(holder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, hgt));

        // 句子文本（找出来）
        LinearLayout info = U.col(this);
        int p = U.px(this, 16);
        info.setPadding(p, p, p, p);
        Media.Line line = null;
        for (Media.Line l : item.lines) {
            if (l.startMs == startMs) {
                line = l;
                break;
            }
        }
        if (line != null) {
            TextView t = U.tv(this, line.text, 16, 0xFFE6E8EB);
            t.setLineSpacing(U.dp(this, 5), 1f);
            info.addView(t);
            if (line.zh != null && !line.zh.isEmpty()) {
                TextView z = U.tv(this, line.zh, 14, 0xFFA8ADB5);
                z.setLineSpacing(U.dp(this, 4), 1f);
                z.setPadding(0, U.px(this, 6), 0, 0);
                info.addView(z);
            }
        }
        root.addView(info);

        // 控制
        LinearLayout ctrl = U.row(this);
        ctrl.setPadding(p, 0, p, U.px(this, 10));
        TextView again = U.ghostButton(this, "↺ 重播这句", 0xFF8AB4F8);
        again.setOnClickListener(v -> {
            video.seekTo(startMs);
            video.start();
        });
        ctrl.addView(again, U.weight(1));
        ctrl.addView(U.hspacer(this, 8));
        TextView lp = U.ghostButton(this, "🔁 循环", 0xFFA8ADB5);
        lp.setOnClickListener(v -> {
            loop = !loop;
            ((TextView) v).setTextColor(loop ? 0xFF81C995 : 0xFFA8ADB5);
        });
        ctrl.addView(lp, U.weight(1));
        root.addView(ctrl);

        setContentView(root);

        video.setVideoPath(Media.file(this, item).getAbsolutePath());
        video.setOnPreparedListener(mp -> {
            video.seekTo(Math.max(0, startMs - 100));
            video.start();
        });

        final android.os.Handler hd = new android.os.Handler(android.os.Looper.getMainLooper());
        hd.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (video != null && video.isPlaying() && endMs > 0
                            && video.getCurrentPosition() >= endMs) {
                        if (loop) {
                            video.seekTo(startMs);
                        } else {
                            video.pause();
                        }
                    }
                } catch (Throwable ignored) {
                }
                hd.postDelayed(this, 120);
            }
        }, 120);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video != null && video.isPlaying()) video.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (video != null) {
            try {
                video.stopPlayback();
            } catch (Throwable ignored) {
            }
        }
    }
}
