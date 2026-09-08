package com.iotmon.domain.device;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 裝置連線狀態。
 *
 * <p>合法的轉移集中宣告在這裡。散在各個 service 裡各寫一次的話，
 * 遲早會有人放行一個非法轉移，而且不會有人發現——狀態機的錯誤通常不會拋例外，
 * 只會讓儀表板顯示一個說不通的狀態。
 */
public enum DeviceStatus {

    /** 尚未收到任何連線事件。新註冊的裝置從這裡開始。 */
    UNKNOWN,

    /** broker 確認已連線，且遙測在預期間隔內。 */
    ONLINE,

    /** 連線還在，但遙測遲到或讀數超出量程——通常是訊號不穩或感測器故障。 */
    DEGRADED,

    /** 收到 LWT 遺言訊息，或心跳逾時補網判定離線。 */
    OFFLINE;

    private static final Map<DeviceStatus, Set<DeviceStatus>> ALLOWED_TRANSITIONS = Map.of(
            UNKNOWN, EnumSet.of(ONLINE, OFFLINE),
            ONLINE, EnumSet.of(DEGRADED, OFFLINE),
            DEGRADED, EnumSet.of(ONLINE, OFFLINE),
            // 離線的裝置只能重新連線。OFFLINE → DEGRADED 沒有意義：
            // 沒有連線就沒有「品質不佳的連線」可言。
            OFFLINE, EnumSet.of(ONLINE)
    );

    public boolean canTransitionTo(DeviceStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, EnumSet.noneOf(DeviceStatus.class)).contains(target);
    }

    /** 是否算「連得上」。告警與可用率統計都以此為準，避免各處自己列舉。 */
    public boolean isConnected() {
        return this == ONLINE || this == DEGRADED;
    }
}
