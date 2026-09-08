package com.iotmon.infrastructure.persistence;

import com.iotmon.application.port.out.TelemetryWriter;
import com.iotmon.domain.telemetry.TelemetryPoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * 批次寫入 TimescaleDB。
 *
 * <p>整條路徑上唯一會碰資料庫的地方，也是每秒五萬點的瓶頸所在。
 * 三個關鍵取捨：
 *
 * <ul>
 *   <li><b>只有批次介面</b>——單筆寫入在這個流量下必然拖垮連線池</li>
 *   <li><b>不用 JPA</b>——實體管理、髒檢查與一級快取在這裡全是純開銷，
 *       而且會把整批資料留在記憶體裡直到交易結束</li>
 *   <li><b>不加唯一約束</b>——維護索引的成本高於它擋掉的重複，
 *       而重複點在聚合時本來就會被 avg 吸收</li>
 * </ul>
 */
@Component
public class TimescaleTelemetryWriter implements TelemetryWriter {

    private static final Logger log = LoggerFactory.getLogger(TimescaleTelemetryWriter.class);

    private static final String INSERT_SQL =
            "INSERT INTO telemetry (time, device_id, metric_id, value) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final MetricDictionary metrics;
    private final DeviceIdResolver devices;
    private final Timer writeTimer;

    public TimescaleTelemetryWriter(JdbcTemplate jdbc, MetricDictionary metrics,
                                    DeviceIdResolver devices, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.devices = devices;
        this.writeTimer = Timer.builder("telemetry.write.batch")
                .description("一批遙測寫入資料庫的耗時")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Override
    public int write(List<TelemetryPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }

        // 先把字串轉成數值 id 再進批次：轉換過程可能要查資料庫（新指標／新裝置），
        // 夾在 batchUpdate 中間會讓那次查詢跟批次共用同一條連線而卡住。
        List<Row> rows = points.stream()
                .map(this::toRow)
                .filter(java.util.Objects::nonNull)
                .toList();

        if (rows.isEmpty()) {
            return 0;
        }

        return writeTimer.record(() -> {
            int[] results = jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Row row = rows.get(i);
                    ps.setTimestamp(1, Timestamp.from(row.time()));
                    ps.setInt(2, row.deviceId());
                    ps.setShort(3, row.metricId());
                    ps.setDouble(4, row.value());
                }

                @Override
                public int getBatchSize() {
                    return rows.size();
                }
            });
            return results.length;
        });
    }

    /**
     * @return null 代表這筆要丟棄——裝置沒註冊過。
     *         丟棄而不是拋例外：一筆無主的遙測不該讓整批四百筆一起失敗。
     */
    private Row toRow(TelemetryPoint point) {
        Integer deviceId = devices.numericIdOf(point.deviceId());
        if (deviceId == null) {
            log.debug("丟棄未註冊裝置的遙測：{}", point.deviceId());
            return null;
        }
        return new Row(point.timestamp(), deviceId, metrics.idOf(point.metric()), point.value());
    }

    private record Row(java.time.Instant time, int deviceId, short metricId, double value) {
    }
}
