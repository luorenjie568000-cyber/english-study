package com.englishstudy.app.ui;

import android.app.Activity;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * 朗读门面。
 *
 * 当前用系统离线 TTS（任何设备都有，零下载）。
 * 若离线 VITS 模型就绪，由 OfflineTtsEngine 接管（后续接入点）。
 */
public final class Tts {

    private static TextToSpeech engine;
    private static boolean ready;
    private static float rate = 0.92f;

    private Tts() {
    }

    public static void init(Activity a) {
        if (engine != null) return;
        engine = new TextToSpeech(a.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = engine.setLanguage(Locale.US);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("Tts", "US English not available");
                }
                engine.setSpeechRate(rate);
                ready = true;
            }
        });
    }

    public static void setRate(float r) {
        rate = Math.max(0.5f, Math.min(1.6f, r));
        if (engine != null && ready) engine.setSpeechRate(rate);
    }

    public static void say(Activity a, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (engine == null) init(a);
        if (engine == null) return;
        if (!ready) {
            final String t = text;
            a.runOnUiThread(() -> a.getWindow().getDecorView().postDelayed(
                    () -> say(a, t), 300));
            return;
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "es");
    }

    public static void stop() {
        if (engine != null) engine.stop();
    }

    public static void shutdown() {
        if (engine != null) {
            engine.stop();
            engine.shutdown();
            engine = null;
            ready = false;
        }
    }
}
