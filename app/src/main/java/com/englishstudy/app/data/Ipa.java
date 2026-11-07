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

/** 国际音标：assets/ipa.json（44 音素）。 */
public final class Ipa {

    public static final class Phone {
        public String sym = "";
        public String name = "";
        public String type = "";
        public String example = "";
        public String ipa = "";
        public String zh = "";
        public String tip = "";
        public String commonErr = "";
    }

    private static final List<Phone> VOWELS = new ArrayList<>();
    private static final List<Phone> CONSONANTS = new ArrayList<>();
    private static volatile boolean ready;

    private Ipa() {
    }

    public static List<Phone> vowels() {
        return VOWELS;
    }

    public static List<Phone> consonants() {
        return CONSONANTS;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("ipa.json"), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(1 << 14);
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject root = new JSONObject(sb.toString());
            read(root.optJSONArray("vowels"), VOWELS);
            read(root.optJSONArray("consonants"), CONSONANTS);
        } catch (Throwable e) {
            Log.e("Ipa", "load failed", e);
        }
        ready = true;
    }

    private static void read(JSONArray arr, List<Phone> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Phone p = new Phone();
            p.sym = o.optString("sym");
            p.name = o.optString("name");
            p.type = o.optString("type");
            p.example = o.optString("example");
            p.ipa = o.optString("ipa");
            p.zh = o.optString("zh");
            p.tip = o.optString("tip");
            p.commonErr = o.optString("commonErr");
            out.add(p);
        }
    }
}
