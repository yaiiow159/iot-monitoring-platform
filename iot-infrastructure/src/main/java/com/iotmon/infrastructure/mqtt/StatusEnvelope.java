package com.iotmon.infrastructure.mqtt;

/**
 * 裝置狀態訊息。裝置自己發的與 broker 代發的 LWT 遺言是同一則格式，不必為正常／異常斷線寫兩套（ADR-0004）。
 * @param state ONLINE 或 OFFLINE
 */
public record StatusEnvelope(String deviceId, String state, long ts) {
}
