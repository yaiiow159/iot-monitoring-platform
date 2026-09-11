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
 * 每條連線訂閱了哪些裝置，以及推播本身。沒訂閱就不推（一萬台全推會打爆前端）；
 * 送不出去的連線直接斷掉，否則一條卡住的連線會讓整個消費群組跟著停。
 * 只依賴 LiveSubscriber，不認得 WebSocket。
 */
@Component
public class LiveSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionRegistry.class);

    private final Map<String, LiveSubscriber> subscribers = new ConcurrentHashMap<>();
    /** subscriber id → 已訂閱的 deviceId 集合（收全部三種訊息） */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    /**
     * subscriber id → 由節點訂閱展開出來的 deviceId 集合。
     * 這一組只收狀態與告警、不收遙測：監控樹要的是「哪裡在響」，不是每台裝置每秒的讀數。
     * 一萬台裝置的樹若連遙測都推，瀏覽器每秒要吃一萬則訊息。
     */
    private final Map<String, Set<String>> nodeSubscriptions = new ConcurrentHashMap<>();
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
        subscriptions.put(subscriber.id(), Set.of());
        nodeSubscriptions.put(subscriber.id(), Set.of());
    }

    public void unregister(String subscriberId) {
        subscribers.remove(subscriberId);
        subscriptions.remove(subscriberId);
        nodeSubscriptions.remove(subscriberId);
    }

    /** 訂閱是「整組取代」而不是累加：前端每次送的是目前畫面上看得到的完整集合。 */
    public void subscribe(String subscriberId, Set<String> deviceIds) {
        subscribe(subscriberId, deviceIds, Set.of());
    }

    /**
     * @param deviceIds     直接訂閱的裝置：三種訊息都推
     * @param nodeDeviceIds 由節點展開的裝置：只推狀態與告警
     */
    public void subscribe(String subscriberId, Set<String> deviceIds, Set<String> nodeDeviceIds) {
        // 整組換掉而不是 clear() 再 addAll()：publish 正在迭代時會看到中間的空集合，那一則告警就漏推了。
        // computeIfPresent 是原子的，也不會把已經 unregister 的連線復活。
        subscriptions.computeIfPresent(subscriberId, (id, previous) -> Set.copyOf(deviceIds));
        nodeSubscriptions.computeIfPresent(subscriberId, (id, previous) -> Set.copyOf(nodeDeviceIds));
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

        boolean telemetry = message instanceof LiveMessage.Telemetry;
        for (Map.Entry<String, Set<String>> entry : subscriptions.entrySet()) {
            boolean wanted = entry.getValue().contains(message.deviceId());
            if (!wanted && !telemetry) {
                Set<String> viaNodes = nodeSubscriptions.get(entry.getKey());
                wanted = viaNodes != null && viaNodes.contains(message.deviceId());
            }
            if (!wanted) {
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

    public int nodeSubscriptionCount(String subscriberId) {
        Set<String> set = nodeSubscriptions.get(subscriberId);
        return set == null ? 0 : set.size();
    }
}
