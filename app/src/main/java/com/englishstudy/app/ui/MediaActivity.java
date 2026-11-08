package com.englishstudy.app.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Media;

/**
 * 媒体跟读独立页（不放在弹窗里）。
 *
 * 独立 Activity 的三个好处：
 *   1. 历史记录列表能完整展开（弹窗高度有限）
 *   2. 系统返回键天然回到上一页（用户担心的「点进单词再返回会不会跳主界面」）
 *   3. 句子多了也能正常滚动，不挤主界面
 */
public class MediaActivity extends Activity {

    private MediaPanel panel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(Theme.c(this, R.color.bg));

        LinearLayout root = U.col(this);

        // 顶栏
        LinearLayout top = U.row(this);
        int p = U.px(this, 16);
        top.setPadding(p, U.px(this, 14), p, U.px(this, 6));
        android.widget.TextView back = U.tv(this, "‹", 26, Theme.c(this, R.color.text));
        back.setPadding(U.px(this, 2), 0, U.px(this, 14), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(U.bold(this, "视频 / 音频跟读", 17,
                Theme.c(this, R.color.text)), U.weight(1));
        root.addView(top);

        panel = new MediaPanel(this);
        View body = panel.build();
        root.addView(body, new LinearLayout.LayoutParams(
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
        if (uri == null) return;
        if (req == 0xB1) {
            panel.onFile(uri, true);
        } else if (req == 0xB2) {
            panel.onFile(uri, false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Tts.stop();
    }
}
