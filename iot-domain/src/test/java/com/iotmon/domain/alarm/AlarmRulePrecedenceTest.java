package com.iotmon.domain.alarm;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmRulePrecedenceTest {

    private static final ModelCode TH = ModelCode.of("TH-100");
    private static final DeviceId DEV = DeviceId.of("DEV-000001");

    private static AlarmRule model(long id, String metric, double threshold) {
        return AlarmRule.forModel(id, "model-" + metric, TH, MetricKey.of(metric), Comparison.GT,
                threshold, null, AlarmSeverity.WARNING, Duration.ZERO, true);
    }

    private static AlarmRule device(long id, String metric, double threshold) {
        return AlarmRule.forDevice(id, "device-" + metric, DEV, MetricKey.of(metric), Comparison.GT,
                threshold, null, AlarmSeverity.CRITICAL, Duration.ZERO, true);
    }

    @Test
    @DisplayName("裝置規則以指標為單位整個取代機型規則，其他指標的機型規則保留")
    void deviceRuleReplacesModelRulesOnSameMetric() {
        List<AlarmRule> model = List.of(model(1, "temperature", 60), model(2, "temperature", 70), model(3, "humidity", 90));
        List<AlarmRule> device = List.of(device(10, "temperature", 75));

        List<AlarmRule> resolved = AlarmRulePrecedence.resolve(model, device);

        assertThat(resolved).extracting(AlarmRule::id).containsExactlyInAnyOrder(10L, 3L);
        // 冷藏區的溫濕度計把門檻放寬到 75，機型的 60 與 70 對它都不再生效
        assertThat(resolved.stream().filter(r -> r.metric().equals(MetricKey.of("temperature"))))
                .singleElement().satisfies(r -> assertThat(r.threshold()).isEqualTo(75));
    }

    @Test
    @DisplayName("沒有裝置規則時就是機型規則本身")
    void noDeviceRulesMeansModelRules() {
        List<AlarmRule> model = List.of(model(1, "temperature", 60));
        assertThat(AlarmRulePrecedence.resolve(model, List.of())).containsExactlyElementsOf(model);
        assertThat(AlarmRulePrecedence.resolve(model, null)).containsExactlyElementsOf(model);
    }

    @Test
    @DisplayName("只有裝置規則、機型沒設任何規則時，裝置規則照常生效")
    void deviceRulesWithoutModelRules() {
        List<AlarmRule> device = List.of(device(10, "temperature", 75));
        assertThat(AlarmRulePrecedence.resolve(List.of(), device)).containsExactlyElementsOf(device);
        assertThat(AlarmRulePrecedence.resolve(null, device)).containsExactlyElementsOf(device);
    }
}
