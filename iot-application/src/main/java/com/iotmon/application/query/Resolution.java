package com.iotmon.application.query;

import java.time.Duration;

/**
 * 查詢解析度：決定一次歷史查詢該讀三層中的哪一層。
 *
 * <p>層級由後端依時間跨度決定，**不開放呼叫端指定**。
 * 開放的話遲早有人對兩年的範圍要求原始精度，把資料庫拖垮——
 * 而那個人通常是三個月後的自己。
 *
 * <p>回應一定會帶上實際使用的層級，呼叫端才知道拿到的是原始值還是聚合值。
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
     * 依請求跨度挑選層級。
     *
     * <p>門檻刻意設得比保留期限保守：原始層保留 7 天，但只有 6 小時內的查詢走它。
     * 理由是查詢成本——七天的原始資料是 302 億筆的七分之一，
     * 就算查得到也不可能在一秒內回應。保留期限決定「資料還在不在」，
     * 這裡的門檻決定「讀它划不划算」，兩者不是同一件事。
     */
    public static Resolution forSpan(Duration span) {
        if (span == null || span.isNegative()) {
            throw new IllegalArgumentException("查詢跨度必須為正");
        }
        for (Resolution resolution : values()) {
            if (resolution.maxSpan == null || span.compareTo(resolution.maxSpan) <= 0) {
                return resolution;
            }
        }
        return ONE_HOUR;
    }

    /**
     * 這個跨度會產生幾個時間桶。
     *
     * <p>用來在查詢真的送出去之前擋下「兩年 × 每分鐘」這種註定超時的請求。
     * 事後加快取救不了這種查詢，因為第一次就會把資料庫打住。
     */
    public long estimatedBuckets(Duration span) {
        if (bucketSize == null) {
            // 原始層沒有固定桶大小，回報上限讓呼叫端一律當成「很多」處理
            return Long.MAX_VALUE;
        }
        return span.toSeconds() / bucketSize.toSeconds();
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
