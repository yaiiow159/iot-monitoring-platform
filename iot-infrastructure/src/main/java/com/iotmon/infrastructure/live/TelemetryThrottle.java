package com.iotmon.infrastructure.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遙測推播的節流：同一台裝置在一個刷新週期內只留最後一筆，否則瀏覽器的 render 佇列會先被塞死。
 * 狀態與告警不走這裡。純邏輯沒有排程器，flush 由呼叫端決定，測試不必等真的一秒。
 */
public class TelemetryThrottle {

    private final Map<String, LiveMessage.Telemetry> latest = new ConcurrentHashMap<>();

    /** 記下這台裝置的最新一筆；同一週期內舊的直接被蓋掉。 */
    public void offer(LiveMessage.Telemetry message) {
        latest.merge(message.deviceId(), message,
                (old, fresh) -> fresh.ts() >= old.ts() ? fresh : old);
    }

    /** 取出並清空這一週期累積的每台裝置最後一筆 */
    public List<LiveMessage.Telemetry> drain() {
        if (latest.isEmpty()) {
            return List.of();
        }
        List<LiveMessage.Telemetry> batch = new ArrayList<>(latest.size());
        // 逐鍵移除而不是 clear()：clear 與 offer 之間有競態，會把剛進來的一筆一起清掉
        for (String deviceId : List.copyOf(latest.keySet())) {
            LiveMessage.Telemetry message = latest.remove(deviceId);
            if (message != null) {
                batch.add(message);
            }
        }
        return batch;
    }

    public int pending() {
        return latest.size();
    }
}
