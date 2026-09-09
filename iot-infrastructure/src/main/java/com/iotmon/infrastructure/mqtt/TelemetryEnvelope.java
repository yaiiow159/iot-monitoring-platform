package com.iotmon.infrastructure.mqtt;

import java.util.Map;

/**
 * MQTT 遙測訊息的線上格式，也是進 Kafka 的格式。一則帶多個指標省下標頭成本；
 * 刻意不在橋接端轉成領域物件，驗證與轉換留給可以慢慢做的消費端。
 * @param ts 裝置端取樣時間的毫秒 epoch
 */
public record TelemetryEnvelope(String deviceId, long ts, Map<String, Double> metrics) {
}
