package com.englishstudy.app.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.englishstudy.app.R;

/**
 * 音频跟读独立页。
 *
 * 为什么从弹窗改成独立 Activity：
 *   AudioPanel.build() 返回的是 ScrollView，塞进 AlertDialog 并给固定高度后，
 *   ScrollView 测不出内容高度，整块塌成 0 —— 弹窗里只剩标题和「关闭」，
 *   看起来就是「功能没做」。这是 v6.0/v6.1 的真实症状。
 *   独立页有正常的窗口高度，MediaActivity 也是这么解决的。
 */
public class AudioActivity extends Activity {

    private AudioPanel panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Theme.c(this, R.color.bg));

        LinearLayout root = U.col(this);

        LinearLayout top = U.row(this);
        int p = U.px(this, 16);
        top.setPadding(p, U.px(this, 14), p, U.px(this, 4));
        android.widget.TextView back = U.tv(this, "‹", 26, Theme.c(this, R.color.text));
        back.setPadding(U.px(this, 2), 0, U.px(this, 14), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.bold(this, "音频跟读", 17, Theme.c(this, R.color.text)), U.weight(1));
        root.addView(top);

        panel = new AudioPanel(this);
        root.addView(panel.build(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(shell);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (data == null) return;
        Uri uri = data.getData();
        if (uri != null && req == 0xA1) panel.onAudioFile(uri);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Tts.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (panel != null) panel.stopPlayer();
    }
}
