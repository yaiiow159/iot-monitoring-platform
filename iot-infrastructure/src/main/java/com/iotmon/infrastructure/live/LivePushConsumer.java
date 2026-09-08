package com.iotmon.infrastructure.live;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.infrastructure.alarm.AlarmEngineConsumer;
import com.iotmon.infrastructure.alarm.AlarmEvent;
import com.iotmon.infrastructure.mqtt.MqttBridge;
import com.iotmon.infrastructure.mqtt.StatusEnvelope;
import com.iotmon.infrastructure.mqtt.TelemetryEnvelope;
import com.iotmon.infrastructure.persistence.DeviceIdResolver;
import com.iotmon.infrastructure.persistence.MonitoringTreeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

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
        for (StatusEnvelope e : envelopes) {
            try {
                applyStatus(e);
            } catch (RuntimeException ex) {
                log.warn("狀態更新失敗 device={}：{}", e.deviceId(), ex.getMessage());
            }
        }
        ack.acknowledge();
    }

    private void applyStatus(StatusEnvelope e) {
        String state = e.state() == null ? "UNKNOWN" : e.state().toUpperCase();
        // LWT 的 ts 是連線當下的時間（遺言在連線時就定案），離線時間以收到的時刻為準
        Instant seenAt = "OFFLINE".equals(state) ? Instant.now() : Instant.ofEpochMilli(e.ts());
        int updated = "OFFLINE".equals(state)
                ? jdbc.update("UPDATE device SET status = 'OFFLINE' WHERE device_id = ?", e.deviceId())
                : jdbc.update("UPDATE device SET status = 'ONLINE', last_seen_at = ? WHERE device_id = ?",
                        Timestamp.from(seenAt), e.deviceId());
        if (updated == 0) {
            return; // 未註冊的裝置
        }
        if ("OFFLINE".equals(state)) {
            alarmEngine.forget(DeviceId.of(e.deviceId()));
        }
        sessions.publish(new LiveMessage.Status(e.deviceId(), state, seenAt.toEpochMilli()));
    }

    /** 告警：補上祖先鏈再推。祖先鏈是一次 ltree 查詢（路徑的所有前綴）。 */
    @KafkaListener(topics = AlarmEngineConsumer.TOPIC_ALARM, groupId = "iot-live-push")
    public void onAlarm(List<AlarmEvent> events, Acknowledgment ack) {
        for (AlarmEvent event : events) {
            List<Long> ancestors = List.of();
            Integer rowId = deviceIds.numericIdOf(DeviceId.of(event.deviceId()));
            if (rowId != null) {
                ancestors = tree.findAncestorIdsOfDevice(rowId);
            }
            sessions.publish(new LiveMessage.Alarm(event.alarmId(), event.deviceId(), event.severity(),
                    event.state(), ancestors, event.ts()));
        }
        ack.acknowledge();
    }
}
