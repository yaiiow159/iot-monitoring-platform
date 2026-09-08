package com.iotmon.api.rest;

import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.infrastructure.persistence.TimescaleTelemetryQuery;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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

        } catch (IllegalArgumentException | InvalidDataAccessApiUsageException badRequest) {
            // 兩種型別都要接：Spring 會把 @Repository 拋出的 IllegalArgumentException
            // 轉譯成 InvalidDataAccessApiUsageException，只接前者的話這裡會漏掉，
            // 使用者收到的是 500 而不是帶有原因的 400。
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(rootMessage(badRequest)));
        }
    }

    /** 例外被轉譯過時，有用的訊息在最內層的 cause 上 */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
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
