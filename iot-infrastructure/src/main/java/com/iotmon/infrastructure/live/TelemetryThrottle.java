package com.iotmon.infrastructure.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遙測推播的節流：同一台裝置在一個刷新週期內只保留最後一筆。
 *
 * <p>人眼看不出每秒五次的差別，但瀏覽器會因此卡住——一萬台裝置每秒五萬則
 * 訊息全推出去，前端的 render 佇列會先被塞死。狀態與告警不走這裡，
 * 那些是低頻但每一則都重要。
 *
 * <p>純邏輯、沒有排程器：什麼時候 flush 由呼叫端決定，測試才不必等真的一秒。
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
