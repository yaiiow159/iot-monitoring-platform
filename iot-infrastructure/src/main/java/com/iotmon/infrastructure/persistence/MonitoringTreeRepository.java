package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.tree.NodeKind;
import com.iotmon.domain.tree.Rollup;
import com.iotmon.domain.tree.TreeNode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 監控樹的儲存。路徑用 ltree 物化（見 ADR-0005）。
 *
 * <p>這裡只負責「把節點與各節點自己身上的告警撈出來」，**上浮不在 SQL 裡算**——
 * 交給領域層的 {@code MonitoringTree.build()}。規則只留一份、而且是有測試守著的那一份；
 * SQL 版與 Java 版各算一次上浮，兩邊遲早會對同一棵樹給出不同的顏色。
 */
@Repository
public class MonitoringTreeRepository {

    private static final String NODE_COLUMNS =
            "id, kind, name, parent_id, sort_order, "
                    + "(SELECT device_id FROM device d WHERE d.id = n.device_id) AS device_code";

    private static final RowMapper<TreeNode> NODE_MAPPER = (rs, i) -> {
        String deviceCode = rs.getString("device_code");
        // 不用 getObject(col, Long.class)：Postgres 驅動拒絕把 int4 轉成 java.lang.Long，
        // 而 parent_id 是 SERIAL 外鍵、正是 int4。getLong + wasNull 是 JDBC 處理可空整數的慣用法。
        long parentRaw = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parentRaw;
        return new TreeNode(
                rs.getLong("id"),
                NodeKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                parentId,
                deviceCode == null ? null : DeviceId.of(deviceCode),
                rs.getLong("sort_order"));
    };

    private final JdbcTemplate jdbc;

    public MonitoringTreeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 整棵樹。順序在領域層定案，這裡不排序。 */
    public List<TreeNode> findAll() {
        return jdbc.query("SELECT " + NODE_COLUMNS + " FROM monitoring_node n", NODE_MAPPER);
    }

    /** 指定節點與其整個子樹：一個 <@ 運算子，走 GiST 索引，不用遞迴 CTE。 */
    public List<TreeNode> findSubtree(long nodeId) {
        return jdbc.query("""
                SELECT %s FROM monitoring_node n
                WHERE n.path <@ (SELECT path FROM monitoring_node WHERE id = ?)
                """.formatted(NODE_COLUMNS), NODE_MAPPER, nodeId);
    }

    /**
     * 從根到該節點的路徑，由上到下。
     * 「祖先＝路徑的所有前綴」——@> 就是這件事，排序用路徑深度。
     */
    public List<TreeNode> findAncestors(long nodeId) {
        return jdbc.query("""
                SELECT %s FROM monitoring_node n
                WHERE n.path @> (SELECT path FROM monitoring_node WHERE id = ?)
                ORDER BY nlevel(n.path)
                """.formatted(NODE_COLUMNS), NODE_MAPPER, nodeId);
    }

    /**
     * 裝置在樹上的祖先鏈 id，由上到下，含裝置節點本身；裝置不在樹上時為空清單。
     * 推播端每則告警呼叫一次，用來告訴前端「整條路徑上的哪些節點該重抓 rollup」。
     */
    public List<Long> findAncestorIdsOfDevice(int deviceRowId) {
        return jdbc.query("""
                SELECT a.id FROM monitoring_node a
                JOIN monitoring_node leaf ON leaf.device_id = ?
                WHERE a.path @> leaf.path
                ORDER BY nlevel(a.path)
                """, (rs, i) -> rs.getLong("id"), deviceRowId);
    }

    public Optional<TreeNode> findById(long nodeId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + NODE_COLUMNS + " FROM monitoring_node n WHERE id = ?",
                    NODE_MAPPER, nodeId));
        } catch (EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    /**
     * 每個節點**自己身上**的未解除告警彙總（不含子樹）。
     * 嚴重度的比較用領域的 {@link AlarmSeverity#isAtLeast}，不在 SQL 裡另定一套排名。
     */
    public Map<Long, Rollup> firingAlarmsByNode() {
        Map<Long, Rollup> result = new HashMap<>();
        jdbc.query("SELECT node_id, severity FROM alarm WHERE state = 'FIRING' AND node_id IS NOT NULL",
                rs -> {
                    long nodeId = rs.getLong("node_id");
                    Rollup one = Rollup.of(AlarmSeverity.valueOf(rs.getString("severity")), 1);
                    result.merge(nodeId, one, Rollup::merge);
                });
        return result;
    }

    /**
     * 新增節點。path 要包含自己的 id，而 id 由序列產生，
     * 所以先 INSERT 拿到 id 再補 path——兩步放在同一個交易裡，
     * 中途失敗不會留下沒有 path 的節點。
     *
     * @return 含資料庫 id 的節點
     */
    @Transactional
    public TreeNode insert(TreeNode node, Long parentId) {
        Integer deviceRowId = node.device()
                .map(d -> jdbc.queryForObject("SELECT id FROM device WHERE device_id = ?", Integer.class, d.value()))
                .orElse(null);

        long sortOrder = node.sortOrder() > 0 ? node.sortOrder() : nextSortOrder(parentId);

        // path 先放一個佔位值，欄位 NOT NULL；拿到 id 後立刻覆寫
        Long id = jdbc.queryForObject("""
                INSERT INTO monitoring_node (kind, name, parent_id, device_id, sort_order, path)
                VALUES (?, ?, ?, ?, ?, '0'::ltree)
                RETURNING id
                """, Long.class, node.kind().name(), node.name(), parentId, deviceRowId, sortOrder);

        if (parentId == null) {
            jdbc.update("UPDATE monitoring_node SET path = text2ltree(?) WHERE id = ?", String.valueOf(id), id);
        } else {
            jdbc.update("""
                    UPDATE monitoring_node SET path = (SELECT path FROM monitoring_node WHERE id = ?) || text2ltree(?)
                    WHERE id = ?
                    """, parentId, String.valueOf(id), id);
        }
        return new TreeNode(id, node.kind(), node.name(), parentId, node.deviceId(), sortOrder);
    }

    /** 沒指定順序就排在同層最後：現有最大值再加一個間隔。 */
    private long nextSortOrder(Long parentId) {
        Long max = parentId == null
                ? jdbc.queryForObject("SELECT max(sort_order) FROM monitoring_node WHERE parent_id IS NULL", Long.class)
                : jdbc.queryForObject("SELECT max(sort_order) FROM monitoring_node WHERE parent_id = ?", Long.class, parentId);
        return (max == null ? 0 : max) + TreeNode.ORDER_GAP;
    }

    public boolean updateSortOrder(long nodeId, long sortOrder) {
        return jdbc.update("UPDATE monitoring_node SET sort_order = ? WHERE id = ?", sortOrder, nodeId) == 1;
    }
}
