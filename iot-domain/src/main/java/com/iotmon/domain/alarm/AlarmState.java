package com.iotmon.domain.alarm;

/** 告警狀態。只有兩種：還在響，或已經解除。 */
public enum AlarmState {
    FIRING,
    RESOLVED
}
