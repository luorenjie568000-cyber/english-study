package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.englishstudy.app.MainActivity;
import com.englishstudy.app.R;
import com.englishstudy.app.data.Books;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Store;
import com.englishstudy.app.data.Vocab;
import com.englishstudy.app.engine.Card;
import com.englishstudy.app.engine.Review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 学习：一题一屏，答题后四档自评（FSRS）。 */
public class StudyActivity extends Activity {

    public static final int MODE_REVIEW = 0;
    public static final int MODE_NEW = 1;

    private final List<Card> queue = new ArrayList<>();
    private int index;
    private int right, wrong;
    private boolean answered;

    private Card card;
    private Dict.Entry entry;

    private TextView progressText;
    private ProgressBar bar;
    private LinearLayout body;
    private ScrollView scroll;
    private LinearLayout gradeBar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int mode = getIntent().getIntExtra("mode", MODE_REVIEW);

        LinearLayout root = U.col(this);
        root.setBackgroundColor(getColor(R.color.bg));

        LinearLayout top = U.row(this);
        top.setPadding(U.px(this, 16), U.px(this, 14), U.px(this, 16), U.px(this, 6));
        TextView close = U.tv(this, "✕", 20, getColor(R.color.text_dim));
        close.setPadding(U.px(this, 4), 0, U.px(this, 12), 0);
        close.setOnClickListener(v -> finish());
        top.addView(close);
        progressText = U.tv(this, "0 / 0", 13, getColor(R.color.text_dim));
        top.addView(progressText, U.weight(1));
        LinearLayout modeTag = U.pill(this, mode == MODE_NEW ? "新词" : "复习",
                getColor(R.color.primary));
        top.addView(modeTag);
        root.addView(top);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, U.px(this, 3));
        blp.leftMargin = U.px(this, 16);
        blp.rightMargin = U.px(this, 16);
        root.addView(bar, blp);

        ScrollView sv = new ScrollView(this);
        scroll = sv;
        body = U.col(this);
        int p = U.px(this, 16);
        body.setPadding(p, U.px(this, 14), p, U.px(this, 24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 评分栏固定在底部：释义再长也不会把按钮挤到屏幕外
        gradeBar = U.col(this);
        int gp = U.px(this, 12);
        gradeBar.setPadding(gp, gp, gp, gp);
        gradeBar.setBackgroundColor(getColor(R.color.bg2));
        gradeBar.setVisibility(android.view.View.GONE);
        root.addView(gradeBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        fill(mode);
        next();
    }

    /** 组队列。 */
    private void fill(int mode) {
        queue.clear();
        long now = System.currentTimeMillis();

        if (mode == MODE_REVIEW) {
            int cap = Math.max(5, Store.getInt("limit.review", 200));
            queue.addAll(Vocab.due(now, cap));
            if (queue.isEmpty()) {
                Toast.makeText(this, "没有到期的词，去学新词吧", Toast.LENGTH_SHORT).show();
                finish();
            }
            return;
        }

        // 教材单元模式
        String unitWords = Store.get("book.words", "");
        List<String> candidates = new ArrayList<>();
        if (!unitWords.isEmpty()) {
            for (String w : unitWords.split("\n")) {
                String t = w.trim();
                if (!t.isEmpty()) candidates.add(t);
            }
        } else {
            String tag = Store.get("book", "gk");
            candidates = Dict.byTag(tag, 300);
            int offset = Store.getInt("book_offset_" + tag, 0);
            if (offset > 0 && offset < candidates.size()) {
                candidates = new ArrayList<>(candidates.subList(offset, candidates.size()));
            }
            Store.putInt("book_offset_" + tag, offset + 20);
        }

        int want = Math.min(Store.getInt("goal", 30), Store.getInt("limit.new", 30));
        for (String w : candidates) {
            if (queue.size() >= Math.max(10, want)) break;
            if (Vocab.get(w) != null) continue;
            queue.add(Vocab.getOrCreate(w));
        }
        if (queue.isEmpty()) {
            Toast.makeText(this, "这个词书已经学完了", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void next() {
        if (index >= queue.size()) {
            done();
            return;
        }
        card = queue.get(index);
        entry = Dict.lookup(card.w);
        answered = false;
        render();
        // 新题回到顶部，否则会停在上一题的滚动位置
        if (scroll != null) scroll.scrollTo(0, 0);
    }

    private void render() {
        body.removeAllViews();
        progressText.setText((index + 1) + " / " + queue.size());
        bar.setProgress((int) (100f * index / Math.max(1, queue.size())));

        // 词卡
        LinearLayout cardBox = U.card(this);
        cardBox.setGravity(Gravity.CENTER_HORIZONTAL);
        cardBox.setPadding(U.px(this, 16), U.px(this, 26), U.px(this, 16), U.px(this, 26));

        TextView word = new TextView(this);
        word.setText(card.w);
        word.setTextColor(getColor(R.color.text));
        word.setGravity(Gravity.CENTER);
        word.setTextSize(32);
        word.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cardBox.addView(word);

        String ipa = entry == null ? "" : entry.displayIpa();
        if (!ipa.isEmpty()) {
            TextView ph = U.tv(this, "/" + ipa + "/", 14, getColor(R.color.text_dim));
            ph.setGravity(Gravity.CENTER);
            ph.setPadding(0, U.px(this, 8), 0, 0);
            cardBox.addView(ph);
        }

        TextView play = U.tv(this, "🔊 朗读", 13, getColor(R.color.primary));
        play.setGravity(Gravity.CENTER);
        play.setPadding(0, U.px(this, 14), 0, 0);
        play.setOnClickListener(v -> Tts.say(this, card.w));
        cardBox.addView(play);
        body.addView(cardBox);

        body.addView(U.spacer(this, 14));

        if (answered) {
            renderAnswer();
        } else {
            renderReveal();
        }
    }

    /** 未作答：只显示「显示释义」。 */
    private void renderReveal() {
        gradeBar.removeAllViews();
        gradeBar.setVisibility(android.view.View.GONE);
        TextView show = U.primaryButton(this, "显示释义");
        show.setOnClickListener(v -> {
            answered = true;
            render();
        });
        body.addView(show);

        body.addView(U.spacer(this, 12));
        LinearLayout tip = U.card(this);
        tip.addView(U.tv(this, "先在心里回想意思，再点开对照——比直接看答案记得牢",
                12, getColor(R.color.text_dim2)));
        body.addView(tip);
    }

    /** 已作答：释义 + 例句 + 四档自评。 */
    private void renderAnswer() {
        LinearLayout defCard = U.card(this);
        defCard.addView(U.bold(this, "释义", 12, getColor(R.color.text_dim2)));
        defCard.addView(U.spacer(this, 6));
        defCard.addView(U.tv(this, entry == null ? "（词典未收录）" : entry.def, 16,
                getColor(R.color.text)));

        if (entry != null && !entry.defin.isEmpty()) {
            defCard.addView(U.spacer(this, 8));
            TextView en = U.tv(this, entry.defin, 12, getColor(R.color.text_dim));
            en.setLineSpacing(U.dp(this, 3), 1f);
            defCard.addView(en);
        }
        body.addView(defCard);

        // 例句（中文默认隐藏，点一下全展开）
        Sentences.load(this);
        List<Sentences.Item> ex = Sentences.items(card.w);

        // 克漏字：先只看挖空句，自己回想该填什么，再点开对照
        boolean clozeOn = Store.getInt("cloze", 1) == 1;
        if (clozeOn && !ex.isEmpty()) {
            body.addView(U.spacer(this, 10));
            LinearLayout cz = U.card(this);
            cz.addView(U.bold(this, "克漏字 · 空里该填什么", 12,
                    getColor(R.color.text_dim2)));
            cz.addView(U.spacer(this, 8));
            int n = Math.min(2, ex.size());
            for (int i = 0; i < n; i++) {
                TextView t = U.tv(this, cloze(ex.get(i).en, card.w), 15,
                        getColor(R.color.text));
                t.setLineSpacing(U.dp(this, 5), 1f);
                t.setPadding(0, U.px(this, 6), 0, U.px(this, 6));
                final String full = ex.get(i).en;
                t.setOnClickListener(v -> {
                    if (t.getText().toString().contains("____")) {
                        t.setText(full);
                        t.setTextColor(getColor(R.color.accent));
                    } else {
                        t.setText(cloze(full, card.w));
                        t.setTextColor(getColor(R.color.text));
                    }
                });
                cz.addView(t);
            }
            cz.addView(U.tv(this, "点句子看答案", 10, getColor(R.color.text_dim2)));
            body.addView(cz);
        }
        if (!ex.isEmpty()) {
            body.addView(U.spacer(this, 10));
            LinearLayout exCard = U.card(this);

            LinearLayout exHead = U.row(this);
            exHead.addView(U.bold(this, "例句", 12, getColor(R.color.text_dim2)), U.weight(1));
            final boolean[] zhOn = {Store.getInt("ex.zh", 0) == 1};
            TextView zhBtn = U.tv(this, zhOn[0] ? "隐藏中文" : "看中文", 12,
                    getColor(R.color.primary));
            zhBtn.setPadding(U.px(this, 8), U.px(this, 2), U.px(this, 8), U.px(this, 2));
            zhBtn.setBackgroundResource(R.drawable.bg_pill);
            exHead.addView(zhBtn);
            exCard.addView(exHead);

            LinearLayout exBody = U.col(this);
            exCard.addView(exBody);
            final Runnable rebuild = () -> {
                exBody.removeAllViews();
                for (Sentences.Item it : ex) {
                    TextView t = U.tv(this, it.en, 14, getColor(R.color.text));
                    t.setPadding(0, U.px(this, 8), 0, 0);
                    t.setLineSpacing(U.dp(this, 4), 1f);
                    t.setOnClickListener(v -> Tts.say(this, it.en));
                    exBody.addView(t);
                    if (zhOn[0]) {
                        TextView z = U.tv(this, it.hasZh() ? it.zh : "（暂无译文）", 12,
                                getColor(it.hasZh() ? R.color.text_dim : R.color.text_dim2));
                        z.setPadding(0, U.px(this, 3), 0, 0);
                        z.setLineSpacing(U.dp(this, 3), 1f);
                        exBody.addView(z);
                    }
                }
            };
            rebuild.run();
            zhBtn.setOnClickListener(v -> {
                zhOn[0] = !zhOn[0];
                Store.putInt("ex.zh", zhOn[0] ? 1 : 0);
                zhBtn.setText(zhOn[0] ? "隐藏中文" : "看中文");
                rebuild.run();
            });
            body.addView(exCard);
        }

        // 四档（固定底栏）
        gradeBar.removeAllViews();
        int[] preview = Review.preview(card, System.currentTimeMillis());
        String[] labels = {"忘了", "勉强", "记得", "太简单"};
        int[] colors = {R.color.danger, R.color.warn, R.color.primary, R.color.accent};

        LinearLayout row = U.row(this);
        for (int g = 1; g <= 4; g++) {
            LinearLayout box = U.col(this);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundResource(R.drawable.bg_tile);
            box.setPadding(U.px(this, 6), U.px(this, 12), U.px(this, 6), U.px(this, 12));

            TextView lb = U.bold(this, labels[g - 1], 14, getColor(colors[g - 1]));
            lb.setGravity(Gravity.CENTER);
            box.addView(lb);

            String iv = preview[g - 1] <= 0 ? "10 分钟" : preview[g - 1] + " 天";
            TextView s = U.tv(this, iv, 11, getColor(R.color.text_dim2));
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, U.px(this, 3), 0, 0);
            box.addView(s);

            final int grade = g;
            box.setOnClickListener(v -> applyGrade(grade));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = U.px(this, 3);
            lp.rightMargin = U.px(this, 3);
            row.addView(box, lp);
        }
        gradeBar.addView(row);
        gradeBar.setVisibility(android.view.View.VISIBLE);
    }

    /** 把例句里的目标词（含变形）换成下划线，做克漏字。 */
    private String cloze(String sentence, String word) {
        if (sentence == null || word == null || word.isEmpty()) return sentence;
        String lower = sentence.toLowerCase(java.util.Locale.US);
        String w = word.toLowerCase(java.util.Locale.US);

        // 先精确匹配，再试常见变形
        String[] forms = {w, w + "s", w + "es", w + "ed", w + "d", w + "ing",
                w + "'s", w + "er", w + "est"};
        for (String f : forms) {
            int idx = indexOfWord(lower, f);
            if (idx >= 0) {
                return sentence.substring(0, idx) + "____"
                        + sentence.substring(idx + f.length());
            }
        }
        // 词形还原后匹配（gave -> give 这类靠 lemma，这里做简单截断兜底）
        String base = Dict.lemmaOf(w);
        if (base != null && !base.equals(w)) {
            int idx = indexOfWord(lower, base);
            if (idx >= 0) {
                return sentence.substring(0, idx) + "____"
                        + sentence.substring(idx + base.length());
            }
        }
        return sentence;
    }

    /** 只在词边界上匹配，避免 "in" 命中 "interesting"。 */
    private int indexOfWord(String text, String word) {
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) return -1;
            boolean leftOk = i == 0 || !Character.isLetter(text.charAt(i - 1));
            int end = i + word.length();
            boolean rightOk = end >= text.length() || !Character.isLetter(text.charAt(end));
            if (leftOk && rightOk) return i;
            from = i + 1;
        }
    }

    private void applyGrade(int g) {
        long now = System.currentTimeMillis();
        Review.apply(card, g, now);
        Vocab.put(card);
        com.englishstudy.app.data.Streak.mark(1);
        if (g == 1) {
            wrong++;
            if (index < queue.size() - 1) queue.add(card);   // 塞回队尾再看一次
        } else {
            right++;
        }
        index++;
        if (index >= queue.size()) {
            done();
        } else {
            next();
        }
    }

    private void done() {
        Vocab.flush();
        body.removeAllViews();
        gradeBar.removeAllViews();
        gradeBar.setVisibility(android.view.View.GONE);
        bar.setProgress(100);
        progressText.setText("完成");

        LinearLayout card = U.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = U.px(this, 24);
        card.setPadding(p, p, p, p);
        card.addView(U.bold(this, "本轮完成", 22, getColor(R.color.text)));
        TextView stat = U.tv(this, "答对 " + right + " · 答错 " + wrong, 14,
                getColor(R.color.text_dim));
        stat.setPadding(0, U.px(this, 10), 0, 0);
        card.addView(stat);
        body.addView(card);

        body.addView(U.spacer(this, 16));
        TextView back = U.primaryButton(this, "回到首页");
        back.setOnClickListener(v -> finish());
        body.addView(back);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Tts.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Vocab.flush();
    }
}
