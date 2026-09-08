package com.iotmon.domain.model;

/**
 * 一個指標在某個機型上的定義：單位與物理量程。
 *
 * <p>量程的用途不是「限制使用者輸入」，而是**分辨故障與異常**。
 * 溫度感測器回報 200°C 不代表機房失火，多半代表感測器壞了或線路斷路；
 * 這兩件事要觸發的告警完全不同。沒有量程就只能把兩者混為一談。
 *
 * @param key       指標代號
 * @param unit      單位，僅供顯示，不參與運算
 * @param minValue  量程下限（含）
 * @param maxValue  量程上限（含）
 */
public record MetricDefinition(MetricKey key, String unit, double minValue, double maxValue) {

    public MetricDefinition {
        if (key == null) {
            throw new IllegalArgumentException("指標代號不可為 null");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("指標單位不可為空：" + key);
        }
        if (Double.isNaN(minValue) || Double.isNaN(maxValue)) {
            throw new IllegalArgumentException("量程不可為 NaN：" + key);
        }
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
