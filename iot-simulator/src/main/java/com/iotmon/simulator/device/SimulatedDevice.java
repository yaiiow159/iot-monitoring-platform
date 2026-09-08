package com.iotmon.simulator.device;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.simulator.catalog.MetricProfile;
import com.iotmon.simulator.catalog.SimulatedModel;
import com.iotmon.simulator.fault.FaultInjector;
import com.iotmon.simulator.fault.FaultType;
import com.iotmon.simulator.metrics.SimulatorMetrics;
import com.iotmon.simulator.mqtt.MqttConnection;
import com.iotmon.simulator.mqtt.MqttTopics;
import com.iotmon.simulator.mqtt.PayloadCodec;
import com.iotmon.simulator.mqtt.StatusMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

/**
 * 一台模擬裝置。
 *
 * <p>沒有自己的執行緒：一萬台各配一條就是一萬條執行緒，光是堆疊就吃掉數 GB。
 * 每台裝置固定由同一個分片執行緒驅動，所以內部狀態不加鎖——只有重連回呼會碰到的欄位標成 volatile。
 */
public final class SimulatedDevice {

    private static final double[] POW10 = {1, 10, 100, 1_000};

    private final DeviceId deviceId;
    private final SimulatedModel model;
    private final FaultType fault;
    private final FaultInjector faults;
    private final MqttConnection connection;
    private final PayloadCodec codec;
    private final SimulatorMetrics metrics;

    private final String telemetryTopic;
    private final String statusTopic;

    private final List<MetricProfile> profiles;
    private final double[] values;
    private final double[] baselines;
    private final SplittableRandom random;
    private final int brokenMetricIndex;
    private final long clockDriftMs;

    /** 每輪重用同一個 Map。每秒五萬則訊息的情況下，逐則新建只是白白製造 GC 壓力。 */
    private final Map<String, Double> payloadBuffer;

    private long startedAtMs;
    private long lastFlapChangeMs;
    private volatile boolean flapOnline = true;
    private volatile boolean reconnecting;

    public SimulatedDevice(DeviceId deviceId, SimulatedModel model, FaultType fault, FaultInjector faults,
                           MqttConnection connection, PayloadCodec codec, SimulatorMetrics metrics, long seed) {
        this.deviceId = deviceId;
        this.model = model;
        this.fault = fault;
        this.faults = faults;
        this.connection = connection;
        this.codec = codec;
        this.metrics = metrics;
        this.telemetryTopic = MqttTopics.telemetry(model.modelCode(), deviceId);
        this.statusTopic = MqttTopics.status(deviceId);
        this.profiles = model.profiles();
        this.values = new double[profiles.size()];
        this.baselines = new double[profiles.size()];
        // 每台裝置自己的亂數源。共用一個的話分片執行緒會在它上面互相搶，而且失去可重現性
        this.random = new SplittableRandom(seed);
        this.brokenMetricIndex = random.nextInt(profiles.size());
        this.clockDriftMs = faults.clockDriftMillis(fault);
        this.payloadBuffer = new LinkedHashMap<>(profiles.size() * 2);
        seedInitialValues();
    }

    /**
     * 起始值散佈在運轉帶內，不是一律從中點開始。
     * 全部從同一點出發的話，一萬台的曲線會在前幾分鐘疊成一條，看不出裝置之間的差異。
     */
    private void seedInitialValues() {
        for (int i = 0; i < profiles.size(); i++) {
            MetricProfile profile = profiles.get(i);
            if (profile.kind() == MetricProfile.Kind.BINARY) {
                values[i] = 0;
                baselines[i] = 0;
            } else {
                double baseline = profile.bandLow() + random.nextDouble() * profile.bandSpan();
                baselines[i] = baseline;
                values[i] = baseline;
            }
        }
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public FaultType fault() {
        return fault;
    }

    public MqttConnection connection() {
        return connection;
    }

    public void markStarted(long nowMs) {
        this.startedAtMs = nowMs;
        this.lastFlapChangeMs = nowMs;
    }

    /** 由分片排程呼叫，走完「該不該說話 → 產生數值 → 發佈」一輪。 */
    public void tick(long nowMs) {
        if (fault == FaultType.OFFLINE_FLAPPING && !flapStep(nowMs)) {
            return;
        }
        if (faults.shouldStayQuiet(fault, nowMs - startedAtMs)) {
            metrics.suppressed();
            return;
        }

        payloadBuffer.clear();
        for (int i = 0; i < profiles.size(); i++) {
            MetricProfile profile = profiles.get(i);
            double value = advance(i, profile);
            value = faults.distort(fault, profile, value, i == brokenMetricIndex, random);
            payloadBuffer.put(profile.keyName(), round(value, profile.decimals()));
        }

        // ts 是裝置端取樣時間；CLOCK_DRIFT 就是讓它落後，好觀察平台以哪個時間入庫
        long ts = nowMs - clockDriftMs;
        publish(telemetryTopic, codec.telemetry(deviceId, ts, payloadBuffer), false);
        metrics.telemetrySent(payloadBuffer.size());
    }

    /**
     * 隨機遊走 ＋ 均值回歸。純均勻亂數畫出來只是雜訊，看不出趨勢，
     * 「持續升溫」這類告警規則也就無從驗證；沒有回歸項則會漂到邊界貼著跑。
     */
    private double advance(int index, MetricProfile profile) {
        if (profile.kind() == MetricProfile.Kind.BINARY) {
            if (random.nextDouble() < profile.flipProbability()) {
                values[index] = values[index] == 0 ? 1 : 0;
            }
            return values[index];
        }
        double step = random.nextGaussian() * profile.bandSpan() * profile.volatility();
        double pull = (baselines[index] - values[index]) * MetricProfile.MEAN_REVERSION;
        values[index] = Math.clamp(values[index] + step + pull, profile.bandLow(), profile.bandHigh());
        return values[index];
    }

    public CompletableFuture<Void> announceOnline(long nowMs) {
        return publishStatus(StatusMessage.ONLINE, nowMs);
    }

    /**
     * 正常關閉時自己送一則 OFFLINE。契約上它跟 broker 代發的遺言對平台沒有差別，
     * 但主動送出讓共用連線的裝置也能各自報離線，不受「一條連線只有一則遺言」的限制。
     */
    public CompletableFuture<Void> announceOffline(long nowMs) {
        return publishStatus(StatusMessage.OFFLINE, nowMs);
    }

    private CompletableFuture<Void> publishStatus(String state, long nowMs) {
        // 狀態訊息 retain=true，平台重啟後訂閱就能立刻拿到每台裝置的最後狀態
        return publish(statusTopic, codec.status(deviceId, state, nowMs), true)
                .thenRun(metrics::statusSent);
    }

    private CompletableFuture<Void> publish(String topic, byte[] payload, boolean retain) {
        return connection.publish(topic, payload, retain)
                .handle((result, error) -> {
                    if (error != null || (result != null && result.getError().isPresent())) {
                        metrics.publishFailed();
                    }
                    return null;
                });
    }

    /**
     * 推進間歇斷線的狀態機，回傳這一輪是否該回報。
     * 斷線走 DISCONNECT_WITH_WILL_MESSAGE，平台收到的才是 broker 代發的遺言，跟真的掉線一樣。
     */
    private boolean flapStep(long nowMs) {
        if (flapOnline) {
            if (nowMs - lastFlapChangeMs < faults.flapUpMs()) {
                return true;
            }
            flapOnline = false;
            lastFlapChangeMs = nowMs;
            metrics.deviceDisconnected();
            connection.dropWithWill();
            return false;
        }
        if (reconnecting || nowMs - lastFlapChangeMs < faults.flapDownMs()) {
            return false;
        }
        reconnecting = true;
        lastFlapChangeMs = nowMs;
        // 不擋住分片執行緒：重連要等 broker 回 CONNACK，同一分片的其他裝置不該跟著等
        connection.connect().whenComplete((ignored, error) -> {
            reconnecting = false;
            if (error == null) {
                flapOnline = true;
                metrics.deviceConnected();
                announceOnline(System.currentTimeMillis());
            }
        });
        return false;
    }

    /** 依指標的小數位數截斷。多餘的位數本來就沒有意義，而且乘上每秒五萬則就不是小數目。 */
    private static double round(double value, int decimals) {
        if (decimals <= 0) {
            return Math.rint(value);
        }
        double factor = POW10[Math.min(decimals, POW10.length - 1)];
        return Math.rint(value * factor) / factor;
    }

    @Override
    public String toString() {
        return "SimulatedDevice[" + deviceId + " " + model.modelCode() + " " + fault + "]";
    }
}
