package com.iotmon.infrastructure.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmRulePrecedence;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 告警規則的讀取，附一份會過期的快取。
 *
 * <p>規則每秒被查五萬次、每天被改幾次。不快取等於把資料庫當成規則引擎用；
 * 永不過期的快取則會讓「我改了門檻怎麼沒生效」變成客服問題。
 * 30 秒是折衷：改完最多半分鐘生效，而資料庫每 30 秒只多一次查詢。
 *
 * <p>機型規則與裝置規則一起載入；衝突時的優先序在 {@link AlarmRulePrecedence}，這裡只負責取資料。
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

    private static AlarmRule mapRule(ResultSet rs) throws SQLException {
        double secondaryRaw = rs.getDouble("secondary_value");
        Double secondary = rs.wasNull() ? null : secondaryRaw;
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
