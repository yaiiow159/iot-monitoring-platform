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
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** 裝置清單、單筆與註冊。註冊是遙測能被接受的唯一入口，成功後要讓 DeviceIdResolver 的負向快取失效。 */
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
    public List<DeviceResponse> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String cabinetId,
                                     @RequestParam(required = false) String modelCode) {
        DeviceStatus s = Params.enumOrNull(DeviceStatus.class, status, "狀態");
        return devices.list(s, cabinetId, modelCode).stream().map(DeviceResponse::from).toList();
    }

    @GetMapping("/{deviceId}")
    public DeviceResponse get(@PathVariable String deviceId) {
        return devices.find(DeviceId.of(deviceId)).map(DeviceResponse::from)
                .orElseThrow(() -> ApiException.notFound("裝置不存在：" + deviceId));
    }

    /** 三道領域檢查：機型存在、機櫃收這個機型且槽位合法（Cabinet.rejectReasonFor）、裝置本身的不變條件。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@RequestBody RegisterRequest request) {
        ModelCode modelCode = ModelCode.of(request.modelCode());
        DeviceModel model = catalog.findModel(modelCode)
                .orElseThrow(() -> new IllegalArgumentException("機型不存在：" + request.modelCode()));

        Cabinet cabinet = null;
        Short slotNo = null;
        if (Params.present(request.cabinetId())) {
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
        String serial = Params.present(request.name()) ? request.name() : deviceId.value();
        Device.register(null, deviceId, serial, model, cabinet == null ? null : cabinet.id(), slotNo);

        DeviceRepository.Row saved;
        try {
            saved = devices.insert(deviceId.value(), serial, modelCode.value(), cabinet, slotNo);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("裝置代號、序號或機櫃槽位已被使用");
        }
        resolver.invalidate(deviceId);
        return DeviceResponse.from(saved);
    }

    public record RegisterRequest(String deviceId, String name, String modelCode, String cabinetId, Integer slot) {
    }

    /** cabinetId 是機櫃 code（與 CabinetResponse.id 一致）；name 用序號，資料表沒有獨立的顯示名稱 */
    public record DeviceResponse(String deviceId, String name, String modelCode, String cabinetId,
                                 Integer slot, String status, Instant lastSeenAt) {
        static DeviceResponse from(DeviceRepository.Row r) {
            return new DeviceResponse(r.deviceId(), r.serialNo(), r.modelCode(), r.cabinetCode(),
                    r.slotNo() == null ? null : (int) r.slotNo(), r.status().name(), r.lastSeenAt());
        }
    }
}
