package com.iotmon.api.rest;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.cabinet.CabinetType;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.infrastructure.alarm.AlarmEngineConsumer;
import com.iotmon.infrastructure.alarm.AlarmRuleRepository;
import com.iotmon.infrastructure.persistence.CatalogRepository;
import com.iotmon.infrastructure.persistence.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 設定中心：機型、機櫃、告警規則。三個控制器形狀一樣：列出、新增；
 * 新增時先組成領域物件（不變條件在那裡拋），再存。規則錯了改領域層，不是這裡。
 */
public final class ConfigControllers {

    private ConfigControllers() {
    }

    // ── 機型 ─────────────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/api/v1/models")
    public static class ModelController {

        private final CatalogRepository catalog;

        public ModelController(CatalogRepository catalog) {
            this.catalog = catalog;
        }

        @GetMapping
        public List<ModelResponse> list() {
            return catalog.findAllModels().stream().map(ModelResponse::from).toList();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public ModelResponse create(@RequestBody ModelRequest request) {
            List<MetricDefinition> metrics = request.metrics() == null ? List.of()
                    : request.metrics().stream()
                    .map(m -> MetricDefinition.of(m.key(), m.unit(), m.minValue(), m.maxValue()))
                    .toList();
            DeviceModel model = DeviceModel.of(ModelCode.of(request.code()), request.manufacturer(),
                    request.displayName(), metrics);
            return ModelResponse.from(catalog.insertModel(model));
        }

        public record ModelRequest(String code, String manufacturer, String displayName, List<MetricRequest> metrics) {
        }

        public record MetricRequest(String key, String unit, double minValue, double maxValue) {
        }
    }

    public record ModelResponse(String code, String manufacturer, String displayName, List<MetricResponse> metrics) {
        static ModelResponse from(DeviceModel m) {
            return new ModelResponse(m.code().value(), m.manufacturer(), m.displayName(),
                    m.metrics().stream().map(d -> new MetricResponse(d.key().value(), d.unit(),
                            d.minValue(), d.maxValue())).toList());
        }
    }

    public record MetricResponse(String key, String unit, double minValue, double maxValue) {
    }

    // ── 機櫃 ─────────────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/api/v1/cabinets")
    public static class CabinetController {

        private final CatalogRepository catalog;

        public CabinetController(CatalogRepository catalog) {
            this.catalog = catalog;
        }

        @GetMapping
        public List<CabinetResponse> list() {
            return catalog.findAllCabinets().stream().map(CabinetResponse::from).toList();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public CabinetResponse create(@RequestBody CabinetRequest request) {
            CabinetType type = Params.enumOf(CabinetType.class, request.type(), "機櫃類型");
            // 可容納機型由 cabinet_type_model 依類型決定；建立時給佔位讓 Cabinet.of 的檢查通過
            Cabinet cabinet = Cabinet.of(null, request.name(), type, request.location(),
                    (short) request.slotCount(), Set.of(ModelCode.of("UNCONFIGURED")));
            return CabinetResponse.from(catalog.insertCabinet(cabinet));
        }

        public record CabinetRequest(String name, String type, String location, int slotCount) {
        }
    }

    /** id 就是 code：前端拿它當顯示名稱與 Device.cabinetId 的關聯鍵 */
    public record CabinetResponse(String id, String name, String type, String location, int slotCount) {
        static CabinetResponse from(Cabinet c) {
            return new CabinetResponse(c.code(), c.code(), c.type().name(), c.location(), c.slotCount());
        }
    }

    // ── 告警規則 ──────────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/api/v1/alarm-rules")
    public static class AlarmRuleController {

        private final AlarmRuleRepository rules;
        private final CatalogRepository catalog;
        private final DeviceRepository devices;
        private final AlarmEngineConsumer engine;

        public AlarmRuleController(AlarmRuleRepository rules, CatalogRepository catalog, DeviceRepository devices,
                                   AlarmEngineConsumer engine) {
            this.rules = rules;
            this.catalog = catalog;
            this.devices = devices;
            this.engine = engine;
        }

        @GetMapping
        public List<RuleResponse> list() {
            return rules.findAll().stream().map(RuleResponse::from).toList();
        }

        /** 綁機型或綁裝置擇一；門檻要落在該指標的量程內，否則規則永遠不會觸發也不會報錯。 */
        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public RuleResponse create(@RequestBody RuleRequest request) {
            boolean deviceScoped = Params.present(request.deviceId());
            if (deviceScoped == Params.present(request.modelCode())) {
                throw new IllegalArgumentException("規則必須綁定機型或裝置，且只能擇一");
            }
            DeviceId deviceId = deviceScoped ? DeviceId.of(request.deviceId()) : null;
            ModelCode modelCode = deviceScoped
                    ? ModelCode.of(devices.find(deviceId)
                    .orElseThrow(() -> new IllegalArgumentException("裝置不存在：" + request.deviceId())).modelCode())
                    : ModelCode.of(request.modelCode());
            DeviceModel model = catalog.findModel(modelCode)
                    .orElseThrow(() -> new IllegalArgumentException("機型不存在：" + modelCode));
            MetricKey metric = MetricKey.of(request.metric());
            MetricDefinition definition = model.metric(metric)
                    .orElseThrow(() -> new IllegalArgumentException("機型 " + modelCode + " 沒有指標 " + metric));

            Comparison comparison = Params.enumOf(Comparison.class, request.comparison(), "比較方式");
            AlarmSeverity severity = Params.enumOf(AlarmSeverity.class, request.severity(), "嚴重度");
            Duration sustained = Duration.ofSeconds(request.durationSeconds());
            AlarmRule rule = deviceScoped
                    ? AlarmRule.forDevice(null, request.name(), deviceId, metric, comparison,
                    request.threshold(), request.secondaryValue(), severity, sustained, request.enabled())
                    : AlarmRule.forModel(null, request.name(), modelCode, metric, comparison,
                    request.threshold(), request.secondaryValue(), severity, sustained, request.enabled());
            if (!rule.isMeaningfulFor(definition)) {
                throw new IllegalArgumentException("門檻 " + request.threshold() + " 超出指標 " + metric
                        + " 的量程 [" + definition.minValue() + ", " + definition.maxValue() + "]，這條規則永遠不會觸發");
            }

            AlarmRule saved = rules.insert(rule);
            if (deviceScoped) {
                engine.supersede(deviceId, metric); // 被取代的機型規則若正在響，解除並推播
            }
            return RuleResponse.from(saved);
        }

        public record RuleRequest(String name, String modelCode, String deviceId, String metric, String comparison,
                                  double threshold, Double secondaryValue, int durationSeconds,
                                  String severity, boolean enabled) {
        }
    }

    /** modelCode 與 deviceId 恰有一個非 null */
    public record RuleResponse(long id, String name, String modelCode, String deviceId, String metric,
                               String comparison, double threshold, Double secondaryValue, int durationSeconds,
                               String severity, boolean enabled) {
        static RuleResponse from(AlarmRule r) {
            return new RuleResponse(r.id(), r.name(), r.modelCode().map(ModelCode::value).orElse(null),
                    r.deviceId().map(DeviceId::value).orElse(null),
                    r.metric().value(), r.comparison().name(), r.threshold(),
                    r.secondaryValue().orElse(null), (int) r.sustainedFor().toSeconds(),
                    r.severity().name(), r.enabled());
        }
    }
}
