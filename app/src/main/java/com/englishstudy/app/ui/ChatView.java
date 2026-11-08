package com.englishstudy.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.englishstudy.app.R;
import com.englishstudy.app.data.Agent;
import com.englishstudy.app.data.Dict;
import com.englishstudy.app.data.Sentences;
import com.englishstudy.app.data.Store;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话：学伴 Agent。
 *
 * 没配 API Key 时用 OfflineTutor（纯本地：查词/翻译/批改/复习）。
 * 配了就走 OpenAI 兼容接口，模型可以调用本地工具。
 */
public class ChatView {

    private final Activity a;
    private LinearLayout root;
    private LinearLayout feed;
    private ScrollView scroll;
    private EditText input;
    private TextView send;
    private boolean busy;

    /** 界面上的消息（含本地回显）。 */
    private final List<String[]> bubbles = new ArrayList<>();  // {role, text}

    public ChatView(Activity a) {
        this.a = a;
    }

    public View build() {
        LinearLayout outer = U.col(a);
        outer.setBackgroundColor(Theme.c(a, R.color.bg));

        // 顶部
        LinearLayout top = U.row(a);
        top.setPadding(U.px(a, 16), U.px(a, 14), U.px(a, 12), U.px(a, 8));
        LinearLayout titleBox = U.col(a);
        titleBox.addView(U.bold(a, "学伴", 22, Theme.c(a, R.color.text)));
        titleBox.addView(U.tv(a, subtitle(), 11, Theme.c(a, R.color.text_dim2)));
        top.addView(titleBox, U.weight(1));

        TextView gear = U.tv(a, "设置", 13, Theme.c(a, R.color.primary));
        gear.setPadding(U.px(a, 12), U.px(a, 8), U.px(a, 12), U.px(a, 8));
        gear.setBackground(U.pillBg(a));
        gear.setOnClickListener(v -> showSettings());
        top.addView(gear);
        outer.addView(top);

        // 消息流
        scroll = new ScrollView(a);
        feed = U.col(a);
        int p = U.px(a, 14);
        feed.setPadding(p, 0, p, U.px(a, 10));
        scroll.addView(feed);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 快捷入口
        LinearLayout chips = U.row(a);
        chips.setPadding(U.px(a, 14), U.px(a, 4), U.px(a, 14), U.px(a, 6));
        for (String c : new String[]{"apple 怎么读", "今天复习什么", "翻译这句", "改我的句子"}) {
            TextView chip = U.tv(a, c, 11, Theme.c(a, R.color.text_dim));
            chip.setPadding(U.px(a, 10), U.px(a, 5), U.px(a, 10), U.px(a, 5));
            chip.setBackground(U.pillBg(a));
            chip.setOnClickListener(v -> {
                input.setText(c);
                input.setSelection(c.length());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = U.px(a, 6);
            chips.addView(chip, lp);
        }
        outer.addView(chips);

        // 输入区
        LinearLayout bar = U.row(a);
        bar.setPadding(U.px(a, 12), U.px(a, 8), U.px(a, 12), U.px(a, 14));
        bar.setBackgroundColor(Theme.c(a, R.color.bg2));

        input = new EditText(a);
        input.setHint("问点什么…");
        input.setHintTextColor(Theme.c(a, R.color.text_dim2));
        input.setTextColor(Theme.c(a, R.color.text));
        input.setTextSize(15);
        input.setBackground(U.tileBg(a));
        input.setPadding(U.px(a, 14), U.px(a, 10), U.px(a, 14), U.px(a, 10));
        input.setMaxLines(4);
        bar.addView(input, U.weight(1));

        send = U.bold(a, "发送", 14, Theme.c(a, R.color.white));
        send.setBackground(primaryBg(a));
        send.setGravity(Gravity.CENTER);
        send.setPadding(U.px(a, 16), U.px(a, 10), U.px(a, 16), U.px(a, 10));
        send.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = U.px(a, 8);
        bar.addView(send, slp);
        outer.addView(bar);

        // 开场白只放一次
        if (bubbles.isEmpty()) {
            bubbles.add(new String[]{"agent", greeting()});
        }
        renderAll();
        return outer;
    }

    private android.graphics.drawable.Drawable primaryBg(Activity ctx) {
        return U.primaryBg(ctx);
    }

    private android.graphics.drawable.Drawable bubbleBg(Activity ctx) {
        return U.bubbleBg(ctx);
    }

    private String subtitle() {
        if (Agent.localMode()) {
            String st = com.englishstudy.app.voice.LocalLlm.loaded()
                    ? "本地模型已加载 · 完全离线" : "本地模型 · 首次提问时加载";
            return st;
        }
        if (Agent.configured()) return "已接入 " + Agent.modelName() + " · 可调本地工具";
        return "离线模式 · 查词/翻译/批改/复习";
    }

    private String greeting() {
        if (Agent.configured()) {
            return "你好，我是学伴。可以查词、翻译、改句子，也能帮你把词加进生词本、"
                    + "看看今天要复习什么。想聊什么？";
        }
        return "你好，我是学伴（离线模式）。\n"
                + "· 查词：`apple 怎么读`\n"
                + "· 翻译：`翻译 The early bird catches the worm.`\n"
                + "· 批改：直接贴一句你写的英文\n"
                + "· 复习：`今天复习什么`\n"
                + "想自由聊天，点右上角「设置」填一个 OpenAI 兼容接口。";
    }

    // ---------------------------------------------------------------- 渲染

    private void renderAll() {
        feed.removeAllViews();
        for (String[] b : bubbles) addBubble(b[0], b[1]);
        scrollBottom();
    }

    private void addBubble(String role, String text) {
        boolean mine = "user".equals(role);

        LinearLayout wrap = U.col(a);
        wrap.setPadding(0, U.px(a, 6), 0, 0);

        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setLineSpacing(U.dp(a, 5), 1f);
        tv.setTextColor(Theme.c(a, mine ? R.color.white : R.color.text));
        tv.setBackground(mine ? primaryBg(a) : bubbleBg(a));
        int ph = U.px(a, 13), pv = U.px(a, 10);
        tv.setPadding(ph, pv, ph, pv);
        tv.setTextIsSelectable(true);

        LinearLayout row = U.row(a);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        // 限制气泡最大宽度，避免长句占满整行
        lp.weight = 0;
        tv.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.82));
        row.addView(tv, lp);
        wrap.addView(row);
        feed.addView(wrap);
    }

    private void scrollBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------- 发送

    private void submit() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");

        bubbles.add(new String[]{"user", text});
        addBubble("user", text);

        final TextView thinking = U.tv(a, "…", 13, Theme.c(a, R.color.text_dim2));
        thinking.setPadding(U.px(a, 4), U.px(a, 8), 0, 0);
        feed.addView(thinking);
        scrollBottom();

        busy = true;
        send.setAlpha(0.5f);
        new Thread(() -> {
            String reply;
            try {
                reply = Agent.chat(text);
            } catch (Throwable e) {
                reply = "出错了：" + e.getMessage()
                        + "\n\n检查一下右上角「设置」里的地址和 Key，或者先清空用离线模式。";
            }
            final String out = reply;
            a.runOnUiThread(() -> {
                feed.removeView(thinking);
                busy = false;
                send.setAlpha(1f);
                bubbles.add(new String[]{"agent", out});
                addBubble("agent", out);
                scrollBottom();
            });
        }, "agent").start();
    }

    // ---------------------------------------------------------------- 设置

    private void showSettings() {
        LinearLayout box = U.col(a);
        int p = U.px(a, 18);
        box.setPadding(p, p, p, p);

        box.addView(U.tv(a, "任何 OpenAI 兼容接口都行（OpenAI / DeepSeek / 通义 / "
                + "本地 Ollama / LM Studio）。不填就一直用离线模式。", 12,
                Theme.c(a, R.color.text_dim2)));
        box.addView(U.spacer(a, 12));

        final EditText base = field(box, "接口地址", "https://api.openai.com/v1",
                Store.get("ai.base", ""));
        final EditText key = field(box, "API Key", "sk-…", Store.get("ai.key", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText model = field(box, "模型名", "gpt-4o-mini", Agent.modelName());

        box.addView(U.spacer(a, 8));
        TextView hint = U.tv(a, "提示：Key 只存在本机 SharedPreferences，不会上传到别处。",
                11, Theme.c(a, R.color.text_dim2));
        box.addView(hint);
        box.addView(U.spacer(a, 14));

        // 本地模型开关（不联网也能用）
        LinearLayout local = U.row(a);
        local.setPadding(0, U.px(a, 10), 0, U.px(a, 10));
        String lm = com.englishstudy.app.voice.LocalLlm.doneBytes(a,
                com.englishstudy.app.voice.LocalLlm.pack()) > 0
                ? "已下载 " + (com.englishstudy.app.voice.LocalLlm.doneBytes(a,
                        com.englishstudy.app.voice.LocalLlm.pack()) / 1048576) + "MB"
                : "未下载";
        local.addView(U.tv(a, "本地模型（端侧）", 14, Theme.c(a, R.color.text)), U.weight(1));
        local.addView(U.tv(a, Agent.localMode() ? "已启用 · " + lm : lm + " ›", 12,
                Theme.c(a, R.color.primary)));
        local.setOnClickListener(v -> LocalModelPanel.show(a));
        box.addView(local);

        new AlertDialog.Builder(a)
                .setTitle("学伴设置")
                .setView(box)
                .setNegativeButton("取消", null)
                .setNeutralButton("清空", (d, w) -> {
                    Store.put("ai.base", "");
                    Store.put("ai.key", "");
                    Store.put("ai.model", "");
                    Agent.reset();
                    ((com.englishstudy.app.MainActivity) a).refresh();
                })
                .setPositiveButton("保存", (d, w) -> {
                    Store.put("ai.base", base.getText().toString().trim());
                    Store.put("ai.key", key.getText().toString().trim());
                    Store.put("ai.model", model.getText().toString().trim());
                    Agent.reset();
                    ((com.englishstudy.app.MainActivity) a).refresh();
                })
                .show();
    }

    private EditText field(LinearLayout box, String label, String hint, String value) {
        box.addView(U.tv(a, label, 12, Theme.c(a, R.color.text_dim)));
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value);
        e.setHintTextColor(Theme.c(a, R.color.text_dim2));
        e.setTextColor(Theme.c(a, R.color.text));
        e.setTextSize(14);
        e.setBackground(U.tileBg(a));
        int ph = U.px(a, 12), pv = U.px(a, 8);
        e.setPadding(ph, pv, ph, pv);
        e.setSingleLine(true);
        box.addView(e);
        box.addView(U.spacer(a, 10));
        return e;
    }
}
