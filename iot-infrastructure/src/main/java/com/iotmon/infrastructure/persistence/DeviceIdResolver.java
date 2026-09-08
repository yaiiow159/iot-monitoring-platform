package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.device.DeviceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 裝置字串識別碼 → 資料庫數值主鍵。
 *
 * <p>與 {@link MetricDictionary} 同樣的理由：時序表每列存 INTEGER 而不是
 * 64 位元組的字串。差別在於**這裡不會自動建立**——
 * 未註冊的裝置送來的遙測要被丟棄，不是默默幫它建一筆。
 *
 * <p>自動建立看起來方便，但它會讓「打錯字的 deviceId」變成一台幽靈裝置，
 * 而且沒有機型、沒有機櫃、不會有人發現。設定中心是唯一的註冊入口。
 * 「查不到」的結果由 {@link CachedLookup} 記住，否則同一個打錯字的 id
 * 會每秒打資料庫五萬次。
 */
@Component
public class DeviceIdResolver {

    private final JdbcTemplate jdbc;
    private final CachedLookup<String, Integer> lookup;

    public DeviceIdResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.lookup = new CachedLookup<>(deviceId -> jdbc.query(
                "SELECT id FROM device WHERE device_id = ?",
                rs -> rs.next() ? Optional.of(rs.getInt("id")) : Optional.empty(),
                deviceId));
    }

    /** @return null 代表這台裝置沒有註冊過 */
    public Integer numericIdOf(DeviceId deviceId) {
        return lookup.get(deviceId.value());
    }

    /** 註冊新裝置後呼叫，讓它立刻可用而不必等快取失效 */
    public void invalidate(DeviceId deviceId) {
        lookup.invalidate(deviceId.value());
    }

    public void warmUp() {
        RowCallbackHandler handler = rs -> lookup.put(rs.getString("device_id"), rs.getInt("id"));
        jdbc.query("SELECT id, device_id FROM device", handler);
    }

    public int cachedCount() {
        return lookup.size();
    }
}
