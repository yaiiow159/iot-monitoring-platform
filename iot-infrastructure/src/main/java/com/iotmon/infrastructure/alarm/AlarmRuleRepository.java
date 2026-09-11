package com.iotmon.infrastructure.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmRulePrecedence;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.infrastructure.persistence.Rows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 告警規則的讀取，附 30 秒 TTL 的快取：規則每秒被查數萬次、每天被改幾次，不快取等於把資料庫當規則引擎；
 * 永不過期又會讓「改了門檻怎麼沒生效」變成客服問題。優先序在 {@link AlarmRulePrecedence}。
 */
@Repository
public class AlarmRuleRepository {

    private static final Duration TTL = Duration.ofSeconds(30);

    private static final String SELECT = """
            SELECT r.id, r.name, r.model_code, d.device_id AS device_code, r.metric_key, r.comparison,
                   r.threshold, r.secondary_value, r.severity, r.duration_seconds, r.enabled
            FROM alarm_rule r LEFT JOIN device d ON d.id = r.device_id
            """;

    private final JdbcTemplate jdbc;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public AlarmRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 引擎用：這台裝置實際該套用的規則，裝置規則已依優先序取代同指標的機型規則 */
    public List<AlarmRule> enabledRulesFor(DeviceId deviceId, ModelCode model) {
        Snapshot s = current();
        return AlarmRulePrecedence.resolve(
                s.byModel().getOrDefault(model, List.of()),
                s.byDevice().getOrDefault(deviceId, List.of()));
    }

    /** 這個機型在這個指標上的規則 id。裝置規則接管該指標時，要清掉它們的累積狀態（ADR-0006）。 */
    public List<Long> modelRuleIdsFor(ModelCode model, MetricKey metric) {
        return current().byModel().getOrDefault(model, List.of()).stream()
                .filter(rule -> rule.metric().equals(metric))
                .map(AlarmRule::id)
                .toList();
    }

    /** 設定畫面用：含停用的、含兩種範圍，依 id 排序。 */
    public List<AlarmRule> findAll() {
        List<AlarmRule> all = new ArrayList<>();
        jdbc.query(SELECT + " ORDER BY r.id", (ResultSet rs) -> {
            all.add(mapRule(rs));
        });
        return all;
    }

    /** 寫入後讓快取立刻失效，改完門檻不必等 30 秒。 */
    public AlarmRule insert(AlarmRule rule) {
        String deviceCode = rule.deviceId().map(DeviceId::value).orElse(null);
        Long id = jdbc.queryForObject("""
                INSERT INTO alarm_rule (name, model_code, device_id, metric_key, comparison, threshold,
                                        secondary_value, severity, duration_seconds, enabled)
                VALUES (?, ?, (SELECT id FROM device WHERE device_id = ?), ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                rule.name(), rule.modelCode().map(ModelCode::value).orElse(null), deviceCode,
                rule.metric().value(), rule.comparison().name(), rule.threshold(),
                rule.secondaryValue().orElse(null), rule.severity().name(),
                (int) rule.sustainedFor().toSeconds(), rule.enabled());
        snapshot.set(null);
        return rule.deviceId().isPresent()
                ? AlarmRule.forDevice(id, rule.name(), rule.deviceId().get(), rule.metric(), rule.comparison(),
                rule.threshold(), rule.secondaryValue().orElse(null), rule.severity(), rule.sustainedFor(), rule.enabled())
                : AlarmRule.forModel(id, rule.name(), rule.modelCode().orElseThrow(), rule.metric(), rule.comparison(),
                rule.threshold(), rule.secondaryValue().orElse(null), rule.severity(), rule.sustainedFor(), rule.enabled());
    }

    public Optional<AlarmRule> findById(long id) {
        return jdbc.query(SELECT + " WHERE r.id = ?",
                (ResultSet rs) -> rs.next() ? Optional.of(mapRule(rs)) : Optional.<AlarmRule>empty(), id);
    }

    /**
     * 可改的只有門檻、持續時間、嚴重度、名稱與啟用與否。
     *
     * <p>範圍（機型／裝置）與指標不能改：那等於換了一條規則，卻沿用同一份告警歷史。
     * 要換範圍就停用舊的、新增一條。
     */
    public boolean update(long id, String name, double threshold, Double secondaryValue,
                          AlarmSeverity severity, Duration sustainedFor, boolean enabled) {
        int updated = jdbc.update("""
                UPDATE alarm_rule
                   SET name = ?, threshold = ?, secondary_value = ?, severity = ?,
                       duration_seconds = ?, enabled = ?
                 WHERE id = ?
                """,
                name, threshold, secondaryValue, severity.name(), (int) sustainedFor.toSeconds(), enabled, id);
        snapshot.set(null);
        return updated > 0;
    }

    /** @return 真的刪掉了才是 true */
    public boolean delete(long id) {
        int deleted = jdbc.update("DELETE FROM alarm_rule WHERE id = ?", id);
        snapshot.set(null);
        return deleted > 0;
    }

    private static AlarmRule mapRule(ResultSet rs) throws SQLException {
        Double secondary = Rows.nullableDouble(rs, "secondary_value");
        String modelCode = rs.getString("model_code");
        String deviceCode = rs.getString("device_code");
        long id = rs.getLong("id");
        String name = rs.getString("name");
        MetricKey metric = MetricKey.of(rs.getString("metric_key"));
        Comparison comparison = Comparison.valueOf(rs.getString("comparison"));
        double threshold = rs.getDouble("threshold");
        AlarmSeverity severity = AlarmSeverity.valueOf(rs.getString("severity"));
        Duration sustained = Duration.ofSeconds(rs.getInt("duration_seconds"));
        boolean enabled = rs.getBoolean("enabled");
        return modelCode != null
                ? AlarmRule.forModel(id, name, ModelCode.of(modelCode), metric, comparison, threshold, secondary,
                severity, sustained, enabled)
                : AlarmRule.forDevice(id, name, DeviceId.of(deviceCode), metric, comparison, threshold, secondary,
                severity, sustained, enabled);
    }

    private Snapshot current() {
        Snapshot s = snapshot.get();
        if (s != null && System.nanoTime() - s.loadedAtNanos() < TTL.toNanos()) {
            return s;
        }
        Snapshot fresh = load();
        snapshot.set(fresh);
        return fresh;
    }

    private Snapshot load() {
        Map<ModelCode, List<AlarmRule>> byModel = new HashMap<>();
        Map<DeviceId, List<AlarmRule>> byDevice = new HashMap<>();
        jdbc.query(SELECT + " WHERE r.enabled", (ResultSet rs) -> {
            AlarmRule rule = mapRule(rs);
            rule.modelCode().ifPresent(m -> byModel.computeIfAbsent(m, k -> new ArrayList<>()).add(rule));
            rule.deviceId().ifPresent(d -> byDevice.computeIfAbsent(d, k -> new ArrayList<>()).add(rule));
        });
        byModel.replaceAll((k, v) -> List.copyOf(v));
        byDevice.replaceAll((k, v) -> List.copyOf(v));
        return new Snapshot(Map.copyOf(byModel), Map.copyOf(byDevice), System.nanoTime());
    }

    private record Snapshot(Map<ModelCode, List<AlarmRule>> byModel, Map<DeviceId, List<AlarmRule>> byDevice,
                            long loadedAtNanos) {
    }
}
