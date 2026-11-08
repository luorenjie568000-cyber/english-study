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
import com.englishstudy.app.ui.FloatingBar;
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

    public static final String[] TABS = {"学习", "词汇", "口语", "写作", "阅读", "我的"};

    private FrameLayout content;
    private FloatingBar floating;
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
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // ══ 真正的悬浮：底栏加在 shell（FrameLayout）里，不占布局空间
        //
        // 之前加在垂直 LinearLayout 里 → 它是布局的一部分，高度会把内容顶上去，
        // 底部永远是它自己的区域，所以看起来像"贴图"。
        // 现在内容占满整屏，底栏用 gravity=bottom 浮在上面，内容能滚到它背后。
        floating = new FloatingBar(this, TABS);
        floating.setOnTab(this::select);
        floating.bindBackdrop(content);
        FrameLayout.LayoutParams fbp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, U.px(this, 52));
        fbp.gravity = Gravity.BOTTOM;
        fbp.leftMargin = U.px(this, 22);
        fbp.rightMargin = U.px(this, 22);
        fbp.bottomMargin = U.px(this, 24);
        shell.addView(floating, fbp);
        floating.setElevation(U.px(this, 8));

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
        if (floating != null) floating.markSelected(idx);
        content.removeAllViews();
        View v = build(idx);
        content.addView(v, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 给内容底部补足胶囊高度 + 边距，否则最后一行会被盖住
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                v.getPaddingBottom() + U.px(this, 52 + 24 + 8));
        // 内容变了 → 重新抓一次模糊快照（延迟到布局完成后）
        if (floating != null) floating.postDelayed(floating::refreshBackdrop, 80);
    }

    private View build(int idx) {
        switch (idx) {
            case 0:
                return new HomeView(this).build();
            case 1:
                return new VocabView(this).build();
            case 2:
                return new SpeakView(this).build();
            case 3:
                return new WriteView(this).build();
            case 4:
                currentRead = new ReadView(this);
                return currentRead.build();
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
        if (requestCode == 0x91) {
            if (currentRead != null) currentRead.onArticleFile(uri);
        } else if (currentMine != null) {
            currentMine.onFilePicked(requestCode, uri);
        }
    }

    /** 当前显示「我的」页时保存实例，供导入回调使用。 */
    private MineView currentMine;
    private ReadView currentRead;

    public void openWord(String word) {
        Intent i = new Intent(this, com.englishstudy.app.ui.WordActivity.class);
        i.putExtra("word", word);
        startActivity(i);
    }
}
