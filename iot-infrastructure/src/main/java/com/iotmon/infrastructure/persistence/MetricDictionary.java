package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.model.MetricKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指標代號 ↔ 數值 id 的字典。
 *
 * <p>時序表每列存 SMALLINT 而不是 63 位元組的字串，每列省下約 60 位元組。
 * 以每秒五萬筆計算，這個轉換一天就省下 260 GB 的原始寫入量——
 * 「兩年歷史放得下」有一半是靠這件事。
 *
 * <p>全部快取在記憶體：指標種類是機型定義出來的，數量以十計而非以萬計，
 * 而查詢它的頻率是每秒五萬次。這種比例不做快取就是把資料庫當成雜湊表用。
 */
@Component
public class MetricDictionary {

    private final JdbcTemplate jdbc;
    private final Map<String, Short> keyToId = new ConcurrentHashMap<>();
    private final Map<Short, String> idToKey = new ConcurrentHashMap<>();

    public MetricDictionary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取得（必要時建立）指標的數值 id。
     *
     * <p>用 ON CONFLICT 而不是「先查後寫」：多個消費端執行緒可能同時遇到同一個新指標，
     * 先查後寫在那個瞬間會有兩條路徑都認為要新增，其中一條會撞上唯一約束。
     */
    public short idOf(MetricKey metric) {
        String key = metric.value();
        Short cached = keyToId.get(key);
        if (cached != null) {
            return cached;
        }

        Short id = jdbc.queryForObject("""
                INSERT INTO metric_dictionary (metric_key) VALUES (?)
                ON CONFLICT (metric_key) DO UPDATE SET metric_key = EXCLUDED.metric_key
                RETURNING metric_id
                """, Short.class, key);

        if (id == null) {
            throw new IllegalStateException("無法取得指標 id：" + key);
        }
        keyToId.put(key, id);
        idToKey.put(id, key);
        return id;
    }

    public String keyOf(short metricId) {
        String cached = idToKey.get(metricId);
        if (cached != null) {
            return cached;
        }
        String key = jdbc.queryForObject(
                "SELECT metric_key FROM metric_dictionary WHERE metric_id = ?",
                String.class, metricId);
        if (key != null) {
            idToKey.put(metricId, key);
            keyToId.put(key, metricId);
        }
        return key;
    }

    /** 啟動時載入全部，避免上線初期每個新指標都打一次資料庫 */
    public void warmUp() {
        // 明確標註型別，否則 lambda 對 query 的兩個多載都適用而編譯不過
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
