package com.englishstudy.app.engine;

/**
 * FSRS-6 调度公式。
 *
 * 与 open-spaced-repetition 官方默认参数一致：
 *   R(t,S) = (1 + FACTOR * t / S) ^ (-DECAY)
 *   FACTOR = 0.9^(-1/DECAY) - 1，保证 R(S,S) = 0.9
 */
public final class Fsrs {

    /** 官方 FSRS-6 默认 21 个参数（DESIRED_RETENTION = 0.9）。 */
    private static final double[] W = {
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };

    private static final double DECAY = -0.5;
    private static final double FACTOR = Math.pow(0.9, 1.0 / DECAY) - 1.0;
    public static final double RETENTION = 0.9;

    private Fsrs() {
    }

    /** 首次评分后的稳定度。 */
    public static double initStability(int g) {
        return Math.max(0.1, W[g - 1]);
    }

    /** 首次评分后的难度（指数形式，前期增长更陡）。 */
    public static double initDifficulty(int g) {
        double d = W[4] - Math.exp(W[5] * (g - 1)) + 1.0;
        return clamp(d, 1, 10);
    }

    /** 当前记忆可提取概率。 */
    public static double retrievability(double elapsedDays, double s) {
        if (s <= 0) return 0;
        return Math.pow(1 + FACTOR * elapsedDays / s, DECAY);
    }

    /** 下次难度：线性阻尼 + 均值回归。 */
    public static double nextDifficulty(double d, int g) {
        double delta = -W[6] * (g - 3);
        double damped = d + delta * (10.0 - d) / 9.0;
        // 均值回归到 init_difficulty(Easy)
        double meanReversion = W[7] * initDifficulty(4) + (1 - W[7]) * damped;
        return clamp(meanReversion, 1, 10);
    }

    /** 复习后的新稳定度。 */
    public static double nextStability(double d, double s, double r, int g) {
        if (g == 1) {
            double sf = W[11] * Math.pow(d, -W[12])
                    * (Math.pow(s + 1, W[13]) - 1)
                    * Math.exp(W[14] * (1 - r));
            return Math.max(0.1, Math.min(sf, s));
        }
        double hardPenalty = (g == 2) ? W[15] : 1.0;
        double easyBonus = (g == 4) ? W[16] : 1.0;
        double inc = Math.exp(W[8])
                * (11 - d)
                * Math.pow(s, -W[9])
                * (Math.exp(W[10] * (1 - r)) - 1)
                * hardPenalty * easyBonus;
        return Math.max(0.1, s * (1 + inc));
    }

    /** 同日重复练习的独立公式。 */
    public static double nextShortTermStability(double s, int g) {
        double inc = Math.exp(W[17] * (g - 3 + W[18])) * Math.pow(s, -W[19]);
        double ns = s * Math.exp(inc);
        return Math.max(0.1, ns);
    }

    /** 目标保持率下的间隔天数。 */
    public static double intervalOf(double s, double retention) {
        double r = retention <= 0 || retention >= 1 ? RETENTION : retention;
        return Math.max(1, s / FACTOR * (Math.pow(r, 1.0 / DECAY) - 1));
    }

    private static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }
}
