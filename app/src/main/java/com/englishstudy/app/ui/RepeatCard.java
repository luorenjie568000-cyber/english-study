package com.englishstudy.app.ui;

import android.app.Activity;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.voice.OfflineAsrEngine;

import java.util.Locale;

/**
 * 跟读卡片：录音 → 离线识别 → 与目标词比对 → 简单评分。
 * 引擎未就绪时给出「未就绪」提示，不崩溃。
 */
public final class RepeatCard {

    public static LinearLayout build(Activity a, String target) {
        final String tgt = target == null ? "" : target.trim();
        LinearLayout card = U.card(a);
        card.addView(U.bold(a, "跟读练习", 13, a.getColor(R.color.text_dim)));

        final TextView state = U.tv(a, "按住下方按钮朗读", 14, a.getColor(R.color.text_dim));
        card.addView(U.spacer(a, 4));
        card.addView(state);

        final TextView score = U.tv(a, "", 22, a.getColor(R.color.text));
        card.addView(score);

        TextView btn = U.bold(a, "🎤 开始跟读", 14, a.getColor(R.color.white));
        btn.setBackgroundResource(R.drawable.bg_primary);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, U.px(a, 12), 0, U.px(a, 12));
        btn.setOnClickListener(v -> {
            if (OfflineAsrEngine.isRecording()) {
                OfflineAsrEngine.stopListening();
                return;
            }
            if (!OfflineAsrEngine.ready()) {
                state.setText("语音引擎未就绪：请到「我的 → 离线语音」下载识别模型");
                return;
            }
            state.setText("正在听…请朗读 " + tgt);
            score.setText("");
            btn.setText("⏹ 停止");
            OfflineAsrEngine.listen(a.getApplicationContext(), new OfflineAsrEngine.Callback() {
                @Override public void onStart() { }

                @Override public void onLevel(float level) { }

                @Override public void onResult(String text) {
                    btn.setText("🎤 开始跟读");
                    if (text == null || text.isEmpty()) {
                        state.setText("没听清，再来一次");
                        return;
                    }
                    int sc = OfflineAsrEngine.score(tgt, text);
                    state.setText("识别：" + text);
                    score.setText(sc >= 90 ? "满分 " + sc : sc >= 60 ? "不错 " + sc : "再试 " + sc);
                    score.setTextColor(a.getColor(
                            sc >= 90 ? R.color.ok : sc >= 60 ? R.color.warn : R.color.danger));
                }

                @Override public void onError(String msg) {
                    btn.setText("🎤 开始跟读");
                    state.setText("出错：" + msg);
                }
            });
        });
        card.addView(U.spacer(a, 8));
        card.addView(btn);
        return card;
    }

    private RepeatCard() {
    }
}
