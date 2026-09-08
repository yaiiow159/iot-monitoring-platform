package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.device.DeviceId;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 裝置字串識別碼 → 資料庫數值主鍵。
 *
 * <p>與 {@link MetricDictionary} 同樣的理由：時序表每列存 INTEGER 而不是
 * 64 位元組的字串。差別在於**這裡不會自動建立**——
 * 未註冊的裝置送來的遙測要被丟棄，不是默默幫它建一筆。
 *
 * <p>自動建立看起來方便，但它會讓「打錯字的 deviceId」變成一台幽靈裝置，
 * 而且沒有機型、沒有機櫃、不會有人發現。設定中心是唯一的註冊入口。
 */
@Component
public class DeviceIdResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();
    /** 快取查不到的結果，避免同一個打錯字的 id 每秒打資料庫五萬次 */
    private final Map<String, Boolean> knownMissing = new ConcurrentHashMap<>();

    public DeviceIdResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return null 代表這台裝置沒有註冊過 */
    public Integer numericIdOf(DeviceId deviceId) {
        String key = deviceId.value();
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (knownMissing.containsKey(key)) {
            return null;
        }

        try {
            Integer id = jdbc.queryForObject(
                    "SELECT id FROM device WHERE device_id = ?", Integer.class, key);
            if (id != null) {
                cache.put(key, id);
            }
            return id;
        } catch (EmptyResultDataAccessException notRegistered) {
            knownMissing.put(key, Boolean.TRUE);
            return null;
        }
    }

    /** 註冊新裝置後呼叫，讓它立刻可用而不必等快取失效 */
    public void invalidate(DeviceId deviceId) {
        cache.remove(deviceId.value());
        knownMissing.remove(deviceId.value());
    }

    public void warmUp() {
        // 明確標註 RowCallbackHandler：不標的話 lambda 同時符合
        // query(String, ResultSetExtractor) 與 query(String, RowCallbackHandler)，編譯不過
        RowCallbackHandler handler = rs -> cache.put(rs.getString("device_id"), rs.getInt("id"));
        jdbc.query("SELECT id, device_id FROM device", handler);
    }

    public int cachedCount() {
        return cache.size();
    }
}
