package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.model.MetricKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 指標代號 ↔ 數值 id 的字典。
 *
 * <p>時序表每列存 SMALLINT 而不是 63 位元組的字串，每列省下約 60 位元組。
 * 以每秒五萬筆計算，這個轉換一天就省下 260 GB 的原始寫入量——
 * 「兩年歷史放得下」有一半是靠這件事。
 *
 * <p>與 {@link DeviceIdResolver} 的差別只有一點：指標**會自動建立**。
 * 指標種類由機型定義決定，數量以十計，新指標出現代表有新機型上線，
 * 不是打錯字。裝置則相反，見 DeviceIdResolver 的說明。
 */
@Component
public class MetricDictionary {

    private final JdbcTemplate jdbc;
    private final CachedLookup<String, Short> keyToId;
    private final CachedLookup<Short, String> idToKey;

    public MetricDictionary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 用 ON CONFLICT 而不是「先查後寫」：多個消費端執行緒可能同時遇到同一個新指標，
        // 先查後寫在那個瞬間會有兩條路徑都認為要新增，其中一條會撞上唯一約束。
        this.keyToId = new CachedLookup<>(key -> Optional.ofNullable(jdbc.queryForObject("""
                INSERT INTO metric_dictionary (metric_key) VALUES (?)
                ON CONFLICT (metric_key) DO UPDATE SET metric_key = EXCLUDED.metric_key
                RETURNING metric_id
                """, Short.class, key)));
        this.idToKey = new CachedLookup<>(id -> jdbc.query(
                "SELECT metric_key FROM metric_dictionary WHERE metric_id = ?",
                rs -> rs.next() ? Optional.of(rs.getString("metric_key")) : Optional.empty(),
                id));
    }

    public short idOf(MetricKey metric) {
        Short id = keyToId.get(metric.value());
        if (id == null) {
            throw new IllegalStateException("無法取得指標 id：" + metric);
        }
        idToKey.put(id, metric.value());
        return id;
    }

    public String keyOf(short metricId) {
        String key = idToKey.get(metricId);
        if (key != null) {
            keyToId.put(key, metricId);
        }
        return key;
    }

    /** 啟動時載入全部，避免上線初期每個新指標都打一次資料庫 */
    public void warmUp() {
        RowCallbackHandler handler = rs -> {
            short id = rs.getShort("metric_id");
            String key = rs.getString("metric_key");
            keyToId.put(key, id);
            idToKey.put(id, key);
        };
        jdbc.query("SELECT metric_id, metric_key FROM metric_dictionary", handler);
    }

    public int size() {
        return keyToId.size();
    }
}
