package com.iotmon.infrastructure.alarm;

/**
 * 告警引擎發到 Kafka {@code iot.alarm} 的事件。
 *
 * <p>只帶識別資訊，不帶祖先鏈：祖先鏈由推播端在送出前查（一次 ltree 查詢），
 * 事件本身保持與樹結構無關，樹重整時歷史事件不會變成錯的。
 *
 * @param state FIRING 或 RESOLVED
 */
public record AlarmEvent(long alarmId, String deviceId, long ruleId, String severity,
                         String state, Double triggerValue, long ts) {
}
