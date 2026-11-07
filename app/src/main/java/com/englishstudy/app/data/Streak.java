package com.englishstudy.app.data;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 打卡日历 + 连续天数（Streak）。
 *
 * 完全本地：只在 SharedPreferences 里记「哪天复习过」和「每天复习了多少」，
 * 不做社交、不做后端，符合离线定位。
 */
public final class Streak {

    /** 一天的学习记录。 */
    public static final class Day {
        public final String key;    // yyyy-MM-dd
        public final int count;     // 当天复习了多少词

        Day(String key, int count) {
            this.key = key;
            this.count = count;
        }
    }

    private Streak() {
    }

    // ---------------------------------------------------------------- 日期

    public static String key(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    public static String today() {
        return key(System.currentTimeMillis());
    }

    private static long dayStart(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ---------------------------------------------------------------- 记录

    /** 复习完一张卡就调用一次；同一天多次调用只累加计数。 */
    public static void mark(int count) {
        String k = today();
        String raw = Store.get("streak.days", "");
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (String seg : raw.split("\n")) {
            if (seg.isEmpty()) continue;
            int tab = seg.indexOf('\t');
            if (tab <= 0) continue;
            String day = seg.substring(0, tab);
            int n = parseInt(seg.substring(tab + 1));
            if (day.equals(k)) {
                n += count;
                found = true;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(day).append('\t').append(n);
        }
        if (!found) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k).append('\t').append(count);
        }
        Store.put("streak.days", sb.toString());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 全部有记录的日期，按时间正序。 */
    public static List<Day> days() {
        List<Day> out = new ArrayList<>();
        String raw = Store.get("streak.days", "");
        for (String seg : raw.split("\n")) {
            if (seg.isEmpty()) continue;
            int tab = seg.indexOf('\t');
            if (tab <= 0) continue;
            out.add(new Day(seg.substring(0, tab), parseInt(seg.substring(tab + 1))));
        }
        out.sort((a, b) -> a.key.compareTo(b.key));
        return out;
    }

    /** 连续天数：今天或昨天有记录才继续算，否则归零。 */
    public static int current() {
        List<Day> all = days();
        if (all.isEmpty()) return 0;
        long now = dayStart(System.currentTimeMillis());
        String t = key(now);
        String y = key(now - 86400000L);

        String lastKey = all.get(all.size() - 1).key;
        if (!lastKey.equals(t) && !lastKey.equals(y)) return 0;

        int run = 0;
        long cursor = lastKey.equals(t) ? now : now - 86400000L;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).key.equals(key(cursor))) {
                run++;
                cursor -= 86400000L;
            } else if (all.get(i).key.compareTo(key(cursor)) < 0) {
                break;
            }
        }
        return run;
    }

    /** 历史最长连续。 */
    public static int longest() {
        List<Day> all = days();
        if (all.isEmpty()) return 0;
        int best = 1, cur = 1;
        for (int i = 1; i < all.size(); i++) {
            long prev = parseKey(all.get(i - 1).key);
            long now = parseKey(all.get(i).key);
            if (now - prev == 86400000L) {
                cur++;
                if (cur > best) best = cur;
            } else if (now != prev) {
                cur = 1;
            }
        }
        return best;
    }

    private static long parseKey(String k) {
        try {
            String[] p = k.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                    0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 最近 n 天（含今天）的记录，缺失的补 0，用于画热力图。 */
    public static List<Day> recent(int n) {
        List<Day> all = days();
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        for (Day d : all) map.put(d.key, d.count);

        List<Day> out = new ArrayList<>(n);
        long start = dayStart(System.currentTimeMillis());
        for (int i = n - 1; i >= 0; i--) {
            String k = key(start - i * 86400000L);
            Integer c = map.get(k);
            out.add(new Day(k, c == null ? 0 : c));
        }
        return out;
    }

    public static int total() {
        int n = 0;
        for (Day d : days()) n += d.count;
        return n;
    }
}
