package com.iotmon.domain.alarm;

import com.iotmon.domain.model.MetricKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 機型規則與裝置規則衝突時聽誰的。
 *
 * <p>規則只有一條：<b>裝置規則以「指標」為單位整個取代機型規則。</b>
 * 某台裝置在 temperature 上有自己的規則，該機型所有 temperature 規則對它就全部失效；
 * 其他指標的機型規則照常套用。
 *
 * <p>不選「兩邊都套用、取較嚴格者」——那樣一台放在冷藏區的溫濕度計，
 * 想把高溫門檻從 60 放寬到 75，機型的 60 仍然會響，覆寫就沒有意義了。
 * 也不做「部分欄位覆寫」——那要回答「只覆寫嚴重度但沿用門檻」這類問題，
 * 設定畫面會變得無法解釋。
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
