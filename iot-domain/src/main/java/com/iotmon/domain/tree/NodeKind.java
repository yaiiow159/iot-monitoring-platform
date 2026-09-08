package com.iotmon.domain.tree;

/**
 * 監控樹的節點種類。
 *
 * <p>嵌套規則寫在列舉本身：Equipment 只能在根、Sensor 可以無限自我嵌套、
 * Device 是葉節點。把規則放在這裡而不是散在建立節點的各個地方，
 * 新增一種節點時編譯器會強迫補上它的位置，而不是讓某個 switch 走進 default。
 */
public enum NodeKind {

    /** 根節點。不能有父節點，也不能掛在任何東西底下。 */
    EQUIPMENT {
        @Override
        public boolean canBeChildOf(NodeKind parent) {
            return false;
        }
    },

    /** 可無限自我嵌套。父節點只能是 Equipment 或 Sensor。 */
    SENSOR {
        @Override
        public boolean canBeChildOf(NodeKind parent) {
            return parent == EQUIPMENT || parent == SENSOR;
        }
    },

    /** 葉節點。只能掛在 Sensor 底下，底下不能再有任何東西。 */
    DEVICE {
        @Override
        public boolean canBeChildOf(NodeKind parent) {
            return parent == SENSOR;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }
    };

    /** 這種節點可不可以掛在指定種類的父節點底下 */
    public abstract boolean canBeChildOf(NodeKind parent);

    /** 是否為根節點種類（不需要、也不允許有父節點） */
    public boolean isRoot() {
        return this == EQUIPMENT;
    }

    /** 是否為葉節點種類（不允許有子節點） */
    public boolean isLeaf() {
        return false;
    }
}
