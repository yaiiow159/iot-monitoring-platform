package com.iotmon.simulator.catalog;

import com.iotmon.domain.model.MetricDefinition;

/**
 * 一個指標「怎麼產生數值」的模擬設定。
 *
 * <p>domain 的 {@link MetricDefinition} 只說得出物理量程，但量程不等於運轉範圍——
 * 溫度感測器量得到 80°C 不代表機房會跑到 80°C。分開兩者，超出量程的故障才跟正常值有明顯區隔。
 *
 * @param definition    domain 的指標定義，量程由它決定
 * @param kind          數值型態
 * @param bandLow       正常運轉帶下限
 * @param bandHigh      正常運轉帶上限
 * @param volatility    每次取樣的漂移幅度，佔運轉帶寬度的比例
 * @param flipProbability BINARY 專用：每次取樣翻轉狀態的機率
 * @param decimals      輸出小數位數，同時決定 payload 大小
 */
public record MetricProfile(MetricDefinition definition, Kind kind, double bandLow, double bandHigh,
                            double volatility, double flipProbability, int decimals) {

    /** 均值回歸的強度。太小會讓隨機遊走貼著邊界跑，太大會把曲線壓成直線。 */
    public static final double MEAN_REVERSION = 0.03;

    public enum Kind {
        /** 連續量：隨機遊走 ＋ 均值回歸。 */
        RANDOM_WALK,
        /** 開關量：多數時間不動，偶爾翻轉，例如門禁。 */
        BINARY
    }

    public MetricProfile {
        if (definition == null || kind == null) {
            throw new IllegalArgumentException("指標定義與型態不可為 null");
        }
        // 運轉帶跑到量程外的話，這個指標的「正常值」本身就會被平台判成感測異常
        if (bandLow >= bandHigh
                || !definition.isWithinRange(bandLow) || !definition.isWithinRange(bandHigh)) {
            throw new IllegalArgumentException(
                    "運轉帶必須落在量程內且下限小於上限：" + definition.key()
                            + " band[" + bandLow + ", " + bandHigh + "]");
        }
    }

    public static MetricProfile walk(MetricDefinition definition, double bandLow, double bandHigh,
                                     double volatility, int decimals) {
        return new MetricProfile(definition, Kind.RANDOM_WALK, bandLow, bandHigh, volatility, 0, decimals);
    }

    public static MetricProfile binary(MetricDefinition definition, double flipProbability) {
        return new MetricProfile(definition, Kind.BINARY,
                definition.minValue(), definition.maxValue(), 0, flipProbability, 0);
    }

    public String keyName() {
        return definition.key().value();
    }

    public double bandSpan() {
        return bandHigh - bandLow;
    }

    /** 物理量程寬度，故障注入用它決定要把讀數推出去多遠。 */
    public double rangeSpan() {
        return definition.maxValue() - definition.minValue();
    }
}
