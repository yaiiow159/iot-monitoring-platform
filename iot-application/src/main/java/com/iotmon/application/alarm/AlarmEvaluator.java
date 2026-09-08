package com.iotmon.application.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.device.DeviceId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警狀態機：把「這一刻違反了門檻」轉成「這是不是一則該發的告警」。
 *
 * <p>兩者不是同一件事。讀數在門檻附近抖動時，每一筆都「違反了門檻」，
 * 但只有持續違反才是事故。少了這個狀態機，一次溫度擦邊就會刷出上百則告警，
 * 而真正的事故會被淹沒在裡面——那就是告警疲勞的成因。
 *
 * <p>狀態放在記憶體而不是資料庫：每秒五萬點，每一點都查一次資料庫的狀態
 * 會讓告警引擎變成整條路徑最慢的一環。代價是重啟後持續時間要重新累積，
 * 這是可接受的——重啟後第一個 sustainedFor 週期內不告警，比拖慢整條路徑好。
 */
public class AlarmEvaluator {

    /** key 是「裝置＋規則」，因為同一台裝置可能同時套用多條規則 */
    private final Map<BreachKey, Instant> breachStartedAt = new ConcurrentHashMap<>();

    /**
     * 判定這筆讀數會造成什麼結果。
     *
     * @param now 當下時間。由呼叫端傳入而不是自己取，測試才有辦法控制時間推進。
     */
    public Decision evaluate(DeviceId deviceId, AlarmRule rule, double value, Instant now) {
        BreachKey key = new BreachKey(deviceId, rule.id());

        if (!rule.isBreachedBy(value)) {
            // 回到正常值。之前若在累積中就取消，若已經告警則要解除。
            Instant removed = breachStartedAt.remove(key);
            return removed != null ? Decision.RESOLVE : Decision.NOTHING;
        }

        Duration sustainedFor = rule.sustainedFor();
        if (sustainedFor.isZero()) {
            // 不要求持續時間：第一次違反就告警，但只在「之前不是違反狀態」時才發，
            // 否則每一筆讀數都會產生一則新告警。
            Instant previous = breachStartedAt.putIfAbsent(key, now);
            return previous == null ? Decision.FIRE : Decision.NOTHING;
        }

        Instant startedAt = breachStartedAt.computeIfAbsent(key, k -> now);
        boolean longEnough = Duration.between(startedAt, now).compareTo(sustainedFor) >= 0;
        if (!longEnough) {
            return Decision.NOTHING;
        }

        // 已達持續時間。用一個哨兵值標記「已經發過了」，避免之後每一筆都重發。
        boolean alreadyFired = startedAt.equals(FIRED_MARKER);
        if (alreadyFired) {
            return Decision.NOTHING;
        }
        breachStartedAt.put(key, FIRED_MARKER);
        return Decision.FIRE;
    }

    /**
     * 裝置離線時清掉它的累積狀態。
     *
     * <p>不清的話，一台離線半小時的裝置重新上線時，
     * 會因為「違反狀態持續了半小時」而立刻告警——但那半小時根本沒有讀數。
     */
    public void forget(DeviceId deviceId) {
        breachStartedAt.keySet().removeIf(key -> key.deviceId().equals(deviceId));
    }

    public int trackedBreaches() {
        return breachStartedAt.size();
    }

    /** 用一個不可能的時間點當哨兵，代表「這組違反已經發過告警了」 */
    private static final Instant FIRED_MARKER = Instant.EPOCH;

    public enum Decision {
        /** 什麼都不做 */
        NOTHING,
        /** 產生一則新告警 */
        FIRE,
        /** 解除既有告警 */
        RESOLVE
    }

    private record BreachKey(DeviceId deviceId, Long ruleId) {
    }

    /** 供測試檢查內部狀態 */
    Optional<Instant> breachStartOf(DeviceId deviceId, Long ruleId) {
        return Optional.ofNullable(breachStartedAt.get(new BreachKey(deviceId, ruleId)));
    }
}
