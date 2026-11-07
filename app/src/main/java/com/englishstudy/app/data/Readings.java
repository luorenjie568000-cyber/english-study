package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 分级阅读：assets/readings.json。 */
public final class Readings {

    public static final class Passage {
        public String id = "";
        public String level = "A1";
        public String title = "";
        public String titleZh = "";
        public String blurb = "";
        public String text = "";

        public String[] sentences() {
            return text.split("(?<=[.!?])\\s+");
        }

        public int words() {
            String t = text.trim();
            return t.isEmpty() ? 0 : t.split("\\s+").length;
        }
    }

    private static final List<Passage> LIST = new ArrayList<>();
    private static volatile boolean ready;

    private Readings() {
    }

    public static List<Passage> all() {
        return LIST;
    }

    public static boolean ready() {
        return ready;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("readings.json"), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(1 << 15);
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Passage p = new Passage();
                p.id = o.optString("id");
                p.level = o.optString("level");
                p.title = o.optString("title");
                p.titleZh = o.optString("titleZh");
                p.blurb = o.optString("blurb");
                p.text = o.optString("text");
                LIST.add(p);
            }
        } catch (Throwable e) {
            Log.e("Readings", "load failed", e);
        }
        ready = true;
    }
}
