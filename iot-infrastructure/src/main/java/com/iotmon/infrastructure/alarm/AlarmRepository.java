package com.iotmon.infrastructure.alarm;

import com.iotmon.domain.alarm.AlarmSeverity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 告警的寫入。
 *
 * <p>觸發用 {@code ON CONFLICT ... DO NOTHING}，衝突目標正是 V1 建的
 * 部分唯一索引「同一裝置同一規則只能有一則未解除」。撞上代表已經在響，
 * 回傳 empty 讓引擎知道不用再推播——這是防告警洪水的最後一道閘，
 * 就算狀態機重啟後記憶清空，資料庫也不會多出重複的告警。
 */
@Repository
public class AlarmRepository {

    private final JdbcTemplate jdbc;

    public AlarmRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param nodeId 裝置在監控樹上的節點；不在樹上為 null
     * @return 新建的告警 id；已有未解除的同款告警時為 empty
     */
    public Optional<Long> fire(int deviceRowId, long ruleId, AlarmSeverity severity,
                               double triggerValue, Instant at, Long nodeId) {
        return jdbc.query("""
                INSERT INTO alarm (device_id, rule_id, severity, state, trigger_value, fired_at, node_id)
                VALUES (?, ?, ?, 'FIRING', ?, ?, ?)
                ON CONFLICT (device_id, rule_id) WHERE state = 'FIRING' DO NOTHING
                RETURNING id
                """,
                rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.<Long>empty(),
                deviceRowId, ruleId, severity.name(), triggerValue, Timestamp.from(at), nodeId);
    }

    /**
     * 裝置規則接管某個指標時，把該裝置上「機型規則」在同指標的未解除告警一併解除。
     * 不做這件事，那些告警會永遠掛著：規則已不再被評估，沒有人會去解除它。
     */
    public List<Superseded> resolveModelRuleAlarms(int deviceRowId, String metricKey, Instant at) {
        return jdbc.query("""
                UPDATE alarm a SET state = 'RESOLVED', resolved_at = ?
                FROM alarm_rule r
                WHERE r.id = a.rule_id AND a.device_id = ? AND a.state = 'FIRING'
                  AND r.model_code IS NOT NULL AND r.metric_key = ?
                RETURNING a.id, a.rule_id, a.severity
                """,
                (rs, i) -> new Superseded(rs.getLong("id"), rs.getLong("rule_id"), rs.getString("severity")),
                Timestamp.from(at), deviceRowId, metricKey);
    }

    public record Superseded(long alarmId, long ruleId, String severity) {
    }

    /**
     * 認可：條件還成立，但已經有人在處理，畫面不必再閃。state 刻意不動。
     *
     * @return 已經被認可過就回 empty，重複點不會覆蓋第一個認領的人
     */
    public Optional<Acked> acknowledge(long alarmId, String username, Instant at) {
        return jdbc.query("""
                UPDATE alarm SET acknowledged_at = ?, acknowledged_by = ?
                WHERE id = ? AND state = 'FIRING' AND acknowledged_at IS NULL
                RETURNING id, device_id, rule_id, severity
                """,
                rs -> rs.next()
                        ? Optional.of(new Acked(rs.getLong("id"), rs.getInt("device_id"),
                        rs.getLong("rule_id"), rs.getString("severity")))
                        : Optional.<Acked>empty(),
                Timestamp.from(at), username, alarmId);
    }

    public record Acked(long alarmId, int deviceRowId, long ruleId, String severity) {
    }

    /** 人工解除單一告警。@return 不存在或早就解除了就回 empty */
    public Optional<Acked> resolveById(long alarmId, Instant at) {
        return jdbc.query("""
                UPDATE alarm SET state = 'RESOLVED', resolved_at = ?
                WHERE id = ? AND state = 'FIRING'
                RETURNING id, device_id, rule_id, severity
                """,
                rs -> rs.next()
                        ? Optional.of(new Acked(rs.getLong("id"), rs.getInt("device_id"),
                        rs.getLong("rule_id"), rs.getString("severity")))
                        : Optional.<Acked>empty(),
                Timestamp.from(at), alarmId);
    }

    /** 規則被停用或刪除時，把它還在響的告警一起解除——否則會亮著而且再也沒有東西會解除它。 */
    public List<Acked> resolveByRule(long ruleId, Instant at) {
        return jdbc.query("""
                UPDATE alarm SET state = 'RESOLVED', resolved_at = ?
                WHERE rule_id = ? AND state = 'FIRING'
                RETURNING id, device_id, rule_id, severity
                """,
                (rs, i) -> new Acked(rs.getLong("id"), rs.getInt("device_id"),
                        rs.getLong("rule_id"), rs.getString("severity")),
                Timestamp.from(at), ruleId);
    }

    /** 這條規則有沒有留下歷史。有的話不讓刪：外鍵是 CASCADE，刪規則會把告警紀錄一起帶走。 */
    public long countAlarmsOf(long ruleId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM alarm WHERE rule_id = ?", Long.class, ruleId);
        return n == null ? 0 : n;
    }

    /** 人工解除或認可時要知道這是哪一台裝置，才能推播與清狀態 */
    public Optional<String> deviceCodeOf(int deviceRowId) {
        return jdbc.query("SELECT device_id FROM device WHERE id = ?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(), deviceRowId);
    }

    /** @return 被解除的告警 id；沒有未解除的同款告警時為 empty */
    public Optional<Long> resolve(int deviceRowId, long ruleId, Instant at) {
        return jdbc.query("""
                UPDATE alarm SET state = 'RESOLVED', resolved_at = ?
                WHERE device_id = ? AND rule_id = ? AND state = 'FIRING'
                RETURNING id
                """,
                rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.<Long>empty(),
                Timestamp.from(at), deviceRowId, ruleId);
    }
}
