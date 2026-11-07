package com.englishstudy.app.data;

import android.content.Context;

import com.englishstudy.app.engine.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 生词本：全量卡片常驻内存，变更后批量落盘。 */
public final class Vocab {

    private static final Map<String, Card> MAP = new HashMap<>(4096);
    private static boolean dirty;
    private static long lastFlush;
    private static Context ctx;
    private static volatile boolean loaded;

    private Vocab() {
    }

    public static void init(Context c) {
        ctx = c.getApplicationContext();
        Store.init(ctx);
        load();
    }

    private static synchronized void load() {
        if (loaded) return;
        String blob = Store.get("cards", "");
        if (!blob.isEmpty()) {
            for (String line : blob.split("\n")) {
                Card c = Card.decode(line);
                if (c != null) MAP.put(c.w, c);
            }
        }
        loaded = true;
    }

    public static synchronized void flush() {
        if (!dirty || ctx == null) return;
        StringBuilder sb = new StringBuilder(MAP.size() * 48);
        for (Card c : MAP.values()) {
            sb.append(c.encode()).append('\n');
        }
        Store.put("cards", sb.toString());
        dirty = false;
        lastFlush = System.currentTimeMillis();
    }

    private static void touch() {
        dirty = true;
        if (System.currentTimeMillis() - lastFlush > 5000) flush();
    }

    public static synchronized Card get(String w) {
        return MAP.get(w.toLowerCase());
    }

    public static synchronized Card getOrCreate(String w) {
        String k = w.toLowerCase();
        Card c = MAP.get(k);
        if (c == null) {
            c = new Card(k);
            MAP.put(k, c);
            touch();
        }
        return c;
    }

    public static synchronized void put(Card c) {
        MAP.put(c.w, c);
        touch();
    }

    public static synchronized void remove(String w) {
        MAP.remove(w.toLowerCase());
        touch();
    }

    /** 清空全部学习数据。 */
    public static synchronized void clear() {
        MAP.clear();
        dirty = true;
        flush();
    }

    public static synchronized int size() {
        return MAP.size();
    }

    public static synchronized List<Card> all() {
        return new ArrayList<>(MAP.values());
    }

    /** 到期卡片（忽略已标记忽略的）。 */
    public static synchronized List<Card> due(long now, int limit) {
        List<Card> out = new ArrayList<>();
        for (Card c : MAP.values()) {
            if (c.status == Card.IGNORED) continue;
            if (c.next <= now) out.add(c);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.next, b.next));
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public static synchronized List<Card> byStatus(int status, int limit) {
        List<Card> out = new ArrayList<>();
        for (Card c : MAP.values()) {
            if (c.status == status) out.add(c);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.last, a.last));
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public static synchronized int countByStatus(int status) {
        int n = 0;
        for (Card c : MAP.values()) if (c.status == status) n++;
        return n;
    }

    public static synchronized int reviewedToday(long dayStart) {
        int n = 0;
        for (Card c : MAP.values()) if (c.last >= dayStart) n++;
        return n;
    }
}
