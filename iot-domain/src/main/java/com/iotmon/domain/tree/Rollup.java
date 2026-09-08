package com.iotmon.domain.tree;

import com.iotmon.domain.alarm.AlarmSeverity;

import java.util.Optional;

/**
 * 一個節點「自己加整個子樹」的告警彙總。
 *
 * <p>這是「父元素感知子節點告警」的資料形狀。它一定是**從子樹重新算出來的值**，
 * 不是靠事件遞增遞減維護的計數器。計數器版本有一個經典的錯：
 * 解除一則告警時把父節點減一，但兄弟節點還在響，父節點卻已經變綠——
 * 而且這個錯不會拋例外，只會在儀表板上安靜地顯示一個錯誤的顏色。
 *
 * @param severity 子樹內未解除告警的最高嚴重度；沒有告警時為 empty
 * @param firing   子樹內未解除告警的數量
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
