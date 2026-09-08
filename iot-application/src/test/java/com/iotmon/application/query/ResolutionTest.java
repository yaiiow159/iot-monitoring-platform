package com.iotmon.application.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查詢層級路由。
 *
 * <p>這是「兩年跨度一秒內」的實作依據，因此每個邊界都要有測試守住——
 * 路由選錯層級不會報錯，只會讓查詢慢十倍或讀不到資料。
 */
class ResolutionTest {

    @Test
    @DisplayName("六小時以內讀原始層")
    void shortSpanUsesRaw() {
        assertEquals(Resolution.RAW, Resolution.forSpan(Duration.ofHours(1)));
        assertEquals(Resolution.RAW, Resolution.forSpan(Duration.ofHours(6)));
    }

    @Test
    @DisplayName("超過六小時到三十天讀 1 分鐘層")
    void mediumSpanUsesMinuteAggregate() {
        assertEquals(Resolution.ONE_MINUTE, Resolution.forSpan(Duration.ofHours(7)));
        assertEquals(Resolution.ONE_MINUTE, Resolution.forSpan(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("超過三十天讀 1 小時層，兩年也一樣")
    void longSpanUsesHourAggregate() {
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(31)));
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(730)));
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(1825)));
    }

    @Test
    @DisplayName("兩年跨度只有 17,520 個桶——這就是它能進一秒的原因")
    void twoYearQueryIsSmall() {
        long buckets = Resolution.ONE_HOUR.estimatedBuckets(Duration.ofDays(730));
        assertEquals(17_520, buckets);
        assertTrue(buckets < 20_000, "兩年的小時桶數應遠小於任何會超時的量級");
    }

    @Test
    @DisplayName("原始層回報桶數上限，讓呼叫端一律當成「很多」處理")
    void rawReportsUnboundedBuckets() {
        assertEquals(Long.MAX_VALUE, Resolution.RAW.estimatedBuckets(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("跨度為負或 null 直接拒絕，不要讓它變成一個掃全表的查詢")
    void rejectsInvalidSpan() {
        assertThrows(IllegalArgumentException.class, () -> Resolution.forSpan(null));
        assertThrows(IllegalArgumentException.class, () -> Resolution.forSpan(Duration.ofDays(-1)));
    }

    @Test
    @DisplayName("聚合層要標示為聚合，前端才知道該畫 min/max 帶狀區間")
    void aggregatedFlagIsCorrect() {
        assertFalse(Resolution.RAW.isAggregated());
        assertTrue(Resolution.ONE_MINUTE.isAggregated());
        assertTrue(Resolution.ONE_HOUR.isAggregated());
    }
}
