package com.iotmon.domain.telemetry;

import com.iotmon.domain.shared.Guard;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;

import java.time.Instant;

/**
 * 一筆遙測讀數。這是整個平台流量最大的物件——每秒五萬個。
 *
 * <p>刻意做成扁平的 record 而不是帶行為的實體：它每秒被建立五萬次，
 * 任何額外的欄位或間接層都會直接反映在 GC 壓力上。
 * 驗證與判讀交給 {@link TelemetryValidator}，這裡只負責承載資料。
 *
 * @param deviceId  來源裝置
 * @param metric    指標代號
 * @param value     讀數
 * @param timestamp 裝置端的取樣時間，不是伺服器收到的時間
 */
public record TelemetryPoint(DeviceId deviceId, MetricKey metric, double value, Instant timestamp) {

    public TelemetryPoint {
        Guard.notNull(deviceId, "遙測的裝置識別碼");
        Guard.notNull(metric, "遙測的指標代號");
        Guard.notNull(timestamp, "遙測的時間戳");
        // NaN 與無限大會一路汙染聚合結果：avg 會變成 NaN，而且 SQL 端不會報錯。
        // 在入口擋掉，比事後從連續聚合裡把它挖出來容易得多。
        Guard.finite(value, "遙測讀數 " + deviceId + "/" + metric);
    }
}
