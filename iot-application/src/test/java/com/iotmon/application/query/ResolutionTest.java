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

    private static final int MAX_POINTS = 5000;

    @Test
    @DisplayName("一小時查詢走原始層：3,600 點，畫得下")
    void shortSpanUsesRaw() {
        assertEquals(Resolution.RAW, Resolution.forSpan(Duration.ofHours(1), MAX_POINTS));
    }

    @Test
    @DisplayName("六小時的原始資料是 21,600 點，超過上限，自動退到 1 分鐘層")
    void rawFallsBackWhenTooManyPoints() {
        // 這是早期版本的缺陷：原始層一律回報「無限多點」，導致所有原始查詢都被拒絕，
        // 六小時以內的路徑等於完全不能用
        assertEquals(21_600, Resolution.RAW.estimatedPoints(Duration.ofHours(6)));
        assertEquals(Resolution.ONE_MINUTE, Resolution.forSpan(Duration.ofHours(6), MAX_POINTS));
    }

    @Test
    @DisplayName("點數超標時往粗的層級退，而不是回錯誤——使用者要的是資料不是錯誤訊息")
    void escalatesInsteadOfRejecting() {
        assertEquals(Resolution.ONE_MINUTE, Resolution.forSpan(Duration.ofDays(3), MAX_POINTS));
        // 30 天以 1 分鐘計是 43,200 點，超過上限，退到小時層
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(30), MAX_POINTS));
    }

    @Test
    @DisplayName("兩年跨度走小時層，17,520 點——這就是它能進一秒的原因")
    void twoYearQueryUsesHourly() {
        assertEquals(17_520, Resolution.ONE_HOUR.estimatedPoints(Duration.ofDays(730)));
        // 但 17,520 > 5000，所以兩年的單次查詢仍需縮小範圍或提高上限
        assertThrows(IllegalArgumentException.class,
                () -> Resolution.forSpan(Duration.ofDays(730), MAX_POINTS));
        // 放寬上限後就走小時層
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(730), 20_000));
    }

    @Test
    @DisplayName("一年跨度在預設上限內：8,760 點仍超標，2000 點上限下要更粗的層級")
    void oneYearNeedsHourly() {
        assertEquals(8_760, Resolution.ONE_HOUR.estimatedPoints(Duration.ofDays(365)));
        assertEquals(Resolution.ONE_HOUR, Resolution.forSpan(Duration.ofDays(365), 10_000));
    }

    @Test
    @DisplayName("連小時層都超標時才拒絕，並說明實際點數")
    void rejectsOnlyWhenEvenHourlyIsTooMuch() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Resolution.forSpan(Duration.ofDays(3650), MAX_POINTS));
        assertTrue(error.getMessage().contains("小時聚合"), "訊息應說明已經用最粗的層級");
    }

    @Test
    @DisplayName("跨度為零、負數或 null 直接拒絕，不要讓它變成掃全表的查詢")
    void rejectsInvalidSpan() {
        assertThrows(IllegalArgumentException.class, () -> Resolution.forSpan(null, MAX_POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> Resolution.forSpan(Duration.ofDays(-1), MAX_POINTS));
        assertThrows(IllegalArgumentException.class,
                () -> Resolution.forSpan(Duration.ZERO, MAX_POINTS));
    }

    @Test
    @DisplayName("聚合層要標示為聚合，前端才知道該畫 min/max 帶狀區間")
    void aggregatedFlagIsCorrect() {
        assertFalse(Resolution.RAW.isAggregated());
        assertTrue(Resolution.ONE_MINUTE.isAggregated());
        assertTrue(Resolution.ONE_HOUR.isAggregated());
    }
}
