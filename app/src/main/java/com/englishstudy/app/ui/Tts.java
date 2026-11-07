package com.englishstudy.app.ui;

import android.app.Activity;
import android.content.Context;

import com.englishstudy.app.voice.OfflineTtsEngine;

/**
 * 朗读门面。
 *
 * 优先走本地 VITS 模型（sherpa-onnx，完全离线、音色自然）；
 * 模型未下载时 OfflineTtsEngine 内部自动降级到系统 TTS（同样离线）。
 * 调用方只认 say / setRate / stop 三个口子。
 */
public final class Tts {

    private static boolean inited;

    private Tts() {
    }

    public static void init(Activity a) {
        init((Context) a);
    }

    public static void init(Context c) {
        if (inited) return;
        inited = true;
        OfflineTtsEngine.setRate(com.englishstudy.app.data.Store.getInt("rate", 92) / 100f);
        OfflineTtsEngine.init(c.getApplicationContext());
    }

    public static void setRate(float r) {
        OfflineTtsEngine.setRate(r);
    }

    public static void say(Activity a, String text) {
        if (text == null || text.trim().isEmpty()) return;
        init(a);
        OfflineTtsEngine.say(a.getApplicationContext(), text);
    }

    /** 指定语速朗读（跟读练习用：0.7 慢速 / 1.0 正常）。 */
    public static void say(Activity a, String text, float rate) {
        if (text == null || text.trim().isEmpty()) return;
        init(a);
        OfflineTtsEngine.say(a.getApplicationContext(), text, rate);
    }

    public static void stop() {
        OfflineTtsEngine.stop();
    }

    public static void shutdown() {
        OfflineTtsEngine.shutdown();
        inited = false;
    }
}
