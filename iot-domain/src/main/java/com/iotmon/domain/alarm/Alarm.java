package com.iotmon.domain.alarm;

import com.iotmon.domain.device.DeviceId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一則告警。從觸發到解除是同一筆紀錄，不是兩筆事件。
 *
 * <p>把「觸發」與「解除」記在同一列，是為了讓「目前有幾則未解除的告警」
 * 成為一個索引查得到的問題（`WHERE state = 'FIRING'`）。
 * 拆成事件流的話，這個查詢要對每台裝置各自找最後一筆事件再判斷狀態，
 * 而那是儀表板每秒都要問一次的問題。
 */
public final class Alarm {

    private final Long id;
    private final DeviceId deviceId;
    private final long ruleId;
    private final AlarmSeverity severity;
    private final Instant firedAt;
    private final Double triggerValue;

    private AlarmState state;
    private Instant resolvedAt;

    private Alarm(Long id, DeviceId deviceId, long ruleId, AlarmSeverity severity,
                  Instant firedAt, Double triggerValue, AlarmState state, Instant resolvedAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.ruleId = ruleId;
        this.severity = severity;
        this.firedAt = firedAt;
        this.triggerValue = triggerValue;
        this.state = state;
        this.resolvedAt = resolvedAt;
    }

    public static Alarm fire(Long id, DeviceId deviceId, long ruleId, AlarmSeverity severity,
                             Instant firedAt, Double triggerValue) {
        Objects.requireNonNull(deviceId, "告警必須指定裝置");
        Objects.requireNonNull(severity, "告警必須指定嚴重度");
        Objects.requireNonNull(firedAt, "告警必須指定觸發時間");
        return new Alarm(id, deviceId, ruleId, severity, firedAt, triggerValue,
                AlarmState.FIRING, null);
    }

    public static Alarm rehydrate(Long id, DeviceId deviceId, long ruleId, AlarmSeverity severity,
                                  Instant firedAt, Double triggerValue,
                                  AlarmState state, Instant resolvedAt) {
        return new Alarm(id, deviceId, ruleId, severity, firedAt, triggerValue, state, resolvedAt);
    }

    /**
     * 解除告警。
     *
     * <p>重複解除不拋例外只回 false：解除的觸發來源有好幾個
     * （讀數回到正常、裝置離線、人工關閉），它們有機會同時發生。
     * 對已解除的告警再解除一次不是錯誤，只是沒事做。
     *
     * @return 這次呼叫是否真的改變了狀態
     */
    public boolean resolve(Instant at) {
        Objects.requireNonNull(at, "解除時間不可為 null");
        if (state == AlarmState.RESOLVED) {
            return false;
        }
        if (at.isBefore(firedAt)) {
            throw new IllegalArgumentException("解除時間早於觸發時間：" + deviceId + " rule=" + ruleId);
        }
        state = AlarmState.RESOLVED;
        resolvedAt = at;
        return true;
    }

    public boolean isFiring() {
        return state == AlarmState.FIRING;
    }

    public Long id() {
        return id;
    }

    public DeviceId deviceId() {
        return deviceId;
    }

    public long ruleId() {
        return ruleId;
    }

    public AlarmSeverity severity() {
        return severity;
    }

    public AlarmState state() {
        return state;
    }

    public Instant firedAt() {
        return firedAt;
    }

    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    public Optional<Double> triggerValue() {
        return Optional.ofNullable(triggerValue);
    }
}
