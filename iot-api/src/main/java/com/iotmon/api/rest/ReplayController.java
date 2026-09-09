package com.iotmon.api.rest;

import com.iotmon.infrastructure.persistence.ReplayRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 歷史回放。回應帶 {@code queryMs}：這一頁的存在意義就是讓「兩年資料一秒內」在畫面上看得到，
 * 所以伺服器端的查詢耗時要誠實地跟資料一起回去。
 */
@RestController
@RequestMapping("/api/v1/replay")
public class ReplayController {

    /** 五年是小時層的保留期限，再早就沒有資料可回放 */
    private static final Duration MAX_LOOKBACK = Duration.ofDays(5 * 365);
    private static final int TIMELINE_LIMIT = 1000;

    private final ReplayRepository replay;

    public ReplayController(ReplayRepository replay) {
        this.replay = replay;
    }

    @GetMapping
    public ResponseEntity<?> snapshot(@RequestParam String cabinetId, @RequestParam String at) {
        Instant now = Instant.now();
        Instant when;
        try {
            when = Instant.parse(at);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "at 必須是 ISO-8601 時間，例如 2026-09-01T08:00:00Z"));
        }
        if (when.isAfter(now)) {
            when = now; // 未來沒有資料，直接看現在
        }
        if (when.isBefore(now.minus(MAX_LOOKBACK))) {
            return ResponseEntity.badRequest().body(Map.of("message", "只保留五年的小時層資料，無法回放更早的時間"));
        }

        long started = System.nanoTime();
        var result = replay.snapshot(cabinetId.trim(), when);
        long queryMs = (System.nanoTime() - started) / 1_000_000;
        return result.<ResponseEntity<?>>map(s -> ResponseEntity.ok(SnapshotResponse.from(s, queryMs)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "機櫃不存在：" + cabinetId)));
    }

    @GetMapping("/timeline")
    public ResponseEntity<?> timeline(@RequestParam String cabinetId, @RequestParam String from, @RequestParam String to) {
        Instant f;
        Instant t;
        try {
            f = Instant.parse(from);
            t = Instant.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "from／to 必須是 ISO-8601 時間"));
        }
        if (!f.isBefore(t)) {
            return ResponseEntity.badRequest().body(Map.of("message", "from 必須早於 to"));
        }
        long started = System.nanoTime();
        List<ReplayRepository.TimelineAlarm> alarms = replay.timeline(cabinetId.trim(), f, t, TIMELINE_LIMIT);
        long queryMs = (System.nanoTime() - started) / 1_000_000;
        return ResponseEntity.ok(new TimelineResponse(f, t, queryMs, alarms.size() >= TIMELINE_LIMIT, alarms));
    }

    public record ReadingResponse(double avg, double min, double max, long count) {
        static ReadingResponse from(ReplayRepository.Reading r) {
            return new ReadingResponse(r.avg(), r.min(), r.max(), r.count());
        }
    }

    public record DeviceResponse(String deviceId, String name, String modelCode, Short slot, boolean hadData,
                                 Map<String, ReadingResponse> readings, List<ReplayRepository.ActiveAlarm> alarms) {
        static DeviceResponse from(ReplayRepository.DeviceSnapshot d) {
            Map<String, ReadingResponse> readings = new java.util.LinkedHashMap<>();
            d.readings().forEach((k, v) -> readings.put(k, ReadingResponse.from(v)));
            return new DeviceResponse(d.deviceId(), d.name(), d.modelCode(), d.slot(), d.hadData(), readings, d.alarms());
        }
    }

    /** resolution 一定回：呼叫端要知道拿到的是原始值、分鐘平均還是小時平均 */
    public record SnapshotResponse(Instant at, String resolution, Instant bucketStart, Instant bucketEnd,
                                   long queryMs, List<DeviceResponse> devices) {
        static SnapshotResponse from(ReplayRepository.Snapshot s, long queryMs) {
            return new SnapshotResponse(s.at(), s.resolution().apiValue(), s.bucketStart(), s.bucketEnd(), queryMs,
                    s.devices().stream().map(DeviceResponse::from).toList());
        }
    }

    public record TimelineResponse(Instant from, Instant to, long queryMs, boolean truncated,
                                   List<ReplayRepository.TimelineAlarm> alarms) {
    }
}
