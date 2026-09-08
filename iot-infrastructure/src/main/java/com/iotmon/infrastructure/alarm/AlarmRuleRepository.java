package com.iotmon.infrastructure.alarm;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
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
 * <p>本階段只載入綁機型的規則；綁單一裝置的例外規則留待設定中心完成後接上。
 */
@Repository
public class AlarmRuleRepository {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public AlarmRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AlarmRule> enabledRulesFor(ModelCode model) {
        return current().byModel().getOrDefault(model, List.of());
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
        jdbc.query("""
                SELECT id, name, model_code, metric_key, comparison, threshold, secondary_value,
                       severity, duration_seconds, enabled
                FROM alarm_rule
                WHERE enabled AND model_code IS NOT NULL
                """, rs -> {
            ModelCode model = ModelCode.of(rs.getString("model_code"));
            double secondaryRaw = rs.getDouble("secondary_value");
            Double secondary = rs.wasNull() ? null : secondaryRaw;
            AlarmRule rule = AlarmRule.forModel(
                    rs.getLong("id"),
                    rs.getString("name"),
                    model,
                    MetricKey.of(rs.getString("metric_key")),
                    Comparison.valueOf(rs.getString("comparison")),
                    rs.getDouble("threshold"),
                    secondary,
                    AlarmSeverity.valueOf(rs.getString("severity")),
                    Duration.ofSeconds(rs.getInt("duration_seconds")),
                    rs.getBoolean("enabled"));
            byModel.computeIfAbsent(model, k -> new java.util.ArrayList<>()).add(rule);
        });
        byModel.replaceAll((k, v) -> List.copyOf(v));
        return new Snapshot(Map.copyOf(byModel), System.nanoTime());
    }

    private record Snapshot(Map<ModelCode, List<AlarmRule>> byModel, long loadedAtNanos) {
    }
}
