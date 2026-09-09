package com.iotmon.application.query;

import java.time.Duration;

/**
 * 查詢解析度：三層中該讀哪一層由後端依跨度決定，不開放呼叫端指定——
 * 開放的話遲早有人對兩年要求原始精度。回應一定帶上實際使用的層級。
 */
public enum Resolution {

    /** 原始表。保留 7 天，只有近期查詢讀得到。 */
    RAW("raw", "telemetry", null, Duration.ofHours(6)),

    /** 1 分鐘聚合。保留 30 天。 */
    ONE_MINUTE("1m", "telemetry_1m", Duration.ofMinutes(1), Duration.ofDays(30)),

    /** 1 小時聚合。保留 5 年，兩年跨度的查詢讀這一層。 */
    ONE_HOUR("1h", "telemetry_1h", Duration.ofHours(1), null);

    private final String apiValue;
    private final String tableName;
    private final Duration bucketSize;
    private final Duration maxSpan;

    Resolution(String apiValue, String tableName, Duration bucketSize, Duration maxSpan) {
        this.apiValue = apiValue;
        this.tableName = tableName;
        this.bucketSize = bucketSize;
        this.maxSpan = maxSpan;
    }

    /**
     * 平台設計的取樣頻率：每台裝置每個指標每秒一點。
     * 原始層沒有固定的桶大小，用這個值估算點數。
     */
    private static final Duration RAW_SAMPLING_INTERVAL = Duration.ofSeconds(1);

    /**
     * 依跨度與點數上限挑層級：跨度要在保留範圍內，且點數不超過上限（六小時的原始資料就是 21,600 點）。
     * 點數超標時往粗的層級退而不是拒絕，回應的 resolution 會誠實說拿到的是哪一層。
     * @throws IllegalArgumentException 連最粗的層級都超過上限時
     */
    public static Resolution forSpan(Duration span, int maxPoints) {
        if (span == null || span.isNegative() || span.isZero()) {
            throw new IllegalArgumentException("查詢跨度必須為正");
        }
        for (Resolution resolution : values()) {
            boolean withinSpan = resolution.maxSpan == null || span.compareTo(resolution.maxSpan) <= 0;
            boolean withinBudget = resolution.estimatedPoints(span) <= maxPoints;
            if (withinSpan && withinBudget) {
                return resolution;
            }
        }
        long coarsest = ONE_HOUR.estimatedPoints(span);
        throw new IllegalArgumentException(
                "此區間即使以小時聚合仍有約 " + coarsest + " 個資料點，超過上限 " + maxPoints
                        + "。請縮小時間範圍。");
    }

    /**
     * 這個跨度在本層會產生幾個資料點。
     *
     * <p>用來在查詢真的送出去之前就決定該讀哪一層。
     * 事後加快取救不了選錯層級的查詢，因為第一次就會把資料庫打住。
     */
    public long estimatedPoints(Duration span) {
        Duration interval = bucketSize != null ? bucketSize : RAW_SAMPLING_INTERVAL;
        return span.toSeconds() / interval.toSeconds();
    }

    public String apiValue() {
        return apiValue;
    }

    public String tableName() {
        return tableName;
    }

    public boolean isAggregated() {
        return this != RAW;
    }
}
