package com.iotmon.domain.alarm;

import com.iotmon.domain.model.MetricKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 裝置規則以「指標」為單位整個取代機型規則（ADR-0006）。
 * 不取較嚴格者：冷藏區的溫濕度計想把門檻從 60 放寬到 75，機型的 60 仍會響，覆寫就沒有意義。
 */
public final class AlarmRulePrecedence {

    private AlarmRulePrecedence() {
    }

    public static List<AlarmRule> resolve(List<AlarmRule> modelRules, List<AlarmRule> deviceRules) {
        if (deviceRules == null || deviceRules.isEmpty()) {
            return modelRules == null ? List.of() : modelRules;
        }
        Set<MetricKey> overridden = new HashSet<>();
        for (AlarmRule rule : deviceRules) {
            overridden.add(rule.metric());
        }
        List<AlarmRule> result = new ArrayList<>(deviceRules);
        if (modelRules != null) {
            for (AlarmRule rule : modelRules) {
                if (!overridden.contains(rule.metric())) {
                    result.add(rule);
                }
            }
        }
        return List.copyOf(result);
    }
}
