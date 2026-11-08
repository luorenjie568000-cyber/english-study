package com.englishstudy.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.data.Books;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Translate;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.ui.ChatView;
import com.englishstudy.app.ui.Glass;
import com.englishstudy.app.ui.HomeView;
import com.englishstudy.app.ui.ReadView;
import com.englishstudy.app.ui.SpeakView;
import com.englishstudy.app.ui.TransView;
import com.englishstudy.app.ui.Tts;
import com.englishstudy.app.ui.U;
import com.englishstudy.app.ui.VocabView;
import com.englishstudy.app.ui.WriteView;
import com.englishstudy.app.ui.IpaView;
import com.englishstudy.app.ui.MineView;
import com.englishstudy.app.voice.OfflineAsrEngine;
import com.englishstudy.app.voice.OfflineTtsEngine;

/** 主界面：底部标签，内容区用原生 View 切换。 */
public class MainActivity extends Activity {

    public static final String[] TABS = {"学习", "口语", "词汇", "对话", "翻译", "写作", "音标", "阅读", "我的"};

    private FrameLayout content;
    private LinearLayout bar;
    private TextView[] labels;
    private int current = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Books.load(this);

        // 底层：深色 + 彩色光晕（玻璃要有东西可透才看得出质感）
        FrameLayout shell = new FrameLayout(this);
        Glass.Backdrop backdrop = new Glass.Backdrop(this);
        backdrop.setAccents(getColor(R.color.primary), getColor(R.color.purple));
        shell.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout root = U.col(this);
        root.setBackgroundColor(0x00000000);
        shell.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        bar = U.row(this);
        Glass.applyBar(bar, this);
        bar.setPadding(0, U.px(this, 6), 0, U.px(this, 6));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, U.px(this, 56)));

        labels = new TextView[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(TABS.length > 8 ? 10 : 11);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setOnClickListener(v -> select(idx));
            bar.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            labels[i] = t;
        }

        setContentView(shell);
        select(0);

        // 首次启动：定目标 → 选词书 → 立刻开始第一组
        if (com.englishstudy.app.ui.Onboarding.needed()) {
            getWindow().getDecorView().postDelayed(
                    () -> com.englishstudy.app.ui.Onboarding.show(this, this::refresh), 300);
        }

        // 语音引擎预热：模型在盘上就直接加载，不必等用户进「我的」页
        Tts.init(this);
        new Thread(() -> {
            OfflineAsrEngine.init(this);
        }, "asr-warm").start();

        // 词典/例句/翻译语料后台加载，加载完刷新一次
        new Thread(() -> {
            Dict.load(this);
            Sentences.load(this);
            Translate.load(this);
            runOnUiThread(this::refresh);
        }, "loader").start();
    }

    private void select(int idx) {
        if (current == idx) return;
        // 切页先掐掉上一页的朗读
        com.englishstudy.app.ui.Tts.stop();
        current = idx;
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == idx;
            labels[i].setTextColor(getColor(on ? R.color.primary : R.color.text_dim2));
            // 选中态用玻璃药丸，未选中透明
            if (on && Glass.level() != Glass.OFF) {
                labels[i].setBackground(Glass.pill(this, getColor(R.color.primary)));
            } else {
                labels[i].setBackground(null);
            }
        }
        content.removeAllViews();
        View v = build(idx);
        content.addView(v, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View build(int idx) {
        switch (idx) {
            case 0:
                return new HomeView(this).build();
            case 1:
                return new SpeakView(this).build();
            case 2:
                return new VocabView(this).build();
            case 3:
                return new ChatView(this).build();
            case 4:
                return new TransView(this).build();
            case 5:
                return new WriteView(this).build();
            case 6:
                return new IpaView(this).build();
            case 7:
                return new ReadView(this).build();
            default:
                currentMine = new MineView(this);
                return currentMine.build();
        }
    }

    /** 重建当前页（不重置滚动位置的话，只在切页时调用）。 */
    public void refresh() {
        int c = current;
        current = -1;
        select(c < 0 ? 0 : c);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Vocab.flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Vocab.flush();
    }

    /** 供子 View 调用：选文件后把 Uri 交给回调。 */
    public void pickFile(int requestCode) {
        android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;
        if (current >= 0) {
            // 让当前页自己处理（目前只有「我的」页有导入）
            View v = content.getChildAt(0);
            if (v instanceof android.view.ViewGroup) {
                // 直接找 MineView 的实例不可行，改为通过标签索引判断
            }
        }
        if (currentMine != null) {
            currentMine.onFilePicked(requestCode, uri);
        }
    }

    /** 当前显示「我的」页时保存实例，供导入回调使用。 */
    private MineView currentMine;

    public void openWord(String word) {
        Intent i = new Intent(this, com.englishstudy.app.ui.WordActivity.class);
        i.putExtra("word", word);
        startActivity(i);
    }
}
