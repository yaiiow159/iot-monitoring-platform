package com.iotmon.domain.tree;

import com.iotmon.domain.alarm.AlarmSeverity;

import java.util.Optional;

/**
 * 一個節點「自己加整個子樹」的告警彙總。一定是從子樹重算的值，不是遞增遞減的計數器：
 * 計數器版本解除一則時把父節點減一，兄弟還在響父節點卻變綠，而且不會拋例外。
 * @param severity 子樹內未解除告警的最高嚴重度；沒有時為 empty
 * @param firing 子樹內未解除告警的數量
 */
public record Rollup(Optional<AlarmSeverity> severity, int firing) {

    public static final Rollup NONE = new Rollup(Optional.empty(), 0);

    public Rollup {
        if (severity == null) {
            severity = Optional.empty();
        }
        if (firing < 0) {
            throw new IllegalArgumentException("告警數不可為負：" + firing);
        }
        if (severity.isPresent() != (firing > 0)) {
            // 「有嚴重度但零則」或「有幾則但沒嚴重度」都是內部矛盾，代表計算過程出錯了
            throw new IllegalArgumentException("嚴重度與告警數不一致：" + severity + " / " + firing);
        }
    }

    public static Rollup of(AlarmSeverity severity, int firing) {
        return new Rollup(Optional.ofNullable(severity), firing);
    }

    /** 合併子節點的彙總進來：嚴重度取最大、數量相加 */
    public Rollup merge(Rollup child) {
        if (child.firing == 0) {
            return this;
        }
        if (this.firing == 0) {
            return child;
        }
        AlarmSeverity worst = this.severity.get().isAtLeast(child.severity.get())
                ? this.severity.get()
                : child.severity.get();
        return new Rollup(Optional.of(worst), this.firing + child.firing);
    }

    public boolean isClear() {
        return firing == 0;
    }
}
