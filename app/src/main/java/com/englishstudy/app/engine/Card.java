package com.englishstudy.app.engine;

/** 一张记忆卡片：一个词的全部调度状态。 */
public class Card {

    public static final int NEW = 0;
    public static final int LEARNING = 1;
    public static final int MASTERED = 2;
    public static final int LEECH = 3;
    public static final int IGNORED = 4;

    public String w;
    public double s = 0;        // 稳定度（天）
    public double d = 5;        // 难度 1-10
    public int reps;
    public int lapses;
    public int right;
    public int wrong;
    public long last;
    public long next;
    public int status = NEW;
    public int lastGrade;

    public Card() {
    }

    public Card(String word) {
        w = word;
    }

    public boolean due(long now) {
        return next <= now;
    }

    public long daysUntil(long now) {
        return (long) Math.floor((next - now) / 86400000.0);
    }

    public String encode() {
        return w + '\t' + s + '\t' + d + '\t' + reps + '\t' + lapses + '\t'
                + right + '\t' + wrong + '\t' + last + '\t' + next + '\t'
                + status + '\t' + lastGrade;
    }

    public static Card decode(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 11) return null;
        Card c = new Card(p[0]);
        try {
            c.s = Double.parseDouble(p[1]);
            c.d = Double.parseDouble(p[2]);
            c.reps = Integer.parseInt(p[3]);
            c.lapses = Integer.parseInt(p[4]);
            c.right = Integer.parseInt(p[5]);
            c.wrong = Integer.parseInt(p[6]);
            c.last = Long.parseLong(p[7]);
            c.next = Long.parseLong(p[8]);
            c.status = Integer.parseInt(p[9]);
            c.lastGrade = Integer.parseInt(p[10]);
        } catch (NumberFormatException e) {
            return null;
        }
        return c;
    }
}
