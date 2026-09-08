package com.iotmon.domain.alarm;

/**
 * 告警規則的比較方式。
 *
 * <p>判定邏輯放在列舉本身而不是外面的 if-else 鏈：新增一種比較方式時，
 * 編譯器會強迫你在這裡補上實作，而不是讓某個 switch 安靜地走進 default 分支。
 * 「安靜地不觸發」是告警系統最糟的失敗模式——沒有人會發現。
 */
public enum Comparison {

    /** 大於門檻 */
    GT {
        @Override
        public boolean breached(double value, double threshold, Double secondary) {
            return value > threshold;
        }
    },

    /** 大於等於門檻 */
    GTE {
        @Override
        public boolean breached(double value, double threshold, Double secondary) {
            return value >= threshold;
        }
    },

    /** 小於門檻 */
    LT {
        @Override
        public boolean breached(double value, double threshold, Double secondary) {
            return value < threshold;
        }
    },

    /** 小於等於門檻 */
    LTE {
        @Override
        public boolean breached(double value, double threshold, Double secondary) {
            return value <= threshold;
        }
    },

    /**
     * 落在 [threshold, secondary] 區間之外。
     *
     * <p>電壓這類指標「過高」與「過低」都是異常，用兩條 GT／LT 規則表達的話，
     * 一次異常會產生兩則告警，而且解除時機也不一致。
     */
    OUT_OF_RANGE {
        @Override
        public boolean breached(double value, double threshold, Double secondary) {
            if (secondary == null) {
                throw new IllegalStateException("OUT_OF_RANGE 需要上下界兩個值");
            }
            double lower = Math.min(threshold, secondary);
            double upper = Math.max(threshold, secondary);
            return value < lower || value > upper;
        }
    };

    /**
     * 這筆讀數是否違反門檻。
     *
     * @param secondary 僅 {@link #OUT_OF_RANGE} 使用，其餘傳 null
     */
    public abstract boolean breached(double value, double threshold, Double secondary);

    public boolean requiresSecondaryValue() {
        return this == OUT_OF_RANGE;
    }
}
