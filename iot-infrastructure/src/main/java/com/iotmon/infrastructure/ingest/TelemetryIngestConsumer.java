package com.iotmon.infrastructure.ingest;

import com.iotmon.application.port.out.TelemetryWriter;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.telemetry.TelemetryPoint;
import com.iotmon.infrastructure.mqtt.TelemetryEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kafka → TimescaleDB 的寫入消費端。
 *
 * <p>用批次監聽（`listener.type: batch`）而不是逐筆：一次 poll 拿回最多 2000 則訊息，
 * 展開成資料點後一次寫入。逐筆消費在每秒五萬點下等於每秒五萬次資料庫往返。
 *
 * <p>**手動 ack，而且只在寫入成功後才 ack。** 自動 ack 會在訊息交給處理邏輯的當下
 * 就推進偏移量，寫入失敗時那批資料就永久遺失了——而時序資料的缺口沒辦法從別處回推。
 * 寧可重複寫入（聚合時被 avg 吸收），也不要缺口。
 */
@Component
public class TelemetryIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestConsumer.class);

    private final TelemetryWriter writer;
    private final Counter ingested;
    private final Counter rejected;

    public TelemetryIngestConsumer(TelemetryWriter writer, MeterRegistry registry) {
        this.writer = writer;
        this.ingested = Counter.builder("telemetry.ingested")
                .description("成功寫入的資料點數").register(registry);
        this.rejected = Counter.builder("telemetry.rejected")
                .description("因格式或內容不合法而丟棄的資料點數").register(registry);
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

        try {
            int written = writer.write(points);
            ingested.increment(written);
            ack.acknowledge();
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
