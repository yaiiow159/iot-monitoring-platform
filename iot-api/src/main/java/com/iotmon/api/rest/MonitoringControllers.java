package com.iotmon.api.rest;

import com.iotmon.infrastructure.alarm.AlarmQueryRepository;
import com.iotmon.infrastructure.persistence.DeviceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 儀表板摘要與告警列表。 */
public final class MonitoringControllers {

    private MonitoringControllers() {
    }

    @RestController
    @RequestMapping("/api/v1/overview")
    public static class OverviewController {

        private final DeviceRepository devices;

        public OverviewController(DeviceRepository devices) {
            this.devices = devices;
        }

        /** 三個查詢都很輕（各一次聚合），儀表板每幾秒問一次不需要快取。 */
        @GetMapping
        public OverviewResponse overview() {
            Map<String, Long> counts = new LinkedHashMap<>();
            devices.countByStatus().forEach((status, n) -> counts.put(status.name(), n));
            return new OverviewResponse(counts, devices.countFiringAlarms(),
                    devices.ingestRatePerSecond(), Instant.now());
        }

        /** deviceCounts 一定含四個狀態的 key，前端的 Record 型別是這樣定的 */
        public record OverviewResponse(Map<String, Long> deviceCounts, long unresolvedAlarms,
                                       double ingestRatePerSecond, Instant generatedAt) {
        }
    }

    @RestController
    @RequestMapping("/api/v1/alarms")
    public static class AlarmController {

        private final AlarmQueryRepository alarms;

        public AlarmController(AlarmQueryRepository alarms) {
            this.alarms = alarms;
        }

        @GetMapping
        public List<AlarmResponse> list(@RequestParam(required = false) String state,
                                        @RequestParam(required = false) String deviceId,
                                        @RequestParam(required = false) Integer limit) {
            return alarms.list(state, deviceId, limit).stream().map(AlarmResponse::from).toList();
        }

        /** message 用規則名稱：值班的人要看的是「哪條規則響了」，不是規則 id */
        public record AlarmResponse(long alarmId, String deviceId, String deviceName, String cabinetId,
                                    String metric, String severity, String state, String message,
                                    Double value, double threshold, Instant firedAt, Instant resolvedAt) {
            static AlarmResponse from(AlarmQueryRepository.Row r) {
                return new AlarmResponse(r.alarmId(), r.deviceId(), r.deviceName(),
                        r.cabinetCode(), r.metric(),
                        r.severity(), r.state(), r.message(), r.value(), r.threshold(),
                        r.firedAt(), r.resolvedAt());
            }
        }
    }
}
