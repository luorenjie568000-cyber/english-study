package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 例句库：assets/sents4.tsv.gz，格式 word \t 句1 \x1f 句2 … */
public final class Sentences {

    private static final String TAG = "Sentences";
    private static final Map<String, List<String>> MAP = new HashMap<>(8192);
    private static volatile boolean ready;

    private Sentences() {
    }

    public static boolean ready() {
        return ready;
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        long t0 = System.currentTimeMillis();
        try {
            GZIPInputStream gz = new GZIPInputStream(
                    new BufferedInputStream(c.getAssets().open("sents4.tsv.gz"), 1 << 15));
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(gz, StandardCharsets.UTF_8), 1 << 15);
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String w = line.substring(0, tab);
                String[] parts = line.substring(tab + 1).split("\u001f");
                List<String> list = new ArrayList<>(parts.length);
                for (String s : parts) {
                    String t = s.trim();
                    if (!t.isEmpty()) list.add(t);
                }
                if (!list.isEmpty()) MAP.put(w, list);
            }
            r.close();
        } catch (Throwable e) {
            Log.e(TAG, "load failed", e);
        }
        ready = true;
        Log.i(TAG, "loaded " + MAP.size() + " words in "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 取例句，自动做词形还原。 */
    public static List<String> of(String word) {
        if (word == null) return new ArrayList<>(0);
        String w = word.toLowerCase();
        List<String> l = MAP.get(w);
        if (l != null) return l;

        String base = Dict.lemmaOf(w);
        if (base != null) {
            l = MAP.get(base);
            if (l != null) return l;
        }
        for (String c : Dict.lemmas(w)) {
            l = MAP.get(c);
            if (l != null) return l;
        }
        return new ArrayList<>(0);
    }

    public static int wordCount() {
        return MAP.size();
    }
}
