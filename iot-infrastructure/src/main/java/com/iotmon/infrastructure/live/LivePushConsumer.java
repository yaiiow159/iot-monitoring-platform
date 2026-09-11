package com.iotmon.infrastructure.live;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.infrastructure.alarm.AlarmEngineConsumer;
import com.iotmon.infrastructure.alarm.AlarmEvent;
import com.iotmon.infrastructure.mqtt.MqttBridge;
import com.iotmon.infrastructure.mqtt.StatusEnvelope;
import com.iotmon.infrastructure.mqtt.TelemetryEnvelope;
import com.iotmon.infrastructure.persistence.DeviceIdResolver;
import com.iotmon.infrastructure.persistence.MonitoringTreeRepository;
import com.iotmon.infrastructure.persistence.Rows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推播消費端：把 Kafka 上的三種事件送到 WebSocket。
 *
 * <p>自成一個消費群組（{@code iot-live-push}），且 {@code auto-offset-reset=latest}：
 * 推播掉一則沒關係，積壓時應該直接跳到最新——舊的遙測推給前端沒有意義，
 * 而入庫消費端絕不能這樣做。這正是 ADR-0003 要分開消費群組的理由。
 */
@Component
public class LivePushConsumer {

    private static final Logger log = LoggerFactory.getLogger(LivePushConsumer.class);

    private final LiveSessionRegistry sessions;
    private final TelemetryThrottle throttle = new TelemetryThrottle();
    private final MonitoringTreeRepository tree;
    private final DeviceIdResolver deviceIds;
    private final AlarmEngineConsumer alarmEngine;
    private final JdbcTemplate jdbc;

    public LivePushConsumer(LiveSessionRegistry sessions, MonitoringTreeRepository tree,
                            DeviceIdResolver deviceIds, AlarmEngineConsumer alarmEngine, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.tree = tree;
        this.deviceIds = deviceIds;
        this.alarmEngine = alarmEngine;
        this.jdbc = jdbc;
    }

    /** 遙測只進節流器，由 {@link #flushTelemetry} 每秒送一次 */
    @KafkaListener(topics = MqttBridge.TOPIC_TELEMETRY, groupId = "iot-live-push",
            properties = "auto.offset.reset=latest")
    public void onTelemetry(List<TelemetryEnvelope> envelopes, Acknowledgment ack) {
        if (sessions.sessionCount() > 0) {
            for (TelemetryEnvelope e : envelopes) {
                if (e.metrics() != null) {
                    throttle.offer(new LiveMessage.Telemetry(e.deviceId(), e.metrics(), e.ts()));
                }
            }
        }
        ack.acknowledge();
    }

    @Scheduled(fixedRate = 1000)
    public void flushTelemetry() {
        for (LiveMessage.Telemetry message : throttle.drain()) {
            sessions.publish(message);
        }
    }

    /**
     * 上下線：先落地到 device 表（儀表板的狀態分佈靠它），再推播。
     * 離線時同時清掉告警引擎的累積狀態，否則重新上線會因為離線期間而立刻告警。
     */
    @KafkaListener(topics = MqttBridge.TOPIC_DEVICE_STATUS, groupId = "iot-live-push")
    public void onStatus(List<StatusEnvelope> envelopes, Acknowledgment ack) {
        try {
            applyStatuses(envelopes);
        } catch (RuntimeException ex) {
            log.warn("狀態更新失敗（{} 則）：{}", envelopes.size(), ex.getMessage());
        }
        ack.acknowledge();
    }

    /**
     * 整批一次更新。一萬台同時重連時逐台 UPDATE 就是一萬次往返，
     * 而那正是這條路徑唯一會忙的時刻（見 performance.md）。
     */
    private void applyStatuses(List<StatusEnvelope> envelopes) {
        // 同一台裝置在一批裡可能上下線好幾次，只有最後一則算數
        Map<String, StatusEnvelope> latest = new LinkedHashMap<>();
        for (StatusEnvelope e : envelopes) {
            latest.merge(e.deviceId(), e, (older, newer) -> newer.ts() >= older.ts() ? newer : older);
        }

        Instant now = Instant.now();
        List<StatusEnvelope> offline = new ArrayList<>();
        List<StatusEnvelope> online = new ArrayList<>();
        for (StatusEnvelope e : latest.values()) {
            ("OFFLINE".equals(stateOf(e)) ? offline : online).add(e);
        }

        // RETURNING 過濾掉未註冊的裝置，行為與原本 updated == 0 就跳過一致
        Set<String> applied = new HashSet<>();
        applied.addAll(updateOffline(offline));
        applied.addAll(updateOnline(online, now));

        List<DeviceId> wentOffline = offline.stream()
                .filter(e -> applied.contains(e.deviceId()))
                .map(e -> DeviceId.of(e.deviceId()))
                .toList();
        alarmEngine.forgetAll(wentOffline);

        for (StatusEnvelope e : latest.values()) {
            if (!applied.contains(e.deviceId())) {
                continue;
            }
            String state = stateOf(e);
            // LWT 的 ts 是連線當下的時間（遺言在連線時就定案），離線時間以收到的時刻為準
            long ts = "OFFLINE".equals(state) ? now.toEpochMilli() : e.ts();
            sessions.publish(new LiveMessage.Status(e.deviceId(), state, ts));
        }
    }

    private static String stateOf(StatusEnvelope e) {
        return e.state() == null ? "UNKNOWN" : e.state().toUpperCase();
    }

    private List<String> updateOffline(List<StatusEnvelope> offline) {
        if (offline.isEmpty()) {
            return List.of();
        }
        String placeholders = Rows.placeholders(offline.size());
        return jdbc.query("UPDATE device SET status = 'OFFLINE' WHERE device_id IN (" + placeholders
                        + ") RETURNING device_id",
                (rs, i) -> rs.getString(1),
                offline.stream().map(StatusEnvelope::deviceId).toArray());
    }

    private List<String> updateOnline(List<StatusEnvelope> online, Instant now) {
        if (online.isEmpty()) {
            return List.of();
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>(online.size() * 2);
        for (StatusEnvelope e : online) {
            values.append(values.isEmpty() ? "" : ",").append("(?, ?::timestamptz)");
            args.add(e.deviceId());
            args.add(Timestamp.from(Instant.ofEpochMilli(e.ts())));
        }
        return jdbc.query("""
                UPDATE device d SET status = 'ONLINE', last_seen_at = v.ts
                FROM (VALUES %s) AS v(device_id, ts)
                WHERE d.device_id = v.device_id
                RETURNING d.device_id
                """.formatted(values), (rs, i) -> rs.getString(1), args.toArray());
    }

    /**
     * 告警：補上祖先鏈再推。整批一次查——一次機櫃斷線就是幾百則，
     * 逐則查等於把 N+1 搬到推播端（見 performance.md）。
     */
    @KafkaListener(topics = AlarmEngineConsumer.TOPIC_ALARM, groupId = "iot-live-push")
    public void onAlarm(List<AlarmEvent> events, Acknowledgment ack) {
        Map<String, Integer> rowIds = new LinkedHashMap<>();
        for (AlarmEvent event : events) {
            Integer rowId = deviceIds.numericIdOf(DeviceId.of(event.deviceId()));
            if (rowId != null) {
                rowIds.put(event.deviceId(), rowId);
            }
        }
        Map<Integer, List<Long>> ancestorsByRow = tree.findAncestorIdsOfDevices(rowIds.values());

        for (AlarmEvent event : events) {
            Integer rowId = rowIds.get(event.deviceId());
            List<Long> ancestors = rowId == null ? List.of()
                    : ancestorsByRow.getOrDefault(rowId, List.of());
            sessions.publish(new LiveMessage.Alarm(event.alarmId(), event.deviceId(), event.severity(),
                    event.state(), ancestors, event.ts()));
        }
        ack.acknowledge();
    }
}
