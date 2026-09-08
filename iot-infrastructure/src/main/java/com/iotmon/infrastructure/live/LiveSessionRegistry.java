package com.iotmon.infrastructure.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 記錄每條連線訂閱了哪些裝置，並負責推播。
 *
 * <p>兩個設計重點：
 *
 * <ul>
 *   <li><b>沒訂閱就什麼都不推。</b>一萬台裝置的更新全推給每個瀏覽器會直接打爆前端，
 *       這是契約明訂的。</li>
 *   <li><b>慢的連線直接斷掉。</b>推播在 Kafka 消費端的執行緒上進行，
 *       一條卡住的連線若讓 send 阻塞，整個消費群組都跟著停。
 *       送不出去就關掉它，讓前端自己重連——這比讓所有人一起變慢好。</li>
 * </ul>
 *
 * <p>只依賴 {@link LiveSubscriber}，不認得 WebSocket；傳輸層的細節在 iot-api。
 */
@Component
public class LiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionRegistry.class);

    private final Map<String, LiveSubscriber> subscribers = new ConcurrentHashMap<>();
    /** subscriber id → 已訂閱的 deviceId 集合 */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final Counter pushed;
    private final Counter dropped;

    public LiveSessionRegistry(ObjectMapper json, MeterRegistry registry) {
        this.json = json;
        this.pushed = Counter.builder("live.messages.pushed").description("成功推播的訊息數").register(registry);
        this.dropped = Counter.builder("live.messages.dropped")
                .description("因連線送不出去而丟棄並斷線的訊息數").register(registry);
        Gauge.builder("live.sessions", subscribers, Map::size).description("目前的推播連線數").register(registry);
    }

    public void register(LiveSubscriber subscriber) {
        subscribers.put(subscriber.id(), subscriber);
        subscriptions.put(subscriber.id(), ConcurrentHashMap.newKeySet());
    }

    public void unregister(String subscriberId) {
        subscribers.remove(subscriberId);
        subscriptions.remove(subscriberId);
    }

    /** 訂閱是「整組取代」而不是累加：前端每次送的是目前畫面上看得到的完整集合。 */
    public void subscribe(String subscriberId, Set<String> deviceIds) {
        Set<String> current = subscriptions.get(subscriberId);
        if (current == null) {
            return;
        }
        current.clear();
        current.addAll(deviceIds);
    }

    /** 推給所有訂閱了這台裝置的連線 */
    public void publish(LiveMessage message) {
        String payload;
        try {
            payload = json.writeValueAsString(message);
        } catch (IOException e) {
            log.warn("推播訊息序列化失敗：{}", e.getMessage());
            return;
        }

        for (Map.Entry<String, Set<String>> entry : subscriptions.entrySet()) {
            if (!entry.getValue().contains(message.deviceId())) {
                continue;
            }
            LiveSubscriber subscriber = subscribers.get(entry.getKey());
            if (subscriber == null || !subscriber.isOpen()) {
                continue;
            }
            try {
                subscriber.send(payload);
                pushed.increment();
            } catch (IOException | RuntimeException sendFailed) {
                dropped.increment();
                unregister(subscriber.id());
                subscriber.close();
            }
        }
    }

    public int sessionCount() {
        return subscribers.size();
    }

    public int subscriptionCount(String subscriberId) {
        Set<String> set = subscriptions.get(subscriberId);
        return set == null ? 0 : set.size();
    }
}
