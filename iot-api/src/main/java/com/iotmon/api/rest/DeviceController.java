package com.iotmon.api.rest;

import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.device.Device;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.device.DeviceStatus;
import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.infrastructure.persistence.CatalogRepository;
import com.iotmon.infrastructure.persistence.DeviceIdResolver;
import com.iotmon.infrastructure.persistence.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * 裝置清單、單筆與註冊。
 *
 * <p>註冊是遙測能被接受的唯一入口（DeviceIdResolver 刻意不自動建立裝置）。
 * 註冊成功後要讓 resolver 的負向快取失效，否則這台裝置在快取過期前送來的遙測都會被丟掉。
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceRepository devices;
    private final CatalogRepository catalog;
    private final DeviceIdResolver resolver;

    public DeviceController(DeviceRepository devices, CatalogRepository catalog, DeviceIdResolver resolver) {
        this.devices = devices;
        this.catalog = catalog;
        this.resolver = resolver;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String cabinetId,
                                  @RequestParam(required = false) String modelCode) {
        try {
            DeviceStatus s = status == null || status.isBlank() ? null
                    : ConfigControllers.parseEnum(DeviceStatus.class, status, "狀態");
            return ResponseEntity.ok(devices.list(s, cabinetId, modelCode).stream().map(DeviceResponse::from).toList());
        } catch (IllegalArgumentException invalid) {
            return ConfigControllers.badRequest(invalid);
        }
    }

    @GetMapping("/{deviceId}")
    public ResponseEntity<?> get(@PathVariable String deviceId) {
        try {
            return devices.find(DeviceId.of(deviceId))
                    .<ResponseEntity<?>>map(row -> ResponseEntity.ok(DeviceResponse.from(row)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException invalid) {
            return ConfigControllers.badRequest(invalid);
        }
    }

    /**
     * 註冊裝置。三道領域檢查依序執行：機型存在、機櫃接受該機型且槽位合法（{@link Cabinet#rejectReasonFor}）、
     * 裝置本身的不變條件（{@link Device#register}）。任何一道不過都回 400 帶原因。
     */
    @PostMapping
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            ModelCode modelCode = ModelCode.of(request.modelCode());
            DeviceModel model = catalog.findModel(modelCode)
                    .orElseThrow(() -> new IllegalArgumentException("機型不存在：" + request.modelCode()));

            Cabinet cabinet = null;
            Short slotNo = null;
            if (request.cabinetId() != null && !request.cabinetId().isBlank()) {
                if (request.slot() == null) {
                    throw new IllegalArgumentException("指定機櫃時必須指定槽位");
                }
                slotNo = request.slot().shortValue();
                cabinet = catalog.findCabinetByCode(request.cabinetId().trim())
                        .orElseThrow(() -> new IllegalArgumentException("機櫃不存在：" + request.cabinetId()));
                String reason = cabinet.rejectReasonFor(modelCode, slotNo);
                if (reason != null) {
                    throw new IllegalArgumentException(reason);
                }
            }

            DeviceId deviceId = DeviceId.of(request.deviceId());
            String serial = request.name() == null || request.name().isBlank() ? deviceId.value() : request.name();
            Device.register(null, deviceId, serial, model, cabinet == null ? null : cabinet.id(), slotNo); // 只為了觸發不變條件檢查

            DeviceRepository.Row saved = devices.insert(deviceId.value(), serial, modelCode.value(), cabinet, slotNo);
            resolver.invalidate(deviceId);
            return ResponseEntity.status(HttpStatus.CREATED).body(DeviceResponse.from(saved));
        } catch (IllegalArgumentException | DuplicateKeyException e) {
            return ConfigControllers.badRequest(e, "裝置代號、序號或機櫃槽位已被使用");
        }
    }

    public record RegisterRequest(String deviceId, String name, String modelCode, String cabinetId, Integer slot) {
    }

    /**
     * 欄位名照前端的 Device 型別：cabinetId 是機櫃 code（與 CabinetResponse.id 一致）、slot 是槽位、
     * name 用序號——資料表沒有獨立的顯示名稱欄位。
     */
    public record DeviceResponse(String deviceId, String name, String modelCode, String cabinetId,
                                 Integer slot, String status, Instant lastSeenAt) {
        static DeviceResponse from(DeviceRepository.Row r) {
            return new DeviceResponse(r.deviceId(), r.serialNo(), r.modelCode(),
                    r.cabinetCode(),
                    r.slotNo() == null ? null : (int) r.slotNo(), r.status().name(), r.lastSeenAt());
        }
    }
}
