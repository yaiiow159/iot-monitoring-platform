package com.iotmon.domain.tree;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.shared.Guard;

import java.util.Comparator;
import java.util.Optional;

/**
 * 監控樹的一個節點。
 *
 * <p>兩件事在這裡被強制：嵌套規則（透過 {@link #attachUnder}）與**同層順序的總序**。
 * 順序用 {@code (sortOrder, id)} 比較，{@code id} 是決定性的平手判斷——
 * 兩個節點 sortOrder 相同時，順序仍然是唯一的。
 * 「前端顯示是無序的」這個問題，根源幾乎都是後端只給了一個可能相同的排序鍵。
 *
 * @param id        資料庫主鍵；尚未持久化時為 null
 * @param kind      節點種類
 * @param name      顯示名稱
 * @param parentId  父節點；根節點為 null
 * @param deviceId  只有 DEVICE 節點有值，指向會發遙測的那台裝置
 * @param sortOrder 同層排序鍵。刻意留間隔（見 {@link #ORDER_GAP}），插入中間不必重新編號
 */
public record TreeNode(Long id, NodeKind kind, String name, Long parentId,
                       DeviceId deviceId, long sortOrder) {

    /** 相鄰兄弟的 sortOrder 間隔。插入兩者之間時取中點，用完 log2(1000) ≈ 10 次才需要重排。 */
    public static final long ORDER_GAP = 1000;

    /**
     * 同層順序的總序。任何回傳子節點的地方都必須用它，
     * 這樣同一棵樹不論從哪條路徑查出來，順序都完全一致。
     */
    public static final Comparator<TreeNode> SIBLING_ORDER =
            Comparator.comparingLong(TreeNode::sortOrder)
                    .thenComparing(TreeNode::id, Comparator.nullsLast(Comparator.naturalOrder()));

    public TreeNode {
        Guard.notNull(kind, "節點種類");
        Guard.notBlank(name, "節點名稱");
        if (kind.isRoot() && parentId != null) {
            throw new IllegalArgumentException("Equipment 是根節點，不能有父節點：" + name);
        }
        if (!kind.isRoot() && parentId == null) {
            throw new IllegalArgumentException(kind + " 必須掛在某個父節點底下：" + name);
        }
        if ((kind == NodeKind.DEVICE) != (deviceId != null)) {
            // Device 節點的意義就是「這裡掛著一台會回報的裝置」；反之其他節點不該綁裝置，
            // 否則同一台裝置的告警會從兩個節點各上浮一次
            throw new IllegalArgumentException("只有 DEVICE 節點綁定裝置，且必須綁定：" + name);
        }
    }

    /** 建立根節點 */
    public static TreeNode equipment(Long id, String name, long sortOrder) {
        return new TreeNode(id, NodeKind.EQUIPMENT, name, null, null, sortOrder);
    }

    /**
     * 在指定父節點底下建立子節點，並套用嵌套規則。
     *
     * <p>規則檢查放在這裡而不是資料庫約束：資料庫只知道 parent_id 不為空，
     * 分不出「Device 底下掛了 Sensor」這種結構錯誤。
     */
    public static TreeNode attachUnder(TreeNode parent, Long id, NodeKind kind, String name,
                                       DeviceId deviceId, long sortOrder) {
        Guard.notNull(parent, "父節點");
        Guard.notNull(kind, "節點種類");
        if (parent.kind().isLeaf()) {
            throw new IllegalArgumentException(
                    "Device 是葉節點，底下不能再掛東西：" + parent.name() + " ← " + name);
        }
        if (!kind.canBeChildOf(parent.kind())) {
            throw new IllegalArgumentException(
                    kind + " 不能掛在 " + parent.kind() + " 底下：" + parent.name() + " ← " + name);
        }
        return new TreeNode(id, kind, name, parent.id(), deviceId, sortOrder);
    }

    public Optional<DeviceId> device() {
        return Optional.ofNullable(deviceId);
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
