package com.iotmon.simulator.fault;

import com.iotmon.simulator.catalog.MetricProfile;
import com.iotmon.simulator.config.SimulatorProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * 決定「哪些裝置故障」以及「故障時讀數長什麼樣」。
 *
 * <p>五種故障的行為集中在這個類別，是為了讓「模擬器到底製造了什麼異常」只有一個地方要讀；
 * 散在裝置邏輯裡的話，驗證告警規則時會分不清是規則沒寫對還是模擬器沒送出那個值。
 */
@Component
public class FaultInjector {

    private final SimulatorProperties.Fault config;
    private final FaultType[] assignments;

    public FaultInjector(SimulatorProperties properties) {
        this.config = properties.fault();
        this.assignments = assign(properties.deviceCount(), properties.seed(), config);
    }

    /**
     * 先洗牌再切片指派故障，而不是用索引取模。
     * 取模會讓故障與機型（同樣依索引決定）綁死，結果例如所有斷線的都是溫濕度感測器。
     */
    private static FaultType[] assign(int deviceCount, long seed, SimulatorProperties.Fault config) {
        FaultType[] result = new FaultType[deviceCount];
        Arrays.fill(result, FaultType.NONE);

        int[] order = new int[deviceCount];
        for (int i = 0; i < deviceCount; i++) {
            order[i] = i;
        }
        Random shuffle = new Random(seed);
        for (int i = deviceCount - 1; i > 0; i--) {
            int j = shuffle.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }

        int cursor = 0;
        cursor = take(result, order, cursor, count(deviceCount, config.offlineFlappingRatio()), FaultType.OFFLINE_FLAPPING);
        cursor = take(result, order, cursor, count(deviceCount, config.outOfRangeRatio()), FaultType.OUT_OF_RANGE);
        cursor = take(result, order, cursor, count(deviceCount, config.clockDriftRatio()), FaultType.CLOCK_DRIFT);
        cursor = take(result, order, cursor, count(deviceCount, config.silentRatio()), FaultType.SILENT);
        take(result, order, cursor, count(deviceCount, config.spikeRatio()), FaultType.SPIKE);
        return result;
    }

    private static int count(int deviceCount, double ratio) {
        return (int) Math.round(deviceCount * ratio);
    }

    private static int take(FaultType[] result, int[] order, int cursor, int howMany, FaultType type) {
        int end = Math.min(order.length, cursor + howMany);
        for (int i = cursor; i < end; i++) {
            result[order[i]] = type;
        }
        return end;
    }

    public FaultType faultFor(int deviceIndex) {
        return assignments[deviceIndex];
    }

    public FaultType[] assignments() {
        return assignments.clone();
    }

    /** 這台裝置固定落後多少毫秒。同一台每次回報的落後量一致，才像時鐘走偏而不是網路抖動。 */
    public long clockDriftMillis(FaultType fault) {
        return fault == FaultType.CLOCK_DRIFT ? config.clockDriftSeconds() * 1000L : 0L;
    }

    public long flapUpMs() {
        return config.flapUpMs();
    }

    public long flapDownMs() {
        return config.flapDownMs();
    }

    /** SILENT 裝置先正常回報一段時間再閉嘴，平台才看得到「有資料變成沒資料」的轉折。 */
    public boolean shouldStayQuiet(FaultType fault, long uptimeMs) {
        return fault == FaultType.SILENT && uptimeMs >= config.silenceAfterMs();
    }

    /**
     * 依故障模式改寫讀數。
     *
     * @param targeted 這個指標是否為該裝置壞掉的那一路。感測器故障通常只壞一路，
     *                 整台的讀數全部超標比較像斷電，不是同一種事故。
     */
    public double distort(FaultType fault, MetricProfile profile, double value,
                          boolean targeted, SplittableRandom random) {
        return switch (fault) {
            case OUT_OF_RANGE -> targeted ? outOfRange(profile, random) : value;
            case SPIKE -> random.nextDouble() < config.spikeProbability() ? spike(profile, random) : value;
            default -> value;
        };
    }

    /** 推到量程上緣之外 5%~20%，確保平台判得出「這不是高溫，是感測器壞了」。 */
    private static double outOfRange(MetricProfile profile, SplittableRandom random) {
        double overshoot = profile.rangeSpan() * (0.05 + random.nextDouble() * 0.15);
        return profile.definition().maxValue() + overshoot;
    }

    /** 尖峰仍留在量程內，否則會被當成感測異常而不是「真的飆高了」，告警規則就驗不到。 */
    private static double spike(MetricProfile profile, SplittableRandom random) {
        double headroom = profile.definition().maxValue() - profile.bandHigh();
        return profile.bandHigh() + headroom * (0.6 + random.nextDouble() * 0.4);
    }
}
