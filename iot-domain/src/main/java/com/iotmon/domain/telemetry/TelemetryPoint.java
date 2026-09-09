package com.iotmon.domain.telemetry;

import com.iotmon.domain.shared.Guard;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;

import java.time.Instant;

/**
 * 一筆遙測讀數，每秒被建立數萬次，所以是扁平的 record：任何額外欄位都直接反映在 GC 壓力上。
 * @param timestamp 裝置端的取樣時間，不是伺服器收到的時間
 */
public record TelemetryPoint(DeviceId deviceId, MetricKey metric, double value, Instant timestamp) {

    public TelemetryPoint {
        Guard.notNull(deviceId, "遙測的裝置識別碼");
        Guard.notNull(metric, "遙測的指標代號");
        Guard.notNull(timestamp, "遙測的時間戳");
        Guard.finite(value, "遙測讀數 " + deviceId + "/" + metric);
    }
}
