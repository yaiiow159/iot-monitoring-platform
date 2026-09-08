package com.iotmon.infrastructure.alarm;

import com.iotmon.application.alarm.AlarmEvaluator;
import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.infrastructure.mqtt.MqttBridge;
import com.iotmon.infrastructure.mqtt.TelemetryEnvelope;
import com.iotmon.infrastructure.persistence.DeviceCatalog;
import com.iotmon.infrastructure.persistence.DeviceIdResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 告警引擎：遙測 → 規則比對 → 告警列 → {@code iot.alarm} 事件。
 *
 * <p>與寫入消費端是**不同的消費群組**。兩者的失敗代價不同：入庫掉一筆是資料缺口，
 * 告警掉一則可能是事故，但它們都不該因為對方慢而受影響（ADR-0003）。
 *
 * <p>「違反門檻」與「該發告警」不是同一件事——抖動的處理在 {@link AlarmEvaluator}。
 * 這裡只負責把狀態機的決定落地：寫資料庫、發事件。
 */
@Component
public class AlarmEngineConsumer {

    public static final String TOPIC_ALARM = "iot.alarm";

    private static final Logger log = LoggerFactory.getLogger(AlarmEngineConsumer.class);

    private final AlarmEvaluator evaluator = new AlarmEvaluator();
    private final AlarmRuleRepository rules;
    private final AlarmRepository alarms;
    private final DeviceCatalog catalog;
    private final DeviceIdResolver deviceIds;
    private final KafkaTemplate<String, Object> kafka;
    private final Counter fired;
    private final Counter resolved;

    public AlarmEngineConsumer(AlarmRuleRepository rules, AlarmRepository alarms, DeviceCatalog catalog,
                               DeviceIdResolver deviceIds, KafkaTemplate<String, Object> kafka,
                               MeterRegistry registry) {
        this.rules = rules;
        this.alarms = alarms;
        this.catalog = catalog;
        this.deviceIds = deviceIds;
        this.kafka = kafka;
        this.fired = Counter.builder("alarm.fired").description("觸發的告警數").register(registry);
        this.resolved = Counter.builder("alarm.resolved").description("解除的告警數").register(registry);
    }

    @KafkaListener(topics = MqttBridge.TOPIC_TELEMETRY, groupId = "iot-alarm-engine")
    public void consume(List<TelemetryEnvelope> envelopes, Acknowledgment ack) {
        Instant now = Instant.now();
        for (TelemetryEnvelope envelope : envelopes) {
            try {
                evaluate(envelope, now);
            } catch (RuntimeException e) {
                // 一台裝置的規則出錯不該讓整批停下：記錄後繼續，偏移量照樣推進。
                // 告警引擎的「重試」沒有意義——同一筆讀數再算一次結果相同。
                log.warn("告警比對失敗 device={}：{}", envelope.deviceId(), e.getMessage());
            }
        }
        ack.acknowledge();
    }

    private void evaluate(TelemetryEnvelope envelope, Instant now) {
        if (envelope.metrics() == null || envelope.metrics().isEmpty()) {
            return;
        }
        DeviceId deviceId = DeviceId.of(envelope.deviceId());
        Optional<ModelCode> model = catalog.modelOf(deviceId);
        if (model.isEmpty()) {
            return; // 未註冊的裝置，寫入端同樣會丟棄
        }
        List<AlarmRule> applicable = rules.enabledRulesFor(deviceId, model.get());
        if (applicable.isEmpty()) {
            return;
        }
        Integer deviceRowId = deviceIds.numericIdOf(deviceId);
        if (deviceRowId == null) {
            return;
        }

        for (AlarmRule rule : applicable) {
            Double value = envelope.metrics().get(rule.metric().value());
            if (value == null) {
                continue;
            }
            AlarmEvaluator.Decision decision = evaluator.evaluate(deviceId, rule, value, now);
            switch (decision) {
                case FIRE -> fire(deviceRowId, deviceId, rule, value, now);
                case RESOLVE -> resolve(deviceRowId, deviceId, rule, now);
                case NOTHING -> { }
            }
        }
    }

    private void fire(int deviceRowId, DeviceId deviceId, AlarmRule rule, double value, Instant at) {
        Long nodeId = catalog.treeNodeOf(deviceRowId).orElse(null);
        // 撞上「同裝置同規則只能有一則未解除」的索引時回 empty：已經在響，不重複推播
        alarms.fire(deviceRowId, rule.id(), rule.severity(), value, at, nodeId).ifPresent(alarmId -> {
            fired.increment();
            publish(new AlarmEvent(alarmId, deviceId.value(), rule.id(), rule.severity().name(),
                    "FIRING", value, at.toEpochMilli()));
        });
    }

    private void resolve(int deviceRowId, DeviceId deviceId, AlarmRule rule, Instant at) {
        alarms.resolve(deviceRowId, rule.id(), at).ifPresent(alarmId -> {
            resolved.increment();
            publish(new AlarmEvent(alarmId, deviceId.value(), rule.id(), rule.severity().name(),
                    "RESOLVED", null, at.toEpochMilli()));
        });
    }

    private void publish(AlarmEvent event) {
        // 分區鍵用 deviceId：同一台裝置的 FIRING → RESOLVED 順序才有保證
        kafka.send(TOPIC_ALARM, event.deviceId(), event);
    }

    /**
     * 裝置規則接管某個指標：解除該裝置上同指標機型規則的告警並推播，
     * 再清掉它的累積狀態，讓新規則從零開始計算持續時間。
     *
     * @return 被解除的告警數
     */
    public int supersede(DeviceId deviceId, MetricKey metric) {
        Integer deviceRowId = deviceIds.numericIdOf(deviceId);
        if (deviceRowId == null) {
            return 0;
        }
        Instant now = Instant.now();
        List<AlarmRepository.Superseded> superseded = alarms.resolveModelRuleAlarms(deviceRowId, metric.value(), now);
        for (AlarmRepository.Superseded s : superseded) {
            resolved.increment();
            publish(new AlarmEvent(s.alarmId(), deviceId.value(), s.ruleId(), s.severity(), "RESOLVED", null,
                    now.toEpochMilli()));
        }
        evaluator.forget(deviceId);
        return superseded.size();
    }

    /** 裝置離線時清掉它的累積狀態（見 AlarmEvaluator.forget 的說明） */
    public void forget(DeviceId deviceId) {
        evaluator.forget(deviceId);
    }

    /** 供測試與 actuator 觀察 */
    public Map<String, Integer> stats() {
        return Map.of("trackedBreaches", evaluator.trackedBreaches());
    }

    /** 讓其他消費端能重用同一組指標鍵型別 */
    static MetricKey metric(String key) {
        return MetricKey.of(key);
    }
}
