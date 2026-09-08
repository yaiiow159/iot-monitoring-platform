package com.iotmon.application.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.iotmon.application.alarm.AlarmEvaluator.Decision;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 告警狀態機。
 *
 * <p>這些測試的重點都是「不該發的時候不要發」——
 * 告警系統的失敗模式很少是漏報，多半是浮報到沒有人再看它。
 */
class AlarmEvaluatorTest {

    private static final DeviceId DEVICE = DeviceId.of("DEV-001");
    private static final ModelCode MODEL = ModelCode.of("TH-100");
    private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

    private static AlarmRule ruleSustainedFor(Duration duration) {
        return AlarmRule.forModel(1L, "溫度過高", MODEL, MetricKey.of("temperature"),
                Comparison.GT, 30, null, AlarmSeverity.WARNING, duration, true);
    }

    @Test
    @DisplayName("持續違反未達門檻時間內不告警")
    void doesNotFireBeforeSustainedDuration() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ofSeconds(60));

        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 35, T0));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 36, T0.plusSeconds(30)));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 37, T0.plusSeconds(59)));
    }

    @Test
    @DisplayName("達到持續時間才告警，而且只告警一次")
    void firesOnceAfterSustainedDuration() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ofSeconds(60));

        evaluator.evaluate(DEVICE, rule, 35, T0);
        assertEquals(Decision.FIRE, evaluator.evaluate(DEVICE, rule, 36, T0.plusSeconds(60)));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 37, T0.plusSeconds(61)));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 38, T0.plusSeconds(120)));
    }

    @Test
    @DisplayName("門檻附近抖動不會刷出大量告警——這是告警疲勞最常見的來源")
    void flappingDoesNotFloodAlarms() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ofSeconds(60));

        int fired = 0;
        for (int i = 0; i < 100; i++) {
            // 在 29.5 與 30.5 之間來回，每次都跨過 30 這個門檻
            double value = (i % 2 == 0) ? 30.5 : 29.5;
            if (evaluator.evaluate(DEVICE, rule, value, T0.plusSeconds(i)) == Decision.FIRE) {
                fired++;
            }
        }
        assertEquals(0, fired, "抖動不該產生任何告警，因為沒有一次違反能持續滿 60 秒");
    }

    @Test
    @DisplayName("不要求持續時間時，第一次違反就告警但不重複")
    void zeroDurationFiresImmediatelyOnce() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ZERO);

        assertEquals(Decision.FIRE, evaluator.evaluate(DEVICE, rule, 35, T0));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 36, T0.plusSeconds(1)));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 40, T0.plusSeconds(2)));
    }

    @Test
    @DisplayName("回到正常值時解除，且只解除一次")
    void resolvesWhenBackToNormal() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ZERO);

        evaluator.evaluate(DEVICE, rule, 35, T0);
        assertEquals(Decision.RESOLVE, evaluator.evaluate(DEVICE, rule, 25, T0.plusSeconds(10)));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 24, T0.plusSeconds(20)));
    }

    @Test
    @DisplayName("裝置離線後清掉累積狀態，重新上線不會因為離線期間而立刻告警")
    void forgettingDeviceClearsAccumulatedBreach() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule rule = ruleSustainedFor(Duration.ofSeconds(60));

        evaluator.evaluate(DEVICE, rule, 35, T0);
        evaluator.forget(DEVICE);
        assertEquals(0, evaluator.trackedBreaches());

        // 離線半小時後重新上線，第一筆讀數仍然違反門檻——但持續時間要重新算
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, rule, 35, T0.plusSeconds(1800)));
    }

    @Test
    @DisplayName("同一台裝置的不同規則各自累積，互不影響")
    void rulesAccumulateIndependently() {
        AlarmEvaluator evaluator = new AlarmEvaluator();
        AlarmRule warning = AlarmRule.forModel(1L, "偏高", MODEL, MetricKey.of("temperature"),
                Comparison.GT, 30, null, AlarmSeverity.WARNING, Duration.ZERO, true);
        AlarmRule critical = AlarmRule.forModel(2L, "過高", MODEL, MetricKey.of("temperature"),
                Comparison.GT, 50, null, AlarmSeverity.CRITICAL, Duration.ZERO, true);

        assertEquals(Decision.FIRE, evaluator.evaluate(DEVICE, warning, 35, T0));
        assertEquals(Decision.NOTHING, evaluator.evaluate(DEVICE, critical, 35, T0));
        assertEquals(Decision.FIRE, evaluator.evaluate(DEVICE, critical, 55, T0.plusSeconds(1)));
    }
}
