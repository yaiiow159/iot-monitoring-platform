package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.device.DeviceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 裝置字串識別碼 → 資料庫主鍵。與 {@link MetricDictionary} 的差別是這裡不自動建立：
 * 未註冊的裝置要被丟棄，否則打錯字的 deviceId 會變成一台沒有機型也沒有機櫃的幽靈裝置。
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
