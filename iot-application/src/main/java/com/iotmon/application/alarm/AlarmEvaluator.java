package com.iotmon.application.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.device.DeviceId;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警狀態機：「這一刻違反了門檻」不等於「該發一則告警」，讀數在門檻附近抖動時只有持續違反才是事故。
 * 狀態放記憶體不放資料庫：每秒數萬點各查一次資料庫會讓引擎變成整條路徑最慢的一環；
 * 代價是重啟後持續時間重新累積，可接受。
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
            // 回到正常值。累積中就回復的沒發過告警，資料庫裡沒有東西可以解除——
            // 回 RESOLVE 只會讓每次抖動都多打一次資料庫。
            Instant removed = breachStartedAt.remove(key);
            return hasFired(removed, rule) ? Decision.RESOLVE : Decision.NOTHING;
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

    /** 不要求持續時間的規則一進 map 就已經發過告警；其餘看哨兵值。 */
    private static boolean hasFired(Instant removed, AlarmRule rule) {
        return removed != null && (rule.sustainedFor().isZero() || removed.equals(FIRED_MARKER));
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

    /**
     * 只清掉指定規則的累積狀態。
     *
     * <p>裝置規則以指標為單位取代機型規則（ADR-0006），整台清會把其他指標的計時一併歸零。
     */
    public void forget(DeviceId deviceId, Collection<Long> ruleIds) {
        for (Long ruleId : ruleIds) {
            breachStartedAt.remove(new BreachKey(deviceId, ruleId));
        }
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
