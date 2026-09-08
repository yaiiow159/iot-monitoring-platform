package com.iotmon.domain.tree;

import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.device.DeviceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 監控樹的三條規則：嵌套合法性、順序總序、告警上浮。
 *
 * <p>這三件事在上一個專案都出過問題，而且都是「不會拋例外、只會顯示錯」的那種。
 */
class MonitoringTreeTest {

    private static final TreeNode EQUIP = TreeNode.equipment(1L, "1 號變電站", 1000);

    private static TreeNode sensor(long id, TreeNode parent, String name, long order) {
        return TreeNode.attachUnder(parent, id, NodeKind.SENSOR, name, null, order);
    }

    private static TreeNode device(long id, TreeNode parent, String name, long order) {
        return TreeNode.attachUnder(parent, id, NodeKind.DEVICE, name,
                DeviceId.of("DEV-" + String.format("%06d", id)), order);
    }

    @Nested
    @DisplayName("嵌套規則")
    class Nesting {

        @Test
        @DisplayName("Sensor 可以無限自我嵌套")
        void sensorsNestIndefinitely() {
            TreeNode s1 = sensor(2, EQUIP, "L1", 1000);
            TreeNode s2 = sensor(3, s1, "L2", 1000);
            TreeNode s3 = sensor(4, s2, "L3", 1000);
            TreeNode s4 = sensor(5, s3, "L4", 1000);
            assertEquals(4L, s4.parentId());
        }

        @Test
        @DisplayName("Device 是葉節點：底下不能掛 Sensor 也不能掛 Device")
        void deviceIsALeaf() {
            TreeNode s = sensor(2, EQUIP, "S", 1000);
            TreeNode d = device(3, s, "D", 1000);
            assertThrows(IllegalArgumentException.class, () -> sensor(4, d, "X", 1000));
            assertThrows(IllegalArgumentException.class, () -> device(4, d, "X", 1000));
        }

        @Test
        @DisplayName("Device 只能掛在 Sensor 底下，不能直接掛在 Equipment 上")
        void deviceOnlyUnderSensor() {
            assertThrows(IllegalArgumentException.class, () -> device(2, EQUIP, "D", 1000));
        }

        @Test
        @DisplayName("Equipment 不能掛在任何東西底下")
        void equipmentIsAlwaysRoot() {
            TreeNode s = sensor(2, EQUIP, "S", 1000);
            assertThrows(IllegalArgumentException.class,
                    () -> TreeNode.attachUnder(s, 3L, NodeKind.EQUIPMENT, "E2", null, 1000));
            assertThrows(IllegalArgumentException.class,
                    () -> new TreeNode(3L, NodeKind.EQUIPMENT, "E2", 1L, null, 1000));
        }

        @Test
        @DisplayName("只有 DEVICE 節點綁定裝置，且必須綁定——否則同一台裝置會從兩個節點各上浮一次")
        void onlyDeviceNodesBindDevices() {
            TreeNode s = sensor(2, EQUIP, "S", 1000);
            assertThrows(IllegalArgumentException.class,
                    () -> TreeNode.attachUnder(s, 3L, NodeKind.DEVICE, "D", null, 1000));
            assertThrows(IllegalArgumentException.class,
                    () -> TreeNode.attachUnder(EQUIP, 3L, NodeKind.SENSOR, "S2",
                            DeviceId.of("DEV-000001"), 1000));
        }
    }

    @Nested
    @DisplayName("順序是總序，不是碰運氣")
    class Ordering {

        @Test
        @DisplayName("子節點依 sortOrder 排，輸入順序不影響輸出")
        void childrenSortedBySortOrder() {
            TreeNode a = sensor(2, EQUIP, "A", 3000);
            TreeNode b = sensor(3, EQUIP, "B", 1000);
            TreeNode c = sensor(4, EQUIP, "C", 2000);

            List<TreeNode> shuffled = new ArrayList<>(List.of(EQUIP, a, b, c));
            Collections.shuffle(shuffled);

            List<MonitoringTree.Branch> tree = MonitoringTree.build(shuffled, Map.of());
            List<String> names = tree.get(0).children().stream().map(br -> br.node().name()).toList();
            assertEquals(List.of("B", "C", "A"), names);
        }

        @Test
        @DisplayName("sortOrder 相同時以 id 決定，順序仍然唯一")
        void tieBrokenById() {
            TreeNode x = sensor(9, EQUIP, "X", 1000);
            TreeNode y = sensor(7, EQUIP, "Y", 1000);
            TreeNode z = sensor(8, EQUIP, "Z", 1000);

            for (int i = 0; i < 20; i++) {
                List<TreeNode> shuffled = new ArrayList<>(List.of(EQUIP, x, y, z));
                Collections.shuffle(shuffled);
                List<String> names = MonitoringTree.build(shuffled, Map.of())
                        .get(0).children().stream().map(br -> br.node().name()).toList();
                assertEquals(List.of("Y", "Z", "X"), names, "第 " + i + " 次洗牌後順序改變了");
            }
        }

        @Test
        @DisplayName("根節點之間同樣排序")
        void rootsAreSortedToo() {
            TreeNode e2 = TreeNode.equipment(2L, "2 號", 500);
            List<MonitoringTree.Branch> tree = MonitoringTree.build(List.of(EQUIP, e2), Map.of());
            assertEquals("2 號", tree.get(0).node().name());
        }
    }

    @Nested
    @DisplayName("告警上浮：父元素感知直到最上層")
    class AlarmRollup {

        // Equipment ─ S1 ─ S2 ─ D1(告警)
        //           └ S3 ─ D2
        private final TreeNode s1 = sensor(2, EQUIP, "S1", 1000);
        private final TreeNode s2 = sensor(3, s1, "S2", 1000);
        private final TreeNode d1 = device(4, s2, "D1", 1000);
        private final TreeNode s3 = sensor(5, EQUIP, "S3", 2000);
        private final TreeNode d2 = device(6, s3, "D2", 1000);
        private final List<TreeNode> all = List.of(EQUIP, s1, s2, d1, s3, d2);

        @Test
        @DisplayName("葉節點的告警一路上浮到 Equipment，每一層都看得到")
        void leafAlarmPropagatesToRoot() {
            Map<Long, Rollup> own = Map.of(4L, Rollup.of(AlarmSeverity.CRITICAL, 2));
            MonitoringTree.Branch root = MonitoringTree.build(all, own).get(0);

            assertEquals(AlarmSeverity.CRITICAL, root.worstSeverity());
            assertEquals(2, root.rollup().firing());

            MonitoringTree.Branch bs1 = root.children().get(0);
            MonitoringTree.Branch bs2 = bs1.children().get(0);
            assertEquals(AlarmSeverity.CRITICAL, bs1.worstSeverity());
            assertEquals(AlarmSeverity.CRITICAL, bs2.worstSeverity());
        }

        @Test
        @DisplayName("不在告警路徑上的兄弟分支保持乾淨")
        void unrelatedBranchStaysClear() {
            Map<Long, Rollup> own = Map.of(4L, Rollup.of(AlarmSeverity.WARNING, 1));
            MonitoringTree.Branch root = MonitoringTree.build(all, own).get(0);
            MonitoringTree.Branch bs3 = root.children().get(1);
            assertFalse(bs3.hasAlarm());
        }

        @Test
        @DisplayName("父節點顯示的是子樹裡最嚴重的那一則，數量是總和")
        void parentShowsWorstSeverityAndTotalCount() {
            Map<Long, Rollup> own = Map.of(
                    4L, Rollup.of(AlarmSeverity.WARNING, 3),
                    6L, Rollup.of(AlarmSeverity.CRITICAL, 1));
            MonitoringTree.Branch root = MonitoringTree.build(all, own).get(0);
            assertEquals(AlarmSeverity.CRITICAL, root.worstSeverity());
            assertEquals(4, root.rollup().firing());
        }

        @Test
        @DisplayName("經典 bug：解除一則後兄弟還在響，父節點不能變綠")
        void resolvingOneAlarmDoesNotClearParentWhileSiblingStillFires() {
            Map<Long, Rollup> before = Map.of(
                    4L, Rollup.of(AlarmSeverity.CRITICAL, 1),
                    6L, Rollup.of(AlarmSeverity.WARNING, 1));
            assertEquals(2, MonitoringTree.build(all, before).get(0).rollup().firing());

            // D1 的告警解除了，D2 還在
            Map<Long, Rollup> after = Map.of(6L, Rollup.of(AlarmSeverity.WARNING, 1));
            MonitoringTree.Branch root = MonitoringTree.build(all, after).get(0);
            assertTrue(root.hasAlarm(), "兄弟節點還在響，父節點不可以變綠");
            assertEquals(AlarmSeverity.WARNING, root.worstSeverity());
            assertEquals(1, root.rollup().firing());
            // 而 S1 那條路徑確實已經乾淨
            assertFalse(root.children().get(0).hasAlarm());
        }

        @Test
        @DisplayName("Sensor 自己身上的告警也會上浮，不只葉節點")
        void sensorOwnAlarmAlsoPropagates() {
            Map<Long, Rollup> own = Map.of(3L, Rollup.of(AlarmSeverity.INFO, 1));
            MonitoringTree.Branch root = MonitoringTree.build(all, own).get(0);
            assertEquals(AlarmSeverity.INFO, root.worstSeverity());
        }

        @Test
        @DisplayName("Rollup 不允許自相矛盾的狀態")
        void rollupRejectsInconsistentState() {
            assertThrows(IllegalArgumentException.class, () -> Rollup.of(AlarmSeverity.CRITICAL, 0));
            assertThrows(IllegalArgumentException.class, () -> Rollup.of(null, 3));
            assertThrows(IllegalArgumentException.class, () -> Rollup.of(null, -1));
        }
    }
}
