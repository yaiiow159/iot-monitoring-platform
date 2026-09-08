package com.iotmon.domain.alarm;

/**
 * 告警嚴重度。
 *
 * <p>只有三級是刻意的。分得越細，值班的人越難決定「現在到底要不要起床」——
 * 五級以上的分類最後都會退化成「CRITICAL 以外全部忽略」。
 */
public enum AlarmSeverity {

    /** 值得記錄，但不需要有人立刻處理 */
    INFO(0),

    /** 需要在上班時間內處理，放著會惡化 */
    WARNING(1),

    /** 需要立刻處理，代表服務已經受影響或即將受影響 */
    CRITICAL(2);

    private final int rank;

    AlarmSeverity(int rank) {
        this.rank = rank;
    }

    public boolean isAtLeast(AlarmSeverity other) {
        return this.rank >= other.rank;
    }

    public boolean isMoreSevereThan(AlarmSeverity other) {
        return this.rank > other.rank;
    }
}
