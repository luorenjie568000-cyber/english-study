package com.englishstudy.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.englishstudy.app.data.Dict;
import com.englishstudy.app.ui.U;

/** 最小验证版：确认构建链路与词典加载。 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = U.col(this);
        root.setBackgroundColor(getColor(R.color.bg));
        root.setGravity(Gravity.CENTER);

        TextView t = U.bold(this, "英语学习", 24, getColor(R.color.text));
        t.setGravity(Gravity.CENTER);
        root.addView(t);

        root.addView(U.spacer(this, 12));

        final TextView info = U.tv(this, "词典加载中…", 14, getColor(R.color.text_dim));
        info.setGravity(Gravity.CENTER);
        root.addView(info);

        setContentView(root);

        new Thread(() -> {
            for (int i = 0; i < 100 && !Dict.ready(); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            runOnUiThread(() -> info.setText(Dict.ready()
                    ? "词典就绪 · " + Dict.size() + " 词"
                    : "词典加载失败"));
        }).start();
    }
}
