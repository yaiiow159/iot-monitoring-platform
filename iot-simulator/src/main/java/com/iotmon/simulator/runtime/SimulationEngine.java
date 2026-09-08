package com.iotmon.simulator.runtime;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.simulator.catalog.SimulatedModels;
import com.iotmon.simulator.config.SimulatorProperties;
import com.iotmon.simulator.device.SimulatedDevice;
import com.iotmon.simulator.fault.FaultInjector;
import com.iotmon.simulator.fault.FaultType;
import com.iotmon.simulator.metrics.SimulatorMetrics;
import com.iotmon.simulator.mqtt.MqttConnection;
import com.iotmon.simulator.mqtt.MqttConnectionPool;
import com.iotmon.simulator.mqtt.PayloadCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 建立整批裝置、連線、然後用少量執行緒推動它們回報。
 *
 * <h2>為什麼發佈不用虛擬執行緒</h2>
 *
 * <p>虛擬執行緒的價值在「大量阻塞等待」。發佈這條路徑沒有阻塞——序列化是 CPU 工作，
 * HiveMQ 的 publish 是非阻塞的 Netty 寫入，每秒五萬個虛擬執行緒只會多出排程與堆疊成本。
 * 所以發佈走固定大小的排程器，分片數大約等於核心數。
 *
 * <p>反過來，啟動時建立一萬條連線是「等 broker 回 CONNACK」的純等待，
 * 用平台執行緒做要嘛慢要嘛開一堆執行緒空等，這裡就正好適合虛擬執行緒。
 */
@Component
public class SimulationEngine implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final SimulatorProperties properties;
    private final FaultInjector faults;
    private final MqttConnectionPool connectionPool;
    private final PayloadCodec codec;
    private final SimulatorMetrics metrics;

    private final List<SimulatedDevice> devices = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public SimulationEngine(SimulatorProperties properties, FaultInjector faults,
                            MqttConnectionPool connectionPool, PayloadCodec codec, SimulatorMetrics metrics) {
        this.properties = properties;
        this.faults = faults;
        this.connectionPool = connectionPool;
        this.codec = codec;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        buildFleet();
        connectAll();
        schedulePublishing();
        running = true;
    }

    private void buildFleet() {
        int count = properties.deviceCount();
        FaultType[] assignments = faults.assignments();

        List<DeviceId> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(DeviceId.of(properties.deviceIdPrefix() + String.format("%06d", i + 1)));
        }
        MqttConnection[] links = connectionPool.allocate(ids, assignments);

        long now = System.currentTimeMillis();
        Map<FaultType, Integer> tally = new EnumMap<>(FaultType.class);
        for (int i = 0; i < count; i++) {
            SimulatedDevice device = new SimulatedDevice(
                    ids.get(i), SimulatedModels.forIndex(i), assignments[i], faults,
                    links[i], codec, metrics,
                    // 種子由全域種子與索引推導，同一台裝置每次執行的數值軌跡因此完全一樣
                    properties.seed() * 31 + i);
            device.markStarted(now);
            devices.add(device);
            tally.merge(assignments[i], 1, Integer::sum);
        }

        int faulty = count - tally.getOrDefault(FaultType.NONE, 0);
        metrics.fleetSize(count, faulty);
        log.info("建立 {} 台模擬裝置，故障指派 {}", count, tally);
    }

    /**
     * 連線並宣告上線。用 Semaphore 限制同時進行的 CONNECT——
     * 一萬條同時打過去，broker 會直接把後段的連線拒掉，看起來就像模擬器壞了。
     */
    private void connectAll() {
        Semaphore gate = new Semaphore(properties.mqtt().connectConcurrency());
        AtomicInteger failed = new AtomicInteger();
        List<MqttConnection> connections = connectionPool.connections();

        try (ExecutorService bootstrap = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> pending = new ArrayList<>(connections.size());
            for (MqttConnection connection : connections) {
                pending.add(CompletableFuture.runAsync(() -> {
                    try {
                        gate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        connection.connect().join();
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        log.warn("連線失敗：{}（{}）", connection.clientId(), e.getMessage());
                    } finally {
                        gate.release();
                    }
                }, bootstrap));
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        }
        log.info("MQTT 連線完成，{} 條成功、{} 條失敗", connections.size() - failed.get(), failed.get());

        long now = System.currentTimeMillis();
        for (SimulatedDevice device : devices) {
            if (device.connection().isConnected()) {
                device.announceOnline(now);
                metrics.deviceConnected();
            }
        }
    }

    /**
     * 把裝置切成數個分片，每片一個週期任務。
     * 起始延遲刻意錯開：全部同時發佈會讓每個週期都出現一次尖峰，量到的其實是尖峰而不是穩態吞吐。
     */
    private void schedulePublishing() {
        int shards = properties.resolvedPublisherThreads();
        long interval = properties.publishIntervalMs();
        AtomicInteger threadSeq = new AtomicInteger();
        scheduler = Executors.newScheduledThreadPool(shards,
                r -> new Thread(r, "sim-publisher-" + threadSeq.incrementAndGet()));

        for (int shard = 0; shard < shards; shard++) {
            List<SimulatedDevice> slice = shardOf(shard, shards);
            if (slice.isEmpty()) {
                continue;
            }
            long stagger = interval * shard / shards;
            scheduler.scheduleAtFixedRate(() -> runShard(slice, interval),
                    stagger, interval, TimeUnit.MILLISECONDS);
        }
        log.info("發佈排程啟動：{} 個分片、每 {} ms 一輪", shards, interval);
    }

    private List<SimulatedDevice> shardOf(int shard, int shards) {
        List<SimulatedDevice> slice = new ArrayList<>(devices.size() / shards + 1);
        for (int i = shard; i < devices.size(); i += shards) {
            slice.add(devices.get(i));
        }
        return slice;
    }

    private void runShard(List<SimulatedDevice> slice, long interval) {
        long startedNanos = System.nanoTime();
        long now = System.currentTimeMillis();
        for (SimulatedDevice device : slice) {
            try {
                device.tick(now);
            } catch (RuntimeException e) {
                // 一台裝置出錯不該讓整個分片停擺，否則故障注入會意外地把負載也一起關掉
                metrics.publishFailed();
                log.debug("裝置回報失敗：{}", device.deviceId(), e);
            }
        }
        long elapsedNanos = System.nanoTime() - startedNanos;
        metrics.tickDuration().record(elapsedNanos, TimeUnit.NANOSECONDS);

        // 一輪跑不完一個週期就代表已到吞吐上限，之後的速率數字都會低於設定值
        if (elapsedNanos > TimeUnit.MILLISECONDS.toNanos(interval)) {
            log.warn("分片一輪耗時 {} ms 已超過發佈週期 {} ms，實際速率會低於設定值",
                    TimeUnit.NANOSECONDS.toMillis(elapsedNanos), interval);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        announceOfflineAndDisconnect();
    }

    /** 正常關閉要自己送 OFFLINE：直接斷線的話 broker 只會替每條連線的遺言持有者發一則。 */
    private void announceOfflineAndDisconnect() {
        long now = System.currentTimeMillis();
        List<CompletableFuture<Void>> pending = new ArrayList<>(devices.size());
        for (SimulatedDevice device : devices) {
            if (device.connection().isConnected()) {
                pending.add(device.announceOffline(now));
            }
        }
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("送出離線狀態時逾時或失敗，broker 會以遺言補上部分裝置");
        }
        connectionPool.connections().forEach(MqttConnection::closeNormally);
        log.info("模擬器已停止，{} 台裝置送出離線狀態", pending.size());
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
