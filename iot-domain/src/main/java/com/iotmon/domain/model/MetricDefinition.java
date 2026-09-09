package com.iotmon.domain.model;

import com.iotmon.domain.shared.Guard;

/**
 * 一個指標在某個機型上的定義。量程不是限制輸入，而是分辨「感測器壞了」與「機房失火」——兩者要觸發的告警不同。
 * @param unit 僅供顯示，無因次量為空字串
 * @param minValue 量程下限（含）
 * @param maxValue 量程上限（含）
 */
public record MetricDefinition(MetricKey key, String unit, double minValue, double maxValue) {

    public MetricDefinition {
        Guard.notNull(key, "指標代號");
        Guard.notNull(unit, "指標 " + key + " 的單位");
        Guard.finite(minValue, "指標 " + key + " 的量程下限");
        Guard.finite(maxValue, "指標 " + key + " 的量程上限");
        if (minValue >= maxValue) {
            throw new IllegalArgumentException(
                    "量程下限必須小於上限：" + key + " [" + minValue + ", " + maxValue + "]");
        }
    }

    public static MetricDefinition of(String key, String unit, double min, double max) {
        return new MetricDefinition(MetricKey.of(key), unit, min, max);
    }

    /** 讀數是否落在物理量程內。超出量程代表感測異常，不是業務上的高低值。 */
    public boolean isWithinRange(double value) {
        return !Double.isNaN(value) && value >= minValue && value <= maxValue;
    }

    /**
     * 告警門檻是否有意義。
     *
     * <p>門檻設在量程之外的規則永遠不會觸發（或永遠觸發），那是設定錯誤而不是保守設定。
     * 這種規則會安靜地不作用，直到事故發生才有人發現「原來那條告警從來沒響過」。
     */
    public boolean isMeaningfulThreshold(double threshold) {
        return isWithinRange(threshold);
    }
}
