package com.iotmon.infrastructure.ingest;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.infrastructure.alarm.AlarmEngineConsumer;
import com.iotmon.infrastructure.live.LiveMessage;
import com.iotmon.infrastructure.live.LiveSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有遙測就是在線。上下線的主要來源是 LWT（ADR-0004），但一萬台同時重連時掉幾則狀態訊息，
 * 那幾台就永遠顯示離線。每筆遙測只在記憶體記時間，每 10 秒整批寫回；太久沒遙測才標離線。
 */
@Component
public class DeviceLiveness {

    private static final Logger log = LoggerFactory.getLogger(DeviceLiveness.class);

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final LiveSessionRegistry sessions;
    private final AlarmEngineConsumer alarmEngine;
    private final long silenceThresholdMs;
    private final Counter flippedOnline;
    private final Counter flippedOffline;

    public DeviceLiveness(JdbcTemplate jdbc, LiveSessionRegistry sessions, AlarmEngineConsumer alarmEngine,
                          MeterRegistry registry,
                          @Value("${iot.offline-detection.silence-threshold-seconds:180}") long silenceThresholdSeconds) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.alarmEngine = alarmEngine;
        this.silenceThresholdMs = silenceThresholdSeconds * 1000;
        this.flippedOnline = Counter.builder("device.liveness.online")
                .description("因為收到遙測而從非在線改為在線的裝置數").register(registry);
        this.flippedOffline = Counter.builder("device.liveness.offline")
                .description("因為太久沒有遙測而改為離線的裝置數").register(registry);
    }

    /** 熱路徑：只是一個 map put。 */
    public void seen(String deviceId, long epochMillis) {
        lastSeen.merge(deviceId, epochMillis, Math::max);
    }

    public int trackedDevices() {
        return lastSeen.size();
    }

    /** 每 10 秒把記憶體裡的「最後看到」整批寫回，並把翻成在線的裝置推播出去。 */
    @Scheduled(fixedRate = 10_000, initialDelay = 10_000)
    public void flush() {
        if (lastSeen.isEmpty()) {
            return;
        }
        // 逐鍵移除而不是 clear()：迭代與 clear 之間進來的 seen() 會被一起清掉（同 TelemetryThrottle.drain）
        List<Object[]> rows = new ArrayList<>(lastSeen.size());
        for (String deviceId : List.copyOf(lastSeen.keySet())) {
            Long seenAt = lastSeen.remove(deviceId);
            if (seenAt != null) {
                rows.add(new Object[]{deviceId, Timestamp.from(Instant.ofEpochMilli(seenAt))});
            }
        }

        // 兩段：先把不在線的翻成在線（要推播），再更新其餘的 last_seen_at（不推播）。
        // 一萬台裝置 10 秒一次，是幾毫秒的事；分成 500 筆一批避免單一 SQL 太長。
        List<String> flipped = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += 500) {
            List<Object[]> chunk = rows.subList(i, Math.min(rows.size(), i + 500));
            StringBuilder values = new StringBuilder();
            List<Object> args = new ArrayList<>(chunk.size() * 2);
            for (Object[] r : chunk) {
                values.append(values.length() == 0 ? "" : ",").append("(?, ?::timestamptz)");
                args.add(r[0]);
                args.add(r[1]);
            }
            flipped.addAll(jdbc.query("""
                    UPDATE device d SET status = 'ONLINE', last_seen_at = v.ts
                    FROM (VALUES %s) AS v(device_id, ts)
                    WHERE d.device_id = v.device_id AND d.status <> 'ONLINE'
                    RETURNING d.device_id
                    """.formatted(values), (rs, n) -> rs.getString(1), args.toArray()));
            jdbc.update("""
                    UPDATE device d SET last_seen_at = v.ts
                    FROM (VALUES %s) AS v(device_id, ts)
                    WHERE d.device_id = v.device_id AND (d.last_seen_at IS NULL OR d.last_seen_at < v.ts)
                    """.formatted(values), args.toArray());
        }
        if (!flipped.isEmpty()) {
            flippedOnline.increment(flipped.size());
            long now = System.currentTimeMillis();
            for (String id : flipped) {
                sessions.publish(new LiveMessage.Status(id, "ONLINE", now));
            }
            log.info("{} 台裝置因為收到遙測而改為在線", flipped.size());
        }
    }

    /** LWT 是主要手段；這是 broker 自己掛掉、遺言沒發出來時的補網，門檻是分鐘級。 */
    @Scheduled(fixedRateString = "${iot.offline-detection.scan-interval-seconds:30}000", initialDelay = 30_000)
    public void markSilentOffline() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusMillis(silenceThresholdMs));
        List<String> silent = jdbc.query("""
                UPDATE device SET status = 'OFFLINE'
                WHERE status = 'ONLINE' AND (last_seen_at IS NULL OR last_seen_at < ?)
                RETURNING device_id
                """, (rs, n) -> rs.getString(1), cutoff);
        if (!silent.isEmpty()) {
            flippedOffline.increment(silent.size());
            long now = System.currentTimeMillis();
            for (String id : silent) {
                // LWT 那條路徑會清，這條補網路徑漏掉的話，重新上線會因為離線期間而立刻告警
                alarmEngine.forget(DeviceId.of(id));
                sessions.publish(new LiveMessage.Status(id, "OFFLINE", now));
            }
            log.info("{} 台裝置超過 {} 秒沒有遙測，改為離線", silent.size(), silenceThresholdMs / 1000);
        }
    }
}
