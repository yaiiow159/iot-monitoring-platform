package com.iotmon.simulator.catalog;

import com.iotmon.domain.model.DeviceModel;

import java.util.List;

/**
 * 機型 ＋ 它每個指標的產生方式。
 *
 * <p>機型定義本身重用 domain 的 {@link DeviceModel}，模擬器只補上 domain 不該知道的東西——
 * 怎麼產生像真的數值。這樣新增一種硬體時，指標契約仍然只有一個來源。
 */
public record SimulatedModel(DeviceModel model, List<MetricProfile> profiles) {

    public SimulatedModel {
        if (model == null || profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("機型與指標產生設定不可為空");
        }
        profiles = List.copyOf(profiles);
        for (MetricProfile profile : profiles) {
            // 產生了機型不回報的指標，下游會當成未知指標丟掉，而且不會有人發現
            if (!model.reports(profile.definition().key())) {
                throw new IllegalArgumentException(
                        "機型未宣告此指標：" + model.code() + " / " + profile.definition().key());
            }
        }
        if (profiles.size() != model.metrics().size()) {
            throw new IllegalArgumentException("機型的每個指標都要有產生設定：" + model.code());
        }
    }

    public String modelCode() {
        return model.code().value();
    }
}
