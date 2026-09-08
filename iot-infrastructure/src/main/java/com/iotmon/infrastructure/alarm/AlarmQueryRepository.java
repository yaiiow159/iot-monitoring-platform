package com.iotmon.infrastructure.alarm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警列表：一次 join 裝置與規則，湊出前端需要的整列。
 *
 * <p>前端的 Alarm 型別要 deviceName、cabinetId、metric、threshold、message，
 * 這些散在三張表。與其讓前端拿到 id 再各打一次，不如在資料庫一次 join 回來——
 * 這正是 ADR-0001 選單一資料庫的理由之一。
 */
@Repository
public class AlarmQueryRepository {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;

    public AlarmQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long alarmId, String deviceId, String deviceName, String cabinetCode, String metric,
                      String severity, String state, String message, Double value, double threshold,
                      Instant firedAt, Instant resolvedAt) {
    }

    /**
     * @param state    FIRING／RESOLVED；null 為全部
     * @param deviceId 限定裝置；null 為全部
     * @param limit    上限，超過 MAX_LIMIT 會被壓到 MAX_LIMIT
     */
    public List<Row> list(String state, String deviceId, Integer limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, d.device_id, d.serial_no, c.code AS cabinet_code, r.metric_key, a.severity, a.state,
                       r.name AS rule_name, a.trigger_value, r.threshold, a.fired_at, a.resolved_at
                FROM alarm a
                JOIN device d ON d.id = a.device_id
                LEFT JOIN cabinet c ON c.id = d.cabinet_id
                JOIN alarm_rule r ON r.id = a.rule_id
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (state != null && !state.isBlank()) {
            sql.append(" AND a.state = ?");
            args.add(state.trim().toUpperCase());
        }
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND d.device_id = ?");
            args.add(deviceId.trim());
        }
        // 未解除的排前面，再依觸發時間新到舊：值班的人要先看到還在響的
        sql.append(" ORDER BY (a.state = 'FIRING') DESC, a.fired_at DESC LIMIT ?");
        int effective = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        args.add(effective);

        return jdbc.query(sql.toString(), (rs, i) -> {
            double triggerRaw = rs.getDouble("trigger_value");
            Double trigger = rs.wasNull() ? null : triggerRaw;
            Timestamp resolved = rs.getTimestamp("resolved_at");
            return new Row(rs.getLong("id"), rs.getString("device_id"), rs.getString("serial_no"),
                    rs.getString("cabinet_code"),
                    rs.getString("metric_key"), rs.getString("severity"), rs.getString("state"),
                    rs.getString("rule_name"), trigger, rs.getDouble("threshold"),
                    rs.getTimestamp("fired_at").toInstant(), resolved == null ? null : resolved.toInstant());
        }, args.toArray());
    }
}
