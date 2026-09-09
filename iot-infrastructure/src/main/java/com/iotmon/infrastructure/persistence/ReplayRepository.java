package com.iotmon.infrastructure.persistence;

import com.iotmon.application.query.Resolution;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.time.temporal.TemporalUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 歷史回放：「某個時間點，這個機櫃長什麼樣」。
 *
 * <p>這是「兩年資料一秒內」在畫面上的證明。關鍵跟歷史查詢一樣：不掃原始資料。
 * 時間點落在哪一層就讀哪一層的**單一桶**——兩年前的某一小時只讀 40 台裝置 × 幾個指標的 40 多列，
 * 跟查十分鐘前一樣快。
 *
 * <ul>
 *   <li>15 分鐘內：連續聚合可能還沒把最新資料算進去，直接對原始表做一分鐘的聚合</li>
 *   <li>30 天內：分鐘層</li>
 *   <li>更久：小時層，保留五年</li>
 * </ul>
 */
@Repository
public class ReplayRepository {

    /** 連續聚合的更新有延遲，這段時間內的資料要看原始表 */
    private static final Duration AGGREGATE_LAG = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final MetricDictionary metrics;

    public ReplayRepository(JdbcTemplate jdbc, MetricDictionary metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public record Reading(double avg, double min, double max, long count) {
    }

    public record ActiveAlarm(long alarmId, String metric, String severity, String message, Double value,
                              Instant firedAt, Instant resolvedAt) {
    }

    public record DeviceSnapshot(String deviceId, String name, String modelCode, Short slot,
                                 Map<String, Reading> readings, List<ActiveAlarm> alarms) {
        public boolean hadData() {
            return !readings.isEmpty();
        }
    }

    public record Snapshot(Instant at, Resolution resolution, Instant bucketStart, Instant bucketEnd,
                           List<DeviceSnapshot> devices) {
    }

    public record TimelineAlarm(long alarmId, String deviceId, String metric, String severity, String message,
                                Instant firedAt, Instant resolvedAt) {
    }

    private record DeviceRow(int id, String deviceId, String serialNo, String modelCode, Short slot) {
    }

    /** @return empty 代表機櫃不存在 */
    public Optional<Snapshot> snapshot(String cabinetCode, Instant at) {
        List<DeviceRow> devices = devicesIn(cabinetCode);
        if (devices.isEmpty() && !cabinetExists(cabinetCode)) {
            return Optional.empty();
        }

        Duration age = Duration.between(at, Instant.now());
        Resolution resolution = age.compareTo(AGGREGATE_LAG) < 0 ? Resolution.RAW
                : age.compareTo(Duration.ofDays(30)) <= 0 ? Resolution.ONE_MINUTE
                : Resolution.ONE_HOUR;
        TemporalUnit unit = resolution == Resolution.ONE_HOUR ? ChronoUnit.HOURS : ChronoUnit.MINUTES;
        Instant bucketStart = at.truncatedTo(unit);
        Instant bucketEnd = bucketStart.plus(1, unit);

        Map<Integer, Map<String, Reading>> readings = devices.isEmpty() ? Map.of()
                : readingsAt(resolution, devices, bucketStart, bucketEnd);
        Map<Integer, List<ActiveAlarm>> alarms = devices.isEmpty() ? Map.of() : alarmsActiveAt(devices, at);

        List<DeviceSnapshot> result = new ArrayList<>(devices.size());
        for (DeviceRow d : devices) {
            result.add(new DeviceSnapshot(d.deviceId(), d.serialNo(), d.modelCode(), d.slot(),
                    readings.getOrDefault(d.id(), Map.of()), alarms.getOrDefault(d.id(), List.of())));
        }
        return Optional.of(new Snapshot(at, resolution, bucketStart, bucketEnd, Collections.unmodifiableList(result)));
    }

    /** 這段期間內機櫃裡「曾經在響」的告警，畫在時間軸上當標記 */
    public List<TimelineAlarm> timeline(String cabinetCode, Instant from, Instant to, int limit) {
        return jdbc.query("""
                SELECT a.id, d.device_id, r.metric_key, a.severity, r.name, a.fired_at, a.resolved_at
                FROM alarm a
                JOIN device d ON d.id = a.device_id
                JOIN cabinet c ON c.id = d.cabinet_id
                JOIN alarm_rule r ON r.id = a.rule_id
                WHERE c.code = ? AND a.fired_at < ? AND (a.resolved_at IS NULL OR a.resolved_at > ?)
                ORDER BY a.fired_at
                LIMIT ?
                """, (rs, i) -> {
            Timestamp resolved = rs.getTimestamp("resolved_at");
            return new TimelineAlarm(rs.getLong("id"), rs.getString("device_id"), rs.getString("metric_key"),
                    rs.getString("severity"), rs.getString("name"), rs.getTimestamp("fired_at").toInstant(),
                    resolved == null ? null : resolved.toInstant());
        }, cabinetCode, Timestamp.from(to), Timestamp.from(from), limit);
    }

    // ── 內部 ─────────────────────────────────────────────────────────────

    private List<DeviceRow> devicesIn(String cabinetCode) {
        return jdbc.query("""
                SELECT d.id, d.device_id, d.serial_no, d.model_code, d.slot_no
                FROM device d JOIN cabinet c ON c.id = d.cabinet_id
                WHERE c.code = ?
                ORDER BY d.slot_no
                """, (rs, i) -> {
            short slotRaw = rs.getShort("slot_no");
            return new DeviceRow(rs.getInt("id"), rs.getString("device_id"), rs.getString("serial_no"),
                    rs.getString("model_code"), rs.wasNull() ? null : slotRaw);
        }, cabinetCode);
    }

    private boolean cabinetExists(String cabinetCode) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM cabinet WHERE code = ?", Integer.class, cabinetCode);
        return n != null && n > 0;
    }

    private Map<Integer, Map<String, Reading>> readingsAt(Resolution resolution, List<DeviceRow> devices,
                                                          Instant bucketStart, Instant bucketEnd) {
        String placeholders = String.join(",", Collections.nCopies(devices.size(), "?"));
        List<Object> args = new ArrayList<>();
        for (DeviceRow d : devices) {
            args.add(d.id());
        }
        String sql;
        if (resolution == Resolution.RAW) {
            // 原始表現場聚合：只碰一個機櫃、一分鐘，是幾千列的事
            sql = """
                    SELECT device_id, metric_id, avg(value) AS avg_value, min(value) AS min_value,
                           max(value) AS max_value, count(*) AS sample_count
                    FROM telemetry
                    WHERE device_id IN (%s) AND time >= ? AND time < ?
                    GROUP BY device_id, metric_id
                    """.formatted(placeholders);
            args.add(Timestamp.from(bucketStart));
            args.add(Timestamp.from(bucketEnd));
        } else {
            // 資料表名稱來自列舉，不是呼叫端輸入
            sql = """
                    SELECT device_id, metric_id, avg_value, min_value, max_value, sample_count
                    FROM %s
                    WHERE device_id IN (%s) AND bucket = ?
                    """.formatted(resolution.tableName(), placeholders);
            args.add(Timestamp.from(bucketStart));
        }

        Map<Integer, Map<String, Reading>> byDevice = new HashMap<>();
        jdbc.query(sql, (java.sql.ResultSet rs) -> {
            String key = metrics.keyOf(rs.getShort("metric_id"));
            if (key == null) {
                return;
            }
            byDevice.computeIfAbsent(rs.getInt("device_id"), k -> new LinkedHashMap<>())
                    .put(key, new Reading(rs.getDouble("avg_value"), rs.getDouble("min_value"),
                            rs.getDouble("max_value"), rs.getLong("sample_count")));
        }, args.toArray());
        return byDevice;
    }

    private Map<Integer, List<ActiveAlarm>> alarmsActiveAt(List<DeviceRow> devices, Instant at) {
        String placeholders = String.join(",", Collections.nCopies(devices.size(), "?"));
        List<Object> args = new ArrayList<>();
        for (DeviceRow d : devices) {
            args.add(d.id());
        }
        args.add(Timestamp.from(at));
        args.add(Timestamp.from(at));
        Map<Integer, List<ActiveAlarm>> byDevice = new HashMap<>();
        jdbc.query("""
                SELECT a.id, a.device_id, r.metric_key, a.severity, r.name, a.trigger_value, a.fired_at, a.resolved_at
                FROM alarm a JOIN alarm_rule r ON r.id = a.rule_id
                WHERE a.device_id IN (%s) AND a.fired_at <= ? AND (a.resolved_at IS NULL OR a.resolved_at > ?)
                ORDER BY a.fired_at
                """.formatted(placeholders), (java.sql.ResultSet rs) -> {
            double triggerRaw = rs.getDouble("trigger_value");
            Double trigger = rs.wasNull() ? null : triggerRaw;
            Timestamp resolved = rs.getTimestamp("resolved_at");
            byDevice.computeIfAbsent(rs.getInt("device_id"), k -> new ArrayList<>())
                    .add(new ActiveAlarm(rs.getLong("id"), rs.getString("metric_key"), rs.getString("severity"),
                            rs.getString("name"), trigger, rs.getTimestamp("fired_at").toInstant(),
                            resolved == null ? null : resolved.toInstant()));
        }, args.toArray());
        return byDevice;
    }
}
