package com.iotmon.infrastructure.mqtt;

/**
 * 裝置狀態訊息的線上格式。
 *
 * <p>來源有兩個：裝置正常關機時自己發的，以及 broker 代發的 LWT 遺言。
 * 對平台而言兩者沒有差別，都是同一則訊息——這正是用 LWT 的好處，
 * 不必為「正常斷線」與「異常斷線」寫兩套處理（見 ADR-0004）。
 *
 * @param state ONLINE 或 OFFLINE
 */
public record StatusEnvelope(String deviceId, String state, long ts) {
}
