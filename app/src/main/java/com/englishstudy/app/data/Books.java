package com.englishstudy.app.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教材词书：assets/books_gaokao.json
 * 结构 {"必修第一册": {"Unit 1": ["word", ...]}, ...}
 *
 * 词条释义统一回 {@link Dict} 取（ECDICT/MIT），本类只负责分册结构。
 */
public final class Books {

    public static final class Book {
        public final String name;
        public final Map<String, List<String>> units = new LinkedHashMap<>();
        public int count;

        Book(String name) {
            this.name = name;
        }
    }

    private static final List<Book> LIST = new ArrayList<>();
    private static volatile boolean ready;

    private Books() {
    }

    public static synchronized void load(Context c) {
        if (ready) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("books_gaokao.json"), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(1 << 14);
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject root = new JSONObject(sb.toString());
            List<Book> out = new ArrayList<>();
            Iterator<String> names = root.keys();
            while (names.hasNext()) {
                String bookName = names.next();
                JSONObject units = root.getJSONObject(bookName);
                Book b = new Book(bookName);
                int total = 0;
                Iterator<String> us = units.keys();
                while (us.hasNext()) {
                    String unitName = us.next();
                    org.json.JSONArray arr = units.getJSONArray(unitName);
                    List<String> ws = new ArrayList<>(arr.length());
                    for (int i = 0; i < arr.length(); i++) {
                        String w = arr.getString(i).trim();
                        if (!w.isEmpty()) ws.add(w);
                    }
                    if (!ws.isEmpty()) {
                        b.units.put(unitName, ws);
                        total += ws.size();
                    }
                }
                b.count = total;
                if (total > 0) out.add(b);
            }
            Collections.sort(out, (a, c2) -> rank(a.name) - rank(c2.name));
            LIST.addAll(out);
        } catch (Throwable e) {
            Log.e("Books", "load failed", e);
        }
        ready = true;
    }

    /** 必修一=1 二=2 三=3；选必修一=11 … 四=14。 */
    private static int rank(String name) {
        boolean sel = name.startsWith("选");
        String cn = sel ? name.substring(3) : name.substring(2);
        int n;
        if (cn.startsWith("一")) n = 1;
        else if (cn.startsWith("二")) n = 2;
        else if (cn.startsWith("三")) n = 3;
        else if (cn.startsWith("四")) n = 4;
        else n = 9;
        return (sel ? 10 : 0) + n;
    }

    public static List<Book> all() {
        return LIST;
    }

    public static Book find(String name) {
        for (Book b : LIST) if (b.name.equals(name)) return b;
        return null;
    }

    public static int totalWords() {
        int n = 0;
        for (Book b : LIST) n += b.count;
        return n;
    }

    /** 整册的全部词（去重，保持顺序）。 */
    public static List<String> allWords(Book b) {
        List<String> out = new ArrayList<>();
        for (List<String> ws : b.units.values()) {
            for (String w : ws) if (!out.contains(w)) out.add(w);
        }
        return out;
    }
}
