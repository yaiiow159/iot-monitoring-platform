package com.iotmon.infrastructure.ingest;

import com.iotmon.application.port.out.TelemetryWriter;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.telemetry.TelemetryPoint;
import com.iotmon.infrastructure.mqtt.TelemetryEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Kafka → TimescaleDB 的寫入消費端。批次監聽、一次寫入：逐筆消費等於每秒數萬次資料庫往返。
 * 手動 ack 且只在寫入成功後 ack：寧可重複寫入（聚合時被 avg 吸收），也不要時序資料出現缺口。
 */
@Component
public class TelemetryIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestConsumer.class);

    private final TelemetryWriter writer;
    private final DeviceLiveness liveness;
    private final Counter ingested;
    private final Counter rejected;
    private final Timer endToEndLatency;
    private final DistributionSummary batchSize;

    public TelemetryIngestConsumer(TelemetryWriter writer, DeviceLiveness liveness, MeterRegistry registry) {
        this.writer = writer;
        this.liveness = liveness;
        this.ingested = Counter.builder("telemetry.ingested")
                .description("成功寫入的資料點數").register(registry);
        this.rejected = Counter.builder("telemetry.rejected")
                .description("因格式或內容不合法而丟棄的資料點數").register(registry);
        // 裝置打時間戳到寫入資料庫之間的延遲。這是「消費端跟不跟得上」最直接的訊號：
        // lag 是積壓的訊息數，延遲才是使用者感受得到的秒數
        this.endToEndLatency = Timer.builder("telemetry.ingest.latency")
                .description("裝置時間戳到入庫的延遲")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.batchSize = DistributionSummary.builder("telemetry.ingest.batch.size")
                .description("每次 poll 展開後的資料點數")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    @KafkaListener(topics = "iot.telemetry", groupId = "iot-telemetry-writer")
    public void consume(List<TelemetryEnvelope> envelopes, Acknowledgment ack) {
        if (envelopes == null || envelopes.isEmpty()) {
            ack.acknowledge();
            return;
        }

        List<TelemetryPoint> points = new ArrayList<>(envelopes.size() * 4);
        for (TelemetryEnvelope envelope : envelopes) {
            expand(envelope, points);
        }

        if (points.isEmpty()) {
            ack.acknowledge();
            return;
        }

        batchSize.record(points.size());
        try {
            int written = writer.write(points);
            ingested.increment(written);
            ack.acknowledge();
            // 只量整批裡最舊的那一則：批次內的差距是毫秒級，逐筆記錄每秒要多五萬次計時
            long oldestTs = Long.MAX_VALUE;
            for (TelemetryEnvelope envelope : envelopes) {
                if (envelope != null && envelope.ts() < oldestTs) {
                    oldestTs = envelope.ts();
                }
            }
            if (oldestTs != Long.MAX_VALUE) {
                endToEndLatency.record(Math.max(0, System.currentTimeMillis() - oldestTs), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            // 不 ack：這批會被重新投遞。重複寫入是可接受的，缺口不是。
            log.error("批次寫入失敗，{} 筆將重試：{}", points.size(), e.getMessage());
            throw e;
        }
    }

    /** 把「一則訊息多個指標」展開成多個資料點 */
    private void expand(TelemetryEnvelope envelope, List<TelemetryPoint> out) {
        if (envelope == null || envelope.metrics() == null) {
            rejected.increment();
            return;
        }
        Instant timestamp = Instant.ofEpochMilli(envelope.ts());
        // 有遙測就是在線：記在記憶體，由 DeviceLiveness 整批寫回
        if (envelope.deviceId() != null) {
            liveness.seen(envelope.deviceId(), envelope.ts());
        }

        for (Map.Entry<String, Double> entry : envelope.metrics().entrySet()) {
            Double value = entry.getValue();
            if (value == null) {
                rejected.increment();
                continue;
            }
            try {
                out.add(new TelemetryPoint(
                        DeviceId.of(envelope.deviceId()),
                        MetricKey.of(entry.getKey()),
                        value,
                        timestamp));
            } catch (IllegalArgumentException invalid) {
                // 識別碼格式不符、NaN、無限大——在這裡擋掉，
                // 不要讓它們進到聚合層之後才發現（那時已經無法分辨了）
                rejected.increment();
            }
        }
    }
}
