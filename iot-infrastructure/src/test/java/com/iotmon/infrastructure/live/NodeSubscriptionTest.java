package com.iotmon.infrastructure.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 按節點訂閱：只推狀態與告警，不推遙測。 */
class NodeSubscriptionTest {

    private static final class Recording implements LiveSubscriber {
        final List<String> received = new ArrayList<>();

        @Override
        public String id() {
            return "tree";
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void send(String payload) {
            received.add(payload);
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("節點展開出來的裝置：告警與狀態會推，遙測不推")
    void nodeSubscriptionReceivesAlarmsAndStatusOnly() {
        LiveSessionRegistry registry = new LiveSessionRegistry(new ObjectMapper(), new SimpleMeterRegistry());
        Recording tree = new Recording();
        registry.register(tree);
        registry.subscribe("tree", Set.of(), Set.of("DEV-1"));

        registry.publish(new LiveMessage.Telemetry("DEV-1", Map.of("temperature", 25.0), 1L));
        registry.publish(new LiveMessage.Status("DEV-1", "OFFLINE", 2L));
        registry.publish(new LiveMessage.Alarm(9L, "DEV-1", "WARNING", "FIRING", List.of(1L, 5L), 3L));
        registry.publish(new LiveMessage.Alarm(10L, "DEV-2", "WARNING", "FIRING", List.of(), 4L));

        assertThat(tree.received).hasSize(2);
        assertThat(tree.received.get(0)).contains("\"status\"").contains("DEV-1");
        assertThat(tree.received.get(1)).contains("\"alarm\"").contains("DEV-1");
        assertThat(registry.nodeSubscriptionCount("tree")).isEqualTo(1);
    }

    @Test
    @DisplayName("直接訂閱的裝置三種都推；重新訂閱會整組取代兩種集合")
    void directSubscriptionReceivesEverythingAndResubscribeReplaces() {
        LiveSessionRegistry registry = new LiveSessionRegistry(new ObjectMapper(), new SimpleMeterRegistry());
        Recording tree = new Recording();
        registry.register(tree);
        registry.subscribe("tree", Set.of("DEV-1"), Set.of("DEV-2"));
        registry.publish(new LiveMessage.Telemetry("DEV-1", Map.of("t", 1.0), 1L));
        registry.publish(new LiveMessage.Telemetry("DEV-2", Map.of("t", 1.0), 1L));
        assertThat(tree.received).hasSize(1);

        registry.subscribe("tree", Set.of(), Set.of());
        registry.publish(new LiveMessage.Alarm(1L, "DEV-2", "INFO", "FIRING", List.of(), 5L));
        assertThat(tree.received).hasSize(1);
    }
}
