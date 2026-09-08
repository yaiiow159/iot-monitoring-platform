package com.iotmon.simulator.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模擬器自己的量測。壓測時第一個要回答的問題是「負載真的送出去了嗎」——
 * 沒有這組數字，平台端的低吞吐分不清是平台慢還是模擬器根本沒送那麼多。
 */
@Component
public class SimulatorMetrics {

    private final Counter telemetryPublished;
    private final Counter statusPublished;
    private final Counter publishFailed;
    private final Counter pointsPublished;
    private final Counter silentSkipped;
    private final Timer tickDuration;

    private final AtomicInteger totalDevices = new AtomicInteger();
    private final AtomicInteger connectedDevices = new AtomicInteger();
    private final AtomicInteger faultyDevices = new AtomicInteger();

    public SimulatorMetrics(MeterRegistry registry) {
        this.telemetryPublished = Counter.builder("simulator.messages.published")
                .description("送出的 MQTT 訊息數")
                .tag("kind", "telemetry")
                .register(registry);
        this.statusPublished = Counter.builder("simulator.messages.published")
                .description("送出的 MQTT 訊息數")
                .tag("kind", "status")
                .register(registry);
        this.publishFailed = Counter.builder("simulator.messages.failed")
                .description("發佈失敗的訊息數")
                .register(registry);
        // 契約是一則訊息帶多個指標，所以「訊息數」不等於平台看到的「資料點數」，兩個都要量
        this.pointsPublished = Counter.builder("simulator.points.published")
                .description("送出的遙測資料點數")
                .register(registry);
        this.silentSkipped = Counter.builder("simulator.messages.suppressed")
                .description("因 SILENT 故障而未送出的訊息數")
                .register(registry);
        this.tickDuration = Timer.builder("simulator.tick.duration")
                .description("單一分片一輪發佈的耗時")
                .register(registry);

        // 不用 .total 結尾：Prometheus 的命名慣例把 _total 留給計數器，Micrometer 會直接把它砍掉
        Gauge.builder("simulator.devices.provisioned", totalDevices, AtomicInteger::get).register(registry);
        Gauge.builder("simulator.devices.connected", connectedDevices, AtomicInteger::get).register(registry);
        Gauge.builder("simulator.devices.faulty", faultyDevices, AtomicInteger::get).register(registry);
    }

    public void telemetrySent(int pointCount) {
        telemetryPublished.increment();
        pointsPublished.increment(pointCount);
    }

    public void statusSent() {
        statusPublished.increment();
    }

    public void publishFailed() {
        publishFailed.increment();
    }

    public void suppressed() {
        silentSkipped.increment();
    }

    public Timer tickDuration() {
        return tickDuration;
    }

    public void fleetSize(int total, int faulty) {
        totalDevices.set(total);
        faultyDevices.set(faulty);
    }

    public void deviceConnected() {
        connectedDevices.incrementAndGet();
    }

    public void deviceDisconnected() {
        connectedDevices.decrementAndGet();
    }
}
