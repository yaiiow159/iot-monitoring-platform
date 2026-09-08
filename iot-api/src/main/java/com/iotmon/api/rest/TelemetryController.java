package com.iotmon.api.rest;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.infrastructure.persistence.TimescaleTelemetryQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 歷史查詢。
 *
 * <p>這支端點是「查詢一秒內」需求的對外形式。它做的事情很少——
 * 真正的機制在 {@link com.iotmon.application.query.Resolution} 的層級路由，
 * 以及 TimescaleDB 的連續聚合。
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TimescaleTelemetryQuery query;

    public TelemetryController(TimescaleTelemetryQuery query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<?> history(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        try {
            TimescaleTelemetryQuery.Series series =
                    query.query(DeviceId.of(deviceId), MetricKey.of(metric), from, to);

            return ResponseEntity.ok(new SeriesResponse(
                    series.deviceId().value(),
                    series.metric().value(),
                    series.resolution().apiValue(),
                    series.points().stream().map(PointResponse::from).toList()));

        } catch (IllegalArgumentException badRequest) {
            // 包含「區間會產生太多點」。回 400 並說明原因，不安靜截斷——
            // 少了一半資料的圖表比沒有圖表更危險，因為它看起來是對的。
            return ResponseEntity.badRequest().body(new ErrorResponse(badRequest.getMessage()));
        }
    }

    /**
     * @param resolution 實際讀取的層級（raw／1m／1h）。一定要回傳：
     *                   呼叫端必須知道自己拿到的是原始值還是聚合值。
     */
    public record SeriesResponse(String deviceId, String metric, String resolution,
                                 List<PointResponse> points) {
    }

    public record PointResponse(Instant t, double avg, double min, double max, long count) {
        static PointResponse from(TimescaleTelemetryQuery.Point point) {
            return new PointResponse(point.time(), point.avg(), point.min(),
                    point.max(), point.count());
        }
    }

    public record ErrorResponse(String message) {
    }
}
