package com.iotmon.infrastructure.persistence;

import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.cabinet.CabinetType;
import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.ModelCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 機型與機櫃。讀出來的是領域物件：機型帶指標定義、機櫃帶可容納的機型，
 * 「門檻在不在量程內」「裝置能不能插這個槽」才能直接呼叫領域方法，不在控制器裡再抄一次規則。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 機型 ─────────────────────────────────────────────────────────────

    public List<DeviceModel> findAllModels() {
        Map<String, List<MetricDefinition>> metricsByModel = loadMetrics(null);
        List<DeviceModel> result = new ArrayList<>();
        jdbc.query("SELECT code, manufacturer, display_name FROM device_model ORDER BY code", rs -> {
            String code = rs.getString("code");
            List<MetricDefinition> metrics = metricsByModel.getOrDefault(code, List.of());
            if (!metrics.isEmpty()) {
                result.add(DeviceModel.of(ModelCode.of(code), rs.getString("manufacturer"),
                        rs.getString("display_name"), metrics));
            }
        });
        return result;
    }

    public Optional<DeviceModel> findModel(ModelCode code) {
        List<MetricDefinition> metrics = loadMetrics(code.value()).getOrDefault(code.value(), List.of());
        if (metrics.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT manufacturer, display_name FROM device_model WHERE code = ?",
                rs -> rs.next()
                        ? Optional.of(DeviceModel.of(code, rs.getString("manufacturer"),
                        rs.getString("display_name"), metrics))
                        : Optional.<DeviceModel>empty(),
                code.value());
    }

    /** 機型與它的指標在同一個交易裡寫入：只寫了機型沒寫指標，就是一個永遠沒資料的機型。 */
    @Transactional
    public DeviceModel insertModel(DeviceModel model) {
        jdbc.update("INSERT INTO device_model (code, manufacturer, display_name) VALUES (?, ?, ?)",
                model.code().value(), model.manufacturer(), model.displayName());
        int ordinal = 1;
        for (MetricDefinition m : model.metrics()) {
            jdbc.update("""
                    INSERT INTO device_model_metric (model_code, metric_key, unit, min_value, max_value, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, model.code().value(), m.key().value(), m.unit(), m.minValue(), m.maxValue(), ordinal++);
        }
        return model;
    }

    private Map<String, List<MetricDefinition>> loadMetrics(String onlyModel) {
        Map<String, List<MetricDefinition>> byModel = new LinkedHashMap<>();
        String sql = "SELECT model_code, metric_key, unit, min_value, max_value FROM device_model_metric"
                + (onlyModel == null ? "" : " WHERE model_code = ?") + " ORDER BY model_code, ordinal";
        Object[] args = onlyModel == null ? new Object[0] : new Object[]{onlyModel};
        jdbc.query(sql, rs -> {
            byModel.computeIfAbsent(rs.getString("model_code"), k -> new ArrayList<>())
                    .add(MetricDefinition.of(rs.getString("metric_key"), rs.getString("unit"),
                            rs.getDouble("min_value"), rs.getDouble("max_value")));
        }, args);
        return byModel;
    }

    // ── 機櫃 ─────────────────────────────────────────────────────────────

    public List<Cabinet> findAllCabinets() {
        Map<String, Set<ModelCode>> accepted = loadAcceptedModels();
        List<Cabinet> result = new ArrayList<>();
        jdbc.query("SELECT id, code, cabinet_type, location, slot_count FROM cabinet ORDER BY code", rs -> {
            String type = rs.getString("cabinet_type");
            Set<ModelCode> models = accepted.get(type);
            // 該類型還沒設定可容納機型時仍要列出來，但 Cabinet.of 要求非空——這是設定缺口，
            // 不該讓整份清單消失；用一個明顯的佔位讓設定畫面看得出來
            result.add(Cabinet.of(rs.getLong("id"), rs.getString("code"), CabinetType.valueOf(type),
                    rs.getString("location"), rs.getShort("slot_count"),
                    models == null || models.isEmpty() ? Set.of(ModelCode.of("UNCONFIGURED")) : models));
        });
        return result;
    }

    /** 對外一律用 code 認機櫃：前端把它當顯示名稱與關聯鍵，數值 id 只在資料庫內部用 */
    public Optional<Cabinet> findCabinetByCode(String code) {
        Map<String, Set<ModelCode>> accepted = loadAcceptedModels();
        return jdbc.query("SELECT id, code, cabinet_type, location, slot_count FROM cabinet WHERE code = ?",
                rs -> {
                    if (!rs.next()) {
                        return Optional.<Cabinet>empty();
                    }
                    String type = rs.getString("cabinet_type");
                    Set<ModelCode> models = accepted.getOrDefault(type, Set.of(ModelCode.of("UNCONFIGURED")));
                    return Optional.of(Cabinet.of(rs.getLong("id"), rs.getString("code"),
                            CabinetType.valueOf(type), rs.getString("location"),
                            rs.getShort("slot_count"), models));
                }, code);
    }

    public Cabinet insertCabinet(Cabinet cabinet) {
        Long id = jdbc.queryForObject("""
                INSERT INTO cabinet (code, cabinet_type, location, slot_count) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, cabinet.code(), cabinet.type().name(), cabinet.location(), cabinet.slotCount());
        return Cabinet.of(id, cabinet.code(), cabinet.type(), cabinet.location(),
                cabinet.slotCount(), cabinet.acceptedModels());
    }

    /** 機櫃類型 → 可容納的機型。整張表以十計，一次載入。 */
    private Map<String, Set<ModelCode>> loadAcceptedModels() {
        Map<String, Set<ModelCode>> map = new HashMap<>();
        jdbc.query("SELECT cabinet_type, model_code FROM cabinet_type_model", (java.sql.ResultSet rs) -> {
            map.computeIfAbsent(rs.getString("cabinet_type"), k -> new HashSet<>())
                    .add(ModelCode.of(rs.getString("model_code")));
        });
        return map;
    }
}
