package com.iotmon.domain.model;

import com.iotmon.domain.shared.Guard;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 機型：決定一台裝置「會回報哪些指標」。
 *
 * <p>機型是整個平台的資料契約來源。遙測進來時要靠它判斷這筆讀數合不合法、
 * 告警規則要靠它判斷門檻設得有沒有意義、前端要靠它決定畫哪幾條線。
 * 把這件事集中在機型上，新增一種硬體時就只有一個地方要改。
 */
public final class DeviceModel {

    private final ModelCode code;
    private final String manufacturer;
    private final String displayName;
    private final Map<MetricKey, MetricDefinition> metrics;

    private DeviceModel(ModelCode code, String manufacturer, String displayName,
                        Map<MetricKey, MetricDefinition> metrics) {
        this.code = code;
        this.manufacturer = manufacturer;
        this.displayName = displayName;
        this.metrics = metrics;
    }

    public static DeviceModel of(ModelCode code, String manufacturer, String displayName,
                                 List<MetricDefinition> metrics) {
        Guard.notNull(code, "機型代號");
        Guard.notBlank(manufacturer, "機型 " + code + " 的製造商");
        Guard.notBlank(displayName, "機型 " + code + " 的名稱");
        if (metrics == null || metrics.isEmpty()) {
            // 不回報任何指標的機型無法被監控，建立它只會在下游產生一個永遠沒有資料的裝置
            throw new IllegalArgumentException("機型至少要定義一個指標：" + code);
        }

        Map<MetricKey, MetricDefinition> indexed = new LinkedHashMap<>();
        for (MetricDefinition metric : metrics) {
            MetricDefinition previous = indexed.put(metric.key(), metric);
            if (previous != null) {
                throw new IllegalArgumentException("機型內指標代號重複：" + code + " / " + metric.key());
            }
        }
        return new DeviceModel(code, manufacturer, displayName, Collections.unmodifiableMap(indexed));
    }

    public ModelCode code() {
        return code;
    }

    public String manufacturer() {
        return manufacturer;
    }

    public String displayName() {
        return displayName;
    }

    /** 依宣告順序回傳，讓前端的圖表排列與設定畫面一致 */
    public List<MetricDefinition> metrics() {
        return List.copyOf(metrics.values());
    }

    public Optional<MetricDefinition> metric(MetricKey key) {
        return Optional.ofNullable(metrics.get(key));
    }

    public boolean reports(MetricKey key) {
        return metrics.containsKey(key);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DeviceModel model && code.equals(model.code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }

    @Override
    public String toString() {
        return "DeviceModel[" + code + " " + displayName + "]";
    }
}
