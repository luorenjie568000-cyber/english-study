package com.englishstudy.app.engine;

/** 复习状态机：把一次评分翻译成新的卡片状态。 */
public final class Review {

    private Review() {
    }

    /**
     * 就地更新卡片。
     *
     * @param grade 1=忘了 2=勉强 3=记得 4=太简单
     * @return 实际采用的分档
     */
    public static int apply(Card c, int grade, long now) {
        int g = Math.max(1, Math.min(4, grade));

        if (c.reps == 0) {
            c.s = Fsrs.initStability(g);
            c.d = Fsrs.initDifficulty(g);
        } else {
            double elapsed = c.last > 0 ? (now - c.last) / 86400000.0 : 0;
            if (elapsed < 0) elapsed = 0;
            double r = Fsrs.retrievability(elapsed, c.s);
            c.d = Fsrs.nextDifficulty(c.d, g);
            if (g == 1) {
                c.s = Fsrs.nextStability(c.d, c.s, r, 1);
                c.lapses++;
            } else if (elapsed < 0.02) {
                c.s = Fsrs.nextShortTermStability(c.s, g);
            } else {
                c.s = Fsrs.nextStability(c.d, c.s, r, g);
            }
        }

        c.reps++;
        c.last = now;
        c.lastGrade = g;
        if (g == 1) {
            c.wrong++;
        } else {
            c.right++;
        }

        // 下次到期
        double days;
        if (g == 1) {
            days = 10.0 / 1440.0;                       // 10 分钟后再来
        } else if (c.reps == 1) {
            days = g == 4 ? 4 : (g == 2 ? 1 : 2);       // 首次毕业间隔
        } else {
            days = Fsrs.intervalOf(c.s, Fsrs.RETENTION);
        }
        c.next = now + (long) (days * 86400000L);

        // 状态
        if (c.lapses >= 4) {
            c.status = Card.LEECH;
        } else if (c.s >= 21) {
            c.status = Card.MASTERED;
        } else {
            c.status = Card.LEARNING;
        }
        return g;
    }

    /** 四档预览：返回各档下次间隔（天），供答题界面提示。 */
    public static int[] preview(Card c, long now) {
        int[] out = new int[4];
        for (int g = 1; g <= 4; g++) {
            Card t = copy(c);
            apply(t, g, now);
            out[g - 1] = Math.max(0, (int) Math.round((t.next - now) / 86400000.0));
        }
        return out;
    }

    private static Card copy(Card c) {
        Card t = new Card(c.w);
        t.s = c.s;
        t.d = c.d;
        t.reps = c.reps;
        t.lapses = c.lapses;
        t.right = c.right;
        t.wrong = c.wrong;
        t.last = c.last;
        t.next = c.next;
        t.status = c.status;
        t.lastGrade = c.lastGrade;
        return t;
    }
}
