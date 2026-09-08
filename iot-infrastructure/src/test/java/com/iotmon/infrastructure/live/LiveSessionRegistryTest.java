package com.iotmon.infrastructure.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推播的兩條規則：沒訂閱不推、慢連線斷掉不拖累別人。
 * 用假訂閱者而不是 mock WebSocketSession——registry 本來就不該認得 WebSocket。
 */
class LiveSessionRegistryTest {

    /** 記錄收到什麼；可以設成「送出時失敗」來模擬卡住的連線 */
    private static final class FakeSubscriber implements LiveSubscriber {
        final String id;
        final List<String> received = new ArrayList<>();
        boolean failOnSend;
        boolean closed;

        FakeSubscriber(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public boolean isOpen() { return !closed; }

        @Override
        public void send(String json) throws IOException {
            if (failOnSend) throw new IOException("connection stalled");
            received.add(json);
        }

        @Override public void close() { closed = true; }
    }

    private static LiveSessionRegistry registry() {
        return new LiveSessionRegistry(new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static LiveMessage.Telemetry telemetry(String device) {
        return new LiveMessage.Telemetry(device, Map.of("temperature", 25.0), 1L);
    }

    @Test
    @DisplayName("只推給訂閱了該裝置的連線——一萬台全推給每個瀏覽器會直接打爆前端")
    void deliversOnlyToSubscribers() {
        LiveSessionRegistry registry = registry();
        FakeSubscriber wants = new FakeSubscriber("a");
        FakeSubscriber other = new FakeSubscriber("b");
        FakeSubscriber none = new FakeSubscriber("c");
        registry.register(wants);
        registry.register(other);
        registry.register(none);
        registry.subscribe("a", Set.of("DEV-1"));
        registry.subscribe("b", Set.of("DEV-2"));

        registry.publish(telemetry("DEV-1"));

        assertEquals(1, wants.received.size());
        assertTrue(other.received.isEmpty());
        assertTrue(none.received.isEmpty(), "沒送訂閱指令的連線什麼都不該收到");
    }

    @Test
    @DisplayName("訂閱是整組取代：前端每次送的是畫面上的完整集合")
    void subscribeReplacesInsteadOfAccumulating() {
        LiveSessionRegistry registry = registry();
        FakeSubscriber s = new FakeSubscriber("a");
        registry.register(s);
        registry.subscribe("a", Set.of("DEV-1", "DEV-2"));
        registry.subscribe("a", Set.of("DEV-3"));

        registry.publish(telemetry("DEV-1"));
        registry.publish(telemetry("DEV-3"));

        assertEquals(1, s.received.size());
        assertEquals(1, registry.subscriptionCount("a"));
    }

    @Test
    @DisplayName("送不出去的連線被斷掉並移除，其他連線照常收到——一條卡住不該讓消費端整個停下")
    void slowSubscriberIsDroppedWithoutAffectingOthers() {
        LiveSessionRegistry registry = registry();
        FakeSubscriber stalled = new FakeSubscriber("slow");
        FakeSubscriber healthy = new FakeSubscriber("ok");
        stalled.failOnSend = true;
        registry.register(stalled);
        registry.register(healthy);
        registry.subscribe("slow", Set.of("DEV-1"));
        registry.subscribe("ok", Set.of("DEV-1"));

        registry.publish(telemetry("DEV-1"));

        assertTrue(stalled.closed, "卡住的連線應被關閉");
        assertEquals(1, registry.sessionCount(), "並從 registry 移除");
        assertEquals(1, healthy.received.size(), "健康的連線不受影響");
    }

    @Test
    @DisplayName("推播內容帶 type 欄位，前端據此分流")
    void payloadCarriesType() {
        LiveSessionRegistry registry = registry();
        FakeSubscriber s = new FakeSubscriber("a");
        registry.register(s);
        registry.subscribe("a", Set.of("DEV-1"));

        registry.publish(new LiveMessage.Alarm(7L, "DEV-1", "CRITICAL", "FIRING", List.of(1L, 4L, 9L), 1L));

        String json = s.received.get(0);
        assertTrue(json.contains("\"type\":\"alarm\""), json);
        assertTrue(json.contains("\"ancestorIds\":[1,4,9]"), json);
    }
}
