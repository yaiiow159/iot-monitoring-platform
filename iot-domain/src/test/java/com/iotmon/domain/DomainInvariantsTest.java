package com.iotmon.domain;

import com.iotmon.domain.alarm.Alarm;
import com.iotmon.domain.alarm.AlarmRule;
import com.iotmon.domain.alarm.AlarmSeverity;
import com.iotmon.domain.alarm.Comparison;
import com.iotmon.domain.cabinet.Cabinet;
import com.iotmon.domain.cabinet.CabinetType;
import com.iotmon.domain.device.Device;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.domain.device.DeviceStatus;
import com.iotmon.domain.model.DeviceModel;
import com.iotmon.domain.model.MetricDefinition;
import com.iotmon.domain.model.MetricKey;
import com.iotmon.domain.model.ModelCode;
import com.iotmon.domain.telemetry.TelemetryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 領域不變條件。
 *
 * <p>這些測試對應的都是「違反了會在幾個月後才被發現」的規則——
 * 狀態卡在錯誤的值、告警永遠不觸發、指標安靜地分裂成兩個序列。
 * 這類缺陷不會拋例外，所以只能靠測試守住。
 */
class DomainInvariantsTest {

    private static final ModelCode TH100 = ModelCode.of("TH-100");

    private static DeviceModel thermoModel() {
        return DeviceModel.of(TH100, "Acme", "溫濕度感測器", List.of(
                MetricDefinition.of("temperature", "°C", -20, 80),
                MetricDefinition.of("humidity", "%", 0, 100)
        ));
    }

    @Nested
    @DisplayName("指標代號")
    class MetricKeyRules {

        @Test
        @DisplayName("大小寫與空白會被正規化，避免同一個指標分裂成兩個序列")
        void normalisesCaseAndWhitespace() {
            assertEquals(MetricKey.of("temperature"), MetricKey.of("  Temperature  "));
        }

        @Test
        @DisplayName("含斜線的代號會被拒絕——它會改變 MQTT 主題的語意")
        void rejectsTopicSeparators() {
            assertThrows(IllegalArgumentException.class, () -> MetricKey.of("temp/inner"));
            assertThrows(IllegalArgumentException.class, () -> MetricKey.of("temp+inner"));
        }
    }

    @Nested
    @DisplayName("裝置狀態轉移")
    class StatusTransitions {

        @Test
        @DisplayName("離線的裝置不能直接變成訊號不穩：沒有連線就沒有連線品質可言")
        void offlineCannotBecomeDegraded() {
            assertFalse(DeviceStatus.OFFLINE.canTransitionTo(DeviceStatus.DEGRADED));
            assertTrue(DeviceStatus.OFFLINE.canTransitionTo(DeviceStatus.ONLINE));
        }

        @Test
        @DisplayName("非法轉移會拋例外，不會安靜地把狀態改成錯的值")
        void illegalTransitionThrows() {
            Device device = Device.register(1L, DeviceId.of("DEV-001"), "SN-001",
                    thermoModel(), null, null);
            device.transitionTo(DeviceStatus.ONLINE, Instant.now());
            device.transitionTo(DeviceStatus.OFFLINE, Instant.now());

            assertThrows(IllegalStateException.class,
                    () -> device.transitionTo(DeviceStatus.DEGRADED, Instant.now()));
        }

        @Test
        @DisplayName("重送相同狀態回傳 false，呼叫端據此避免重複推播")
        void repeatedStatusIsNotAChange() {
            Device device = Device.register(1L, DeviceId.of("DEV-001"), "SN-001",
                    thermoModel(), null, null);
            assertTrue(device.transitionTo(DeviceStatus.ONLINE, Instant.now()));
            assertFalse(device.transitionTo(DeviceStatus.ONLINE, Instant.now()));
        }
    }

    @Nested
    @DisplayName("遙測驗證")
    class TelemetryValidation {

        @Test
        @DisplayName("機型沒定義的指標會被拒絕，不會在字典表裡多出沒人認得的 metric_id")
        void rejectsUndeclaredMetric() {
            Device device = Device.register(1L, DeviceId.of("DEV-001"), "SN-001",
                    thermoModel(), null, null);
            TelemetryPoint rogue = new TelemetryPoint(
                    DeviceId.of("DEV-001"), MetricKey.of("voltage"), 220, Instant.now());

            assertThrows(IllegalArgumentException.class, () -> device.acceptTelemetry(rogue));
        }

        @Test
        @DisplayName("超出量程的讀數會被接收但標記為異常——那是感測器故障，不是業務高低值")
        void outOfRangeIsAcceptedButFlagged() {
            Device device = Device.register(1L, DeviceId.of("DEV-001"), "SN-001",
                    thermoModel(), null, null);

            assertTrue(device.acceptTelemetry(new TelemetryPoint(
                    DeviceId.of("DEV-001"), MetricKey.of("temperature"), 25, Instant.now())));
            assertFalse(device.acceptTelemetry(new TelemetryPoint(
                    DeviceId.of("DEV-001"), MetricKey.of("temperature"), 200, Instant.now())));
        }

        @Test
        @DisplayName("NaN 與無限大在入口就被擋下，不會汙染整個聚合層")
        void rejectsNonFiniteValues() {
            DeviceId id = DeviceId.of("DEV-001");
            MetricKey temp = MetricKey.of("temperature");
            assertThrows(IllegalArgumentException.class,
                    () -> new TelemetryPoint(id, temp, Double.NaN, Instant.now()));
            assertThrows(IllegalArgumentException.class,
                    () -> new TelemetryPoint(id, temp, Double.POSITIVE_INFINITY, Instant.now()));
        }
    }

    @Nested
    @DisplayName("告警規則")
    class AlarmRules {

        @Test
        @DisplayName("門檻設在量程外的規則會被判定為無意義——它永遠不會觸發且不會報錯")
        void thresholdOutsideRangeIsMeaningless() {
            MetricDefinition temperature = MetricDefinition.of("temperature", "°C", -20, 80);
            AlarmRule impossible = AlarmRule.forModel(1L, "溫度過高", TH100,
                    MetricKey.of("temperature"), Comparison.GT, 500, null,
                    AlarmSeverity.CRITICAL, Duration.ZERO, true);

            assertFalse(impossible.isMeaningfulFor(temperature));
        }

        @Test
        @DisplayName("OUT_OF_RANGE 用單一規則表達雙向異常，避免一次異常產生兩則告警")
        void outOfRangeCoversBothDirections() {
            AlarmRule rule = AlarmRule.forModel(1L, "電壓異常", TH100,
                    MetricKey.of("temperature"), Comparison.OUT_OF_RANGE, 200, 240.0,
                    AlarmSeverity.WARNING, Duration.ZERO, true);

            assertTrue(rule.isBreachedBy(180));
            assertTrue(rule.isBreachedBy(260));
            assertFalse(rule.isBreachedBy(220));
        }

        @Test
        @DisplayName("停用的規則不會觸發")
        void disabledRuleNeverFires() {
            AlarmRule disabled = AlarmRule.forModel(1L, "溫度過高", TH100,
                    MetricKey.of("temperature"), Comparison.GT, 30, null,
                    AlarmSeverity.WARNING, Duration.ZERO, false);

            assertFalse(disabled.isBreachedBy(999));
        }

        @Test
        @DisplayName("規則必須綁機型或裝置其中之一，不可兩者皆是")
        void ruleScopeIsExclusive() {
            assertThrows(NullPointerException.class, () -> AlarmRule.forModel(
                    1L, "x", null, MetricKey.of("temperature"), Comparison.GT, 1, null,
                    AlarmSeverity.INFO, Duration.ZERO, true));
        }
    }

    @Nested
    @DisplayName("告警生命週期")
    class AlarmLifecycle {

        @Test
        @DisplayName("重複解除不拋例外，因為解除的觸發來源有多個且可能同時發生")
        void repeatedResolveIsIdempotent() {
            Instant now = Instant.now();
            Alarm alarm = Alarm.fire(1L, DeviceId.of("DEV-001"), 7L,
                    AlarmSeverity.CRITICAL, now, 95.0);

            assertTrue(alarm.resolve(now.plusSeconds(10)));
            assertFalse(alarm.resolve(now.plusSeconds(20)));
        }

        @Test
        @DisplayName("解除時間不可早於觸發時間")
        void resolveBeforeFireIsRejected() {
            Instant now = Instant.now();
            Alarm alarm = Alarm.fire(1L, DeviceId.of("DEV-001"), 7L,
                    AlarmSeverity.CRITICAL, now, 95.0);

            assertThrows(IllegalArgumentException.class, () -> alarm.resolve(now.minusSeconds(1)));
        }
    }

    @Nested
    @DisplayName("機櫃容納規則")
    class CabinetAcceptance {

        @Test
        @DisplayName("配電櫃不接受感測器機型，設定錯誤在建立當下就被擋下")
        void rejectsIncompatibleModel() {
            Cabinet powerCabinet = Cabinet.of(1L, "PWR-A01", CabinetType.POWER, "機房 A",
                    (short) 20, Set.of(ModelCode.of("PWR-3P")));

            assertNotNull(powerCabinet.rejectReasonFor(TH100, (short) 3));
            assertNull(powerCabinet.rejectReasonFor(ModelCode.of("PWR-3P"), (short) 3));
        }

        @Test
        @DisplayName("超出範圍的槽位會說明合法區間，而不是只回一個 false")
        void rejectReasonExplainsSlotRange() {
            Cabinet cabinet = Cabinet.of(1L, "PWR-A01", CabinetType.POWER, "機房 A",
                    (short) 20, Set.of(ModelCode.of("PWR-3P")));

            String reason = cabinet.rejectReasonFor(ModelCode.of("PWR-3P"), (short) 99);
            assertNotNull(reason);
            assertTrue(reason.contains("1~20"), "訊息應說明合法槽位區間：" + reason);
        }
    }

    @Nested
    @DisplayName("機型定義")
    class ModelDefinition {

        @Test
        @DisplayName("不回報任何指標的機型會被拒絕——它會產生一台永遠沒有資料的裝置")
        void modelMustDeclareAtLeastOneMetric() {
            assertThrows(IllegalArgumentException.class,
                    () -> DeviceModel.of(TH100, "Acme", "空機型", List.of()));
        }

        @Test
        @DisplayName("指標代號重複會被拒絕")
        void rejectsDuplicateMetrics() {
            assertThrows(IllegalArgumentException.class, () -> DeviceModel.of(
                    TH100, "Acme", "重複", List.of(
                            MetricDefinition.of("temperature", "°C", -20, 80),
                            MetricDefinition.of("temperature", "K", 250, 350))));
        }
    }
}
