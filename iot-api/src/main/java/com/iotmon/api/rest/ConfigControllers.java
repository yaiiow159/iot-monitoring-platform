package com.iotmon.api.rest;

import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.cabinet.CabinetType;
import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.infrastructure.alarm.AlarmRuleRepository;
import com.iotmon.infrastructure.persistence.CatalogRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 設定中心：機型、機櫃、告警規則。
 *
 * <p>三個控制器放同一個檔案，因為它們的形狀一模一樣：列出、新增，
 * 新增時先組成領域物件（讓不變條件在那裡拋例外），再存。
 * 控制器不含任何規則——規則錯了要改領域層，不是這裡。
 */
public final class ConfigControllers {

    private ConfigControllers() {
    }

    /** 領域拋出的 IllegalArgumentException 一律回 400 並帶原文；重複鍵回 409。 */
    static ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return badRequest(e, "已存在相同代號的資料");
    }

    static ResponseEntity<Map<String, String>> badRequest(RuntimeException e, String duplicateMessage) {
        HttpStatus status = e instanceof DuplicateKeyException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        String message = e instanceof DuplicateKeyException ? duplicateMessage : e.getMessage();
        return ResponseEntity.status(status).body(Map.of("message", message == null ? "請求無效" : message));
    }

    /** Enum.valueOf 的錯誤訊息會把 Java 類別名漏給使用者，這裡換成列出合法值 */
    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + "不可為空");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " " + raw + " 不合法，可用值：" + java.util.Arrays.toString(type.getEnumConstants()));
        }
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
        public ResponseEntity<?> create(@RequestBody ModelRequest request) {
            try {
                List<MetricDefinition> metrics = request.metrics() == null ? List.of()
                        : request.metrics().stream()
                        .map(m -> MetricDefinition.of(m.key(), m.unit(), m.minValue(), m.maxValue()))
                        .toList();
                // DeviceModel.of 會擋下：沒有指標、指標重複、名稱空白
                DeviceModel model = DeviceModel.of(ModelCode.of(request.code()), request.manufacturer(),
                        request.displayName(), metrics);
                return ResponseEntity.status(HttpStatus.CREATED).body(ModelResponse.from(catalog.insertModel(model)));
            } catch (IllegalArgumentException | DuplicateKeyException e) {
                return badRequest(e);
            }
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
        public ResponseEntity<?> create(@RequestBody CabinetRequest request) {
            try {
                CabinetType type = parseEnum(CabinetType.class, request.type(), "機櫃類型");
                // 可容納機型由 cabinet_type_model 依類型決定，建立時先給佔位讓 Cabinet.of 的檢查通過
                Cabinet cabinet = Cabinet.of(null, request.name(), type, request.location(),
                        (short) request.slotCount(), Set.of(ModelCode.of("UNCONFIGURED")));
                return ResponseEntity.status(HttpStatus.CREATED).body(CabinetResponse.from(catalog.insertCabinet(cabinet)));
            } catch (IllegalArgumentException | DuplicateKeyException e) {
                return badRequest(e);
            }
        }

        public record CabinetRequest(String name, String type, String location, int slotCount) {
        }
    }

    /** id 就是 code：前端拿它當顯示名稱與 Device.cabinetId 的關聯鍵，數值 id 不外露。 */
    public record CabinetResponse(String id, String name, String type, String location, int slotCount) {
        static CabinetResponse from(Cabinet c) {
            return new CabinetResponse(c.code(), c.code(), c.type().name(),
                    c.location(), c.slotCount());
        }
    }

    // ── 告警規則 ──────────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/api/v1/alarm-rules")
    public static class AlarmRuleController {

        private final AlarmRuleRepository rules;
        private final CatalogRepository catalog;

        public AlarmRuleController(AlarmRuleRepository rules, CatalogRepository catalog) {
            this.rules = rules;
            this.catalog = catalog;
        }

        @GetMapping
        public List<RuleResponse> list() {
            return rules.findAllModelRules().stream().map(RuleResponse::from).toList();
        }

        /**
         * 建立前檢查門檻是否落在該指標的量程內。
         * 門檻設在量程外的規則永遠不會觸發、也不會報錯——它會安靜地存在直到事故發生。
         */
        @PostMapping
        public ResponseEntity<?> create(@RequestBody RuleRequest request) {
            try {
                ModelCode modelCode = ModelCode.of(request.modelCode());
                DeviceModel model = catalog.findModel(modelCode)
                        .orElseThrow(() -> new IllegalArgumentException("機型不存在：" + request.modelCode()));
                MetricKey metric = MetricKey.of(request.metric());
                MetricDefinition definition = model.metric(metric)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "機型 " + modelCode + " 沒有指標 " + metric));

                AlarmRule rule = AlarmRule.forModel(null, request.name(), modelCode, metric,
                        parseEnum(Comparison.class, request.comparison(), "比較方式"),
                        request.threshold(), request.secondaryValue(),
                        parseEnum(AlarmSeverity.class, request.severity(), "嚴重度"),
                        Duration.ofSeconds(request.durationSeconds()), request.enabled());

                if (!rule.isMeaningfulFor(definition)) {
                    throw new IllegalArgumentException("門檻 " + request.threshold() + " 超出指標 " + metric
                            + " 的量程 [" + definition.minValue() + ", " + definition.maxValue()
                            + "]，這條規則永遠不會觸發");
                }
                return ResponseEntity.status(HttpStatus.CREATED).body(RuleResponse.from(rules.insert(rule)));
            } catch (IllegalArgumentException | DuplicateKeyException e) {
                return badRequest(e);
            }
        }

        public record RuleRequest(String name, String modelCode, String metric, String comparison,
                                  double threshold, Double secondaryValue, int durationSeconds,
                                  String severity, boolean enabled) {
        }
    }

    public record RuleResponse(long id, String name, String modelCode, String metric, String comparison,
                               double threshold, Double secondaryValue, int durationSeconds,
                               String severity, boolean enabled) {
        static RuleResponse from(AlarmRule r) {
            return new RuleResponse(r.id(), r.name(), r.modelCode().map(ModelCode::value).orElse(null),
                    r.metric().value(), r.comparison().name(), r.threshold(),
                    r.secondaryValue().orElse(null), (int) r.sustainedFor().toSeconds(),
                    r.severity().name(), r.enabled());
        }
    }
}
