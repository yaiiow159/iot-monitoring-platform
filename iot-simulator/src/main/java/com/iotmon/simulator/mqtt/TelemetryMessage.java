package com.iotmon.simulator.mqtt;

import java.util.Map;

/**
 * 遙測批次訊息，欄位順序與名稱對齊 docs/contracts.md。
 *
 * @param ts 裝置端取樣時間的毫秒 epoch，不是送出時間——平台用它跟接收時間相減算端到端延遲
 */
public record TelemetryMessage(String deviceId, long ts, Map<String, Double> metrics) {
}
