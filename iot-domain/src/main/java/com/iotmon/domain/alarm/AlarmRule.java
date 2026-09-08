package com.iotmon.domain.alarm;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 告警規則。
 *
 * <p>規則可以綁在機型（一次套用到該機型的所有裝置）或單一裝置（處理例外），
 * 兩者擇一。允許同時綁定的話，就要回答「機型規則與裝置規則衝突時聽誰的」，
 * 而任何答案都會讓設定畫面難以解釋。
 */
public final class AlarmRule {

    private final Long id;
    private final String name;
    private final ModelCode modelCode;
    private final DeviceId deviceId;
    private final MetricKey metric;
    private final Comparison comparison;
    private final double threshold;
    private final Double secondaryValue;
    private final AlarmSeverity severity;
    private final Duration sustainedFor;
    private final boolean enabled;

    private AlarmRule(Long id, String name, ModelCode modelCode, DeviceId deviceId,
                      MetricKey metric, Comparison comparison, double threshold,
                      Double secondaryValue, AlarmSeverity severity,
                      Duration sustainedFor, boolean enabled) {
        this.id = id;
        this.name = name;
        this.modelCode = modelCode;
        this.deviceId = deviceId;
        this.metric = metric;
        this.comparison = comparison;
        this.threshold = threshold;
        this.secondaryValue = secondaryValue;
        this.severity = severity;
        this.sustainedFor = sustainedFor;
        this.enabled = enabled;
    }

    /** 套用到整個機型的規則 */
    public static AlarmRule forModel(Long id, String name, ModelCode modelCode, MetricKey metric,
                                     Comparison comparison, double threshold, Double secondaryValue,
                                     AlarmSeverity severity, Duration sustainedFor, boolean enabled) {
        Objects.requireNonNull(modelCode, "機型規則必須指定機型");
        return validated(new AlarmRule(id, name, modelCode, null, metric, comparison,
                threshold, secondaryValue, severity, sustainedFor, enabled));
    }

    /** 只套用到單一裝置的規則 */
    public static AlarmRule forDevice(Long id, String name, DeviceId deviceId, MetricKey metric,
                                      Comparison comparison, double threshold, Double secondaryValue,
                                      AlarmSeverity severity, Duration sustainedFor, boolean enabled) {
        Objects.requireNonNull(deviceId, "裝置規則必須指定裝置");
        return validated(new AlarmRule(id, name, null, deviceId, metric, comparison,
                threshold, secondaryValue, severity, sustainedFor, enabled));
    }

    private static AlarmRule validated(AlarmRule rule) {
        if (rule.name == null || rule.name.isBlank()) {
            throw new IllegalArgumentException("告警規則必須有名稱");
        }
        Objects.requireNonNull(rule.metric, "告警規則必須指定指標");
        Objects.requireNonNull(rule.comparison, "告警規則必須指定比較方式");
        Objects.requireNonNull(rule.severity, "告警規則必須指定嚴重度");
        if (!Double.isFinite(rule.threshold)) {
            throw new IllegalArgumentException("門檻必須是有限數：" + rule.name);
        }
        if (rule.comparison.requiresSecondaryValue() && rule.secondaryValue == null) {
            throw new IllegalArgumentException("OUT_OF_RANGE 需要上下界兩個值：" + rule.name);
        }
        if (rule.sustainedFor == null || rule.sustainedFor.isNegative()) {
            throw new IllegalArgumentException("持續時間不可為負：" + rule.name);
        }
        return rule;
    }

    /**
     * 這條規則的門檻，在該指標的定義下是否有意義。
     *
     * <p>門檻設在量程外的規則永遠不會觸發，而且不會有任何錯誤訊息——
     * 它會安靜地存在於設定清單裡，直到事故發生才有人發現它從來沒響過。
     * 因此建立規則時就要擋下來，不要等到執行期。
     */
    public boolean isMeaningfulFor(MetricDefinition definition) {
        if (definition == null || !definition.key().equals(metric)) {
            return false;
        }
        if (!definition.isMeaningfulThreshold(threshold)) {
            return false;
        }
        return secondaryValue == null || definition.isMeaningfulThreshold(secondaryValue);
    }

    /** 這筆讀數是否違反本規則。不考慮持續時間——那是告警引擎的狀態機要處理的。 */
    public boolean isBreachedBy(double value) {
        return enabled && comparison.breached(value, threshold, secondaryValue);
    }

    /** 規則是否適用於這台裝置 */
    public boolean appliesTo(DeviceId candidateDevice, ModelCode candidateModel) {
        if (deviceId != null) {
            return deviceId.equals(candidateDevice);
        }
        return modelCode != null && modelCode.equals(candidateModel);
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Optional<ModelCode> modelCode() {
        return Optional.ofNullable(modelCode);
    }

    public Optional<DeviceId> deviceId() {
        return Optional.ofNullable(deviceId);
    }

    public MetricKey metric() {
        return metric;
    }

    public Comparison comparison() {
        return comparison;
    }

    public double threshold() {
        return threshold;
    }

    public Optional<Double> secondaryValue() {
        return Optional.ofNullable(secondaryValue);
    }

    public AlarmSeverity severity() {
        return severity;
    }

    /** 必須持續違反多久才真的告警。用來抑制門檻附近的抖動。 */
    public Duration sustainedFor() {
        return sustainedFor;
    }

    public boolean enabled() {
        return enabled;
    }
}
