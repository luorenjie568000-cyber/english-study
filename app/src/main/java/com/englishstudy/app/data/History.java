package com.englishstudy.app.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻译历史：全部存本地 SharedPreferences。
 *
 * 存储格式（每行一条，字段用 \u001f 分隔）：
 *   时间戳 \u001f 原文 \u001f 译文
 *
 * 原文/译文里的换行已压成空格，避免破坏行结构。
 */
public final class History {

    private static final String KEY = "trans.history";
    private static final int MAX = 200;

    public static final class Item {
        public long time;
        public String source = "";
        public String target = "";
    }

    private History() {
    }

    /** 读全部历史，最新在前。 */
    public static List<Item> all() {
        List<Item> out = new ArrayList<>();
        String blob = Store.get(KEY, "");
        if (blob.isEmpty()) return out;
        for (String line : blob.split("\n")) {
            String[] p = line.split("\u001f", -1);
            if (p.length < 3) continue;
            Item it = new Item();
            try {
                it.time = Long.parseLong(p[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            it.source = p[1];
            it.target = p[2];
            out.add(it);
        }
        return out;
    }

    /** 加一条；同一原文已存在则提到最前。 */
    public static void add(String source, String target) {
        if (source == null || source.trim().isEmpty()) return;
        String s = oneLine(source);
        String t = oneLine(target);

        List<Item> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).source.equals(s)) {
                list.remove(i);
                break;
            }
        }
        Item it = new Item();
        it.time = System.currentTimeMillis();
        it.source = s;
        it.target = t;
        list.add(0, it);

        if (list.size() > MAX) list = new ArrayList<>(list.subList(0, MAX));
        save(list);
    }

    /** 删一条（按原文匹配）。 */
    public static void remove(String source) {
        String s = oneLine(source);
        List<Item> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).source.equals(s)) {
                list.remove(i);
                break;
            }
        }
        save(list);
    }

    public static void clear() {
        Store.remove(KEY);
    }

    public static int size() {
        return all().size();
    }

    private static void save(List<Item> list) {
        StringBuilder sb = new StringBuilder(list.size() * 96);
        for (Item it : list) {
            sb.append(it.time).append('\u001f')
              .append(it.source).append('\u001f')
              .append(it.target).append('\n');
        }
        Store.put(KEY, sb.toString());
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }
}
