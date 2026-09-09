package com.iotmon.api.rest;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.infrastructure.persistence.TimescaleTelemetryQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** 歷史查詢。層級路由在 {@link com.iotmon.application.query.Resolution}，這裡只是對外的形狀。 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TimescaleTelemetryQuery query;

    public TelemetryController(TimescaleTelemetryQuery query) {
        this.query = query;
    }

    @GetMapping
    public SeriesResponse history(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        TimescaleTelemetryQuery.Series series = query.query(DeviceId.of(deviceId), MetricKey.of(metric), from, to);
        return new SeriesResponse(series.deviceId().value(), series.metric().value(),
                series.resolution().apiValue(), series.points().stream().map(PointResponse::from).toList());
    }

    /** resolution 一定回：呼叫端要知道拿到的是原始值還是聚合值 */
    public record SeriesResponse(String deviceId, String metric, String resolution, List<PointResponse> points) {
    }

    public record PointResponse(Instant t, double avg, double min, double max, long count) {
        static PointResponse from(TimescaleTelemetryQuery.Point point) {
            return new PointResponse(point.time(), point.avg(), point.min(), point.max(), point.count());
        }
    }
}
