package com.iotmon.infrastructure.mqtt;

import java.util.Map;

/**
 * MQTT 遙測訊息的線上格式，同時也是進 Kafka 的格式。
 *
 * <p>一則訊息帶多個指標而不是一點一則：每秒五萬點若逐點成訊息，
 * 光是 MQTT 與 Kafka 的標頭就會吃掉可觀的頻寬與 CPU。
 *
 * <p>刻意不在這裡轉成領域物件。橋接端的工作是「盡快把訊息從 MQTT 搬到 Kafka」，
 * 中間多做一次物件轉換就是在最不能塞車的地方增加工作。
 * 驗證與轉換留給消費端，它可以慢慢做。
 *
 * @param deviceId 裝置識別碼
 * @param ts       裝置端取樣時間的毫秒 epoch，不是伺服器接收時間
 * @param metrics  指標代號 → 讀數
 */
public record TelemetryEnvelope(String deviceId, long ts, Map<String, Double> metrics) {
}
