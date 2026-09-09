package com.iotmon.infrastructure.ingest;

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
 * 「有遙測進來就是在線」的補網。
 *
 * <p>上下線的主要來源是 MQTT 的連線事件與 LWT（ADR-0004），但那條路徑只在連線變化時說話一次；
 * 一萬台裝置同時重連的那幾秒，狀態訊息若有任何一則掉了，那台裝置就會一直顯示離線——
 * 即使它每秒都在送遙測。實測時 7,899 台就是這樣被標成離線的。
 *
 * <p>做法：寫入消費端每收到一筆遙測就在記憶體裡記下「最後看到的時間」（一個 map 的 put，每秒五萬次也無感），
 * 每 10 秒整批寫回資料庫一次；反過來，太久沒有遙測也沒有 LWT 的裝置，由同一個排程標成離線。
 * 每點都更新資料庫是不可能的：那是每秒五萬次 UPDATE。
 */
@Component
public class DeviceLiveness {

    private static final Logger log = LoggerFactory.getLogger(DeviceLiveness.class);

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final LiveSessionRegistry sessions;
    private final long silenceThresholdMs;
    private final Counter flippedOnline;
    private final Counter flippedOffline;

    public DeviceLiveness(JdbcTemplate jdbc, LiveSessionRegistry sessions, MeterRegistry registry,
                          @Value("${iot.offline-detection.silence-threshold-seconds:180}") long silenceThresholdSeconds) {
        this.jdbc = jdbc;
        this.sessions = sessions;
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
        List<Object[]> rows = new ArrayList<>(lastSeen.size());
        for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
            rows.add(new Object[]{e.getKey(), Timestamp.from(Instant.ofEpochMilli(e.getValue()))});
        }
        lastSeen.clear();

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
                sessions.publish(new LiveMessage.Status(id, "OFFLINE", now));
            }
            log.info("{} 台裝置超過 {} 秒沒有遙測，改為離線", silent.size(), silenceThresholdMs / 1000);
        }
    }
}
