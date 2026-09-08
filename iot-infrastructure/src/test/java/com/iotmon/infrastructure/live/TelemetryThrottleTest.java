package com.iotmon.infrastructure.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryThrottleTest {

    private static LiveMessage.Telemetry t(String device, double temp, long ts) {
        return new LiveMessage.Telemetry(device, Map.of("temperature", temp), ts);
    }

    @Test
    @DisplayName("同一週期內同一台裝置只留最後一筆——人眼看不出每秒五次的差別，瀏覽器卻會卡住")
    void keepsOnlyLatestPerDevice() {
        TelemetryThrottle throttle = new TelemetryThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.offer(t("DEV-1", 20 + i, 1000 + i));
        }
        List<LiveMessage.Telemetry> batch = throttle.drain();
        assertEquals(1, batch.size());
        assertEquals(24.0, batch.get(0).metrics().get("temperature"));
    }

    @Test
    @DisplayName("不同裝置各自保留，互不覆蓋")
    void devicesAreIndependent() {
        TelemetryThrottle throttle = new TelemetryThrottle();
        throttle.offer(t("DEV-1", 20, 1));
        throttle.offer(t("DEV-2", 30, 1));
        assertEquals(2, throttle.drain().size());
    }

    @Test
    @DisplayName("亂序到達時以裝置端時間戳為準，較舊的不會蓋掉較新的")
    void olderTimestampDoesNotOverwriteNewer() {
        TelemetryThrottle throttle = new TelemetryThrottle();
        throttle.offer(t("DEV-1", 25, 2000));
        throttle.offer(t("DEV-1", 99, 1000));
        assertEquals(25.0, throttle.drain().get(0).metrics().get("temperature"));
    }

    @Test
    @DisplayName("drain 之後清空，下一週期從零開始")
    void drainClearsPending() {
        TelemetryThrottle throttle = new TelemetryThrottle();
        throttle.offer(t("DEV-1", 20, 1));
        throttle.drain();
        assertEquals(0, throttle.pending());
        assertTrue(throttle.drain().isEmpty());
    }
}
