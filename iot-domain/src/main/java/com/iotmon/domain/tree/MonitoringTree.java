package com.iotmon.domain.tree;

import com.iotmon.domain.alarm.AlarmSeverity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把扁平的節點清單組成有序、帶彙總的樹。
 *
 * <p>純領域計算，沒有任何 I/O：給它節點清單與「每個節點自己身上的告警」，
 * 它回傳排好序、每個節點都算好 rollup 的樹。這讓「上浮是否正確」可以用
 * 幾行測試在毫秒內驗證，而不需要起資料庫。
 *
 * <p>子節點的順序在這裡定案（{@link TreeNode#SIBLING_ORDER}），
 * 之後任何一層都不該再排序——重排一次就是多一個可能排錯的地方。
 */
public final class MonitoringTree {

    private MonitoringTree() {
    }

    /**
     * @param nodes      整棵樹（或子樹）的所有節點，順序不拘
     * @param ownAlarms  每個節點**自己身上**的未解除告警；key 為節點 id。
     *                   不在 map 裡的節點視為沒有告警。
     * @return 根節點清單（已排序），每個節點含已排序的子節點與 rollup
     */
    public static List<Branch> build(Collection<TreeNode> nodes, Map<Long, Rollup> ownAlarms) {
        Map<Long, List<TreeNode>> childrenByParent = new HashMap<>();
        List<TreeNode> roots = new ArrayList<>();

        for (TreeNode node : nodes) {
            if (node.isRoot()) {
                roots.add(node);
            } else {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
            }
        }

        roots.sort(TreeNode.SIBLING_ORDER);
        List<Branch> result = new ArrayList<>(roots.size());
        for (TreeNode root : roots) {
            result.add(assemble(root, childrenByParent, ownAlarms));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 後序遞迴：先算完所有子樹的 rollup，再合併進自己。
     * 深度就是 Sensor 的嵌套層數，實務上是個位數，遞迴不會有堆疊問題。
     */
    private static Branch assemble(TreeNode node, Map<Long, List<TreeNode>> childrenByParent,
                                   Map<Long, Rollup> ownAlarms) {
        List<TreeNode> children = childrenByParent.getOrDefault(node.id(), List.of());
        children = new ArrayList<>(children);
        children.sort(TreeNode.SIBLING_ORDER);

        Rollup rollup = ownAlarms.getOrDefault(node.id(), Rollup.NONE);
        List<Branch> branches = new ArrayList<>(children.size());
        for (TreeNode child : children) {
            Branch branch = assemble(child, childrenByParent, ownAlarms);
            branches.add(branch);
            rollup = rollup.merge(branch.rollup());
        }
        return new Branch(node, rollup, Collections.unmodifiableList(branches));
    }

    /** 組好的一個分支：節點、它的彙總、已排序的子分支 */
    public record Branch(TreeNode node, Rollup rollup, List<Branch> children) {

        public boolean hasAlarm() {
            return !rollup.isClear();
        }

        public AlarmSeverity worstSeverity() {
            return rollup.severity().orElse(null);
        }
    }
}
