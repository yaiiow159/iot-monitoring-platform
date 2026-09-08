package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.ModelCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 裝置 → 機型、以及裝置 → 監控樹節點。
 *
 * <p>告警引擎每一筆遙測都要問「這台是什麼機型」才知道套哪些規則；
 * 推播端每則告警都要問「這台在樹的哪裡」才知道祖先鏈。
 * 兩者都建在 {@link CachedLookup} 上，跟 DeviceIdResolver 同一套機制。
 *
 * <p>樹節點的快取在裝置被掛到樹上時要失效——本階段樹由設定中心維護、
 * 變動頻率低，先用短 TTL 的替代方案：不快取「不在樹上」這個結果。
 */
@Component
public class DeviceCatalog {

    private final CachedLookup<String, ModelCode> models;
    private final JdbcTemplate jdbc;

    public DeviceCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.models = new CachedLookup<>(deviceId -> jdbc.query(
                "SELECT model_code FROM device WHERE device_id = ?",
                rs -> rs.next() ? Optional.of(ModelCode.of(rs.getString("model_code"))) : Optional.empty(),
                deviceId));
    }

    /** @return 未註冊的裝置回傳 empty */
    public Optional<ModelCode> modelOf(DeviceId deviceId) {
        return Optional.ofNullable(models.get(deviceId.value()));
    }

    /**
     * 裝置在監控樹上的節點 id。刻意不快取：裝置剛被掛上樹的那一刻就要能上浮，
     * 而這個查詢只在告警觸發／解除時發生，頻率遠低於遙測。
     */
    public Optional<Long> treeNodeOf(int deviceRowId) {
        return jdbc.query("SELECT id FROM monitoring_node WHERE device_id = ?",
                rs -> rs.next() ? Optional.of(rs.getLong("id")) : Optional.<Long>empty(),
                deviceRowId);
    }
}
