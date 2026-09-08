package com.iotmon.infrastructure.persistence;

import com.iotmon.application.query.Resolution;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 歷史查詢：依時間跨度路由到三層中的一層。
 *
 * <p>這是「查詢一秒內」的實作。關鍵不在索引，而在**不要去掃原始資料**——
 * 跨兩年的查詢讀的是預先算好的 17,520 個小時桶，比查六小時的原始資料還少。
 */
@Repository
public class TimescaleTelemetryQuery {

    /** 單次查詢允許回傳的最大點數。超標時先往粗的層級退，退到底才拒絕。 */
    public static final int MAX_POINTS = 5000;

    private final JdbcTemplate jdbc;
    private final MetricDictionary metrics;
    private final DeviceIdResolver devices;

    public TimescaleTelemetryQuery(JdbcTemplate jdbc, MetricDictionary metrics,
                                   DeviceIdResolver devices) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.devices = devices;
    }

    public Series query(DeviceId deviceId, MetricKey metric, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("查詢區間無效：from 必須早於 to");
        }

        Integer numericDeviceId = devices.numericIdOf(deviceId);
        if (numericDeviceId == null) {
            throw new IllegalArgumentException("裝置未註冊：" + deviceId);
        }

        // 層級由跨度與點數上限共同決定：點數超標時往粗的層級退，
        // 只有連小時層都放不下時才拒絕（forSpan 會拋例外）
        Duration span = Duration.between(from, to);
        Resolution resolution = Resolution.forSpan(span, MAX_POINTS);

        short metricId = metrics.idOf(metric);
        List<Point> points = resolution == Resolution.RAW
                ? queryRaw(numericDeviceId, metricId, from, to)
                : queryAggregate(resolution, numericDeviceId, metricId, from, to);

        return new Series(deviceId, metric, resolution, points);
    }

    private List<Point> queryRaw(int deviceId, short metricId, Instant from, Instant to) {
        // 原始層沒有 min/max 的概念，三個值都填同一個讀數，
        // 讓前端不必為兩種形狀寫兩套繪圖邏輯
        return jdbc.query("""
                        SELECT time, value
                        FROM telemetry
                        WHERE device_id = ? AND metric_id = ? AND time >= ? AND time < ?
                        ORDER BY time
                        LIMIT ?
                        """,
                (rs, i) -> {
                    double v = rs.getDouble("value");
                    return new Point(rs.getTimestamp("time").toInstant(), v, v, v, 1);
                },
                deviceId, metricId, java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(to), MAX_POINTS);
    }

    private List<Point> queryAggregate(Resolution resolution, int deviceId, short metricId,
                                       Instant from, Instant to) {
        // 資料表名稱來自列舉而非呼叫端輸入，所以字串組裝在這裡是安全的；
        // 其餘參數一律走預備語句。
        String sql = """
                SELECT bucket, avg_value, min_value, max_value, sample_count
                FROM %s
                WHERE device_id = ? AND metric_id = ? AND bucket >= ? AND bucket < ?
                ORDER BY bucket
                LIMIT ?
                """.formatted(resolution.tableName());

        return jdbc.query(sql,
                (rs, i) -> new Point(
                        rs.getTimestamp("bucket").toInstant(),
                        rs.getDouble("avg_value"),
                        rs.getDouble("min_value"),
                        rs.getDouble("max_value"),
                        rs.getLong("sample_count")),
                deviceId, metricId, java.sql.Timestamp.from(from),
                java.sql.Timestamp.from(to), MAX_POINTS);
    }

    /**
     * @param resolution 一定要回傳給呼叫端。不標示的話，前端畫出來的平滑曲線
     *                   會被當成真實讀數，而它其實可能是小時平均。
     */
    public record Series(DeviceId deviceId, MetricKey metric, Resolution resolution,
                         List<Point> points) {
    }

    public record Point(Instant time, double avg, double min, double max, long count) {
    }
}
