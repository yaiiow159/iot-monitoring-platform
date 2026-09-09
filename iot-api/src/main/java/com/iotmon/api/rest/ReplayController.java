package com.iotmon.api.rest;

import com.iotmon.infrastructure.persistence.ReplayRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 歷史回放。回應帶 queryMs：這一頁存在的意義就是讓「兩年資料一秒內」看得到。 */
@RestController
@RequestMapping("/api/v1/replay")
public class ReplayController {

    /** 小時層保留五年，再早沒有資料可回放 */
    private static final Duration MAX_LOOKBACK = Duration.ofDays(5 * 365);
    private static final int TIMELINE_LIMIT = 1000;

    private final ReplayRepository replay;

    public ReplayController(ReplayRepository replay) {
        this.replay = replay;
    }

    @GetMapping
    public SnapshotResponse snapshot(@RequestParam String cabinetId, @RequestParam String at) {
        Instant now = Instant.now();
        Instant when = Params.instant(at, "at");
        if (when.isAfter(now)) {
            when = now;
        }
        if (when.isBefore(now.minus(MAX_LOOKBACK))) {
            throw new IllegalArgumentException("只保留五年的小時層資料，無法回放更早的時間");
        }
        long started = System.nanoTime();
        ReplayRepository.Snapshot s = replay.snapshot(cabinetId.trim(), when)
                .orElseThrow(() -> ApiException.notFound("機櫃不存在：" + cabinetId));
        return SnapshotResponse.from(s, (System.nanoTime() - started) / 1_000_000);
    }

    @GetMapping("/timeline")
    public TimelineResponse timeline(@RequestParam String cabinetId, @RequestParam String from, @RequestParam String to) {
        Instant f = Params.instant(from, "from");
        Instant t = Params.instant(to, "to");
        if (!f.isBefore(t)) {
            throw new IllegalArgumentException("from 必須早於 to");
        }
        long started = System.nanoTime();
        List<ReplayRepository.TimelineAlarm> alarms = replay.timeline(cabinetId.trim(), f, t, TIMELINE_LIMIT);
        return new TimelineResponse(f, t, (System.nanoTime() - started) / 1_000_000,
                alarms.size() >= TIMELINE_LIMIT, alarms);
    }

    public record ReadingResponse(double avg, double min, double max, long count) {
        static ReadingResponse from(ReplayRepository.Reading r) {
            return new ReadingResponse(r.avg(), r.min(), r.max(), r.count());
        }
    }

    public record DeviceResponse(String deviceId, String name, String modelCode, Short slot, boolean hadData,
                                 Map<String, ReadingResponse> readings, List<ReplayRepository.ActiveAlarm> alarms) {
        static DeviceResponse from(ReplayRepository.DeviceSnapshot d) {
            Map<String, ReadingResponse> readings = new LinkedHashMap<>();
            d.readings().forEach((k, v) -> readings.put(k, ReadingResponse.from(v)));
            return new DeviceResponse(d.deviceId(), d.name(), d.modelCode(), d.slot(), d.hadData(), readings, d.alarms());
        }
    }

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
