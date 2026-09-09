package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.device.DeviceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 裝置清單、單筆、註冊與儀表板摘要。清單一定有上限：一萬台裝置的表沒有上限的 SELECT 遲早有人打爆。 */
@Repository
public class DeviceRepository {

    public static final int MAX_LIST = 1000;

    private final JdbcTemplate jdbc;

    public DeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 列的扁平投影。domain 的 Device 需要完整機型物件，清單畫面用不到那麼多。 */
    public record Row(String deviceId, String serialNo, String modelCode, String cabinetCode,
                      Short slotNo, DeviceStatus status, Instant lastSeenAt) {
    }

    private static final String SELECT = """
            SELECT d.device_id, d.serial_no, d.model_code, c.code AS cabinet_code, d.slot_no, d.status, d.last_seen_at
            FROM device d LEFT JOIN cabinet c ON c.id = d.cabinet_id
            """;

    public List<Row> list(DeviceStatus status, String cabinetCode, String modelCode) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND d.status = ?");
            args.add(status.name());
        }
        if (cabinetCode != null && !cabinetCode.isBlank()) {
            sql.append(" AND c.code = ?");
            args.add(cabinetCode.trim());
        }
        if (modelCode != null && !modelCode.isBlank()) {
            sql.append(" AND d.model_code = ?");
            args.add(modelCode.trim().toUpperCase());
        }
        // 總序：機櫃內依槽位，其餘依識別碼。前端不排序，順序在這裡定案。
        sql.append(" ORDER BY c.code NULLS LAST, d.slot_no NULLS LAST, d.device_id LIMIT ").append(MAX_LIST);
        return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    public Optional<Row> find(DeviceId deviceId) {
        List<Row> rows = jdbc.query(SELECT + " WHERE d.device_id = ?", this::mapRow, deviceId.value());
        return rows.stream().findFirst();
    }

    public Row insert(String deviceId, String serialNo, String modelCode, Cabinet cabinet, Short slotNo) {
        jdbc.update("""
                INSERT INTO device (device_id, serial_no, model_code, cabinet_id, slot_no, status)
                VALUES (?, ?, ?, ?, ?, 'UNKNOWN')
                """, deviceId, serialNo, modelCode, cabinet == null ? null : cabinet.id(), slotNo);
        return new Row(deviceId, serialNo, modelCode, cabinet == null ? null : cabinet.code(), slotNo,
                DeviceStatus.UNKNOWN, null);
    }

    /** 四種狀態都有 key，沒有裝置的狀態是 0 而不是缺欄位——前端的 Record 型別是這樣定的 */
    public Map<DeviceStatus, Long> countByStatus() {
        Map<DeviceStatus, Long> counts = new EnumMap<>(DeviceStatus.class);
        for (DeviceStatus s : DeviceStatus.values()) {
            counts.put(s, 0L);
        }
        jdbc.query("SELECT status, count(*) AS n FROM device GROUP BY status", rs -> {
            counts.put(DeviceStatus.valueOf(rs.getString("status")), rs.getLong("n")); // 欄位有 CHECK 約束
        });
        return counts;
    }

    public long countFiringAlarms() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM alarm WHERE state = 'FIRING'", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 過去 10 秒的寫入速率（點/秒）。直接數原始表：時間範圍很小，chunk 排除後只碰最新的 chunk。
     * 不用 Micrometer 的計數器差分，那需要一個背景取樣器與額外狀態，而這個查詢每秒才被問一次。
     */
    public double ingestRatePerSecond() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM telemetry WHERE time > now() - INTERVAL '10 seconds'", Long.class);
        return n == null ? 0 : n / 10.0;
    }

    private Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getString("device_id"), rs.getString("serial_no"), rs.getString("model_code"),
                rs.getString("cabinet_code"), Rows.nullableShort(rs, "slot_no"),
                DeviceStatus.valueOf(rs.getString("status")), Rows.instant(rs, "last_seen_at"));
    }
}
