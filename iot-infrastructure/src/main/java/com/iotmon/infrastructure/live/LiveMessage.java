package com.iotmon.infrastructure.live;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 推播的三種訊息，形狀依 contracts.md。
 *
 * <p>用 sealed interface 而不是一個帶 type 欄位的萬用類別：
 * 新增訊息種類時，序列化與前端型別都能從編譯器得到提醒，
 * 而不是在某個 switch 的 default 分支安靜地被丟掉。
 */
public sealed interface LiveMessage permits LiveMessage.Telemetry, LiveMessage.Status, LiveMessage.Alarm {

    /**
     * 明確標成 JSON 屬性：它不是 record 元件、也不叫 getType，
     * Jackson 預設會直接略過，推出去的訊息就沒有 type，前端無法分流。
     */
    @JsonProperty("type")
    String type();

    String deviceId();

    /** 遙測。每台裝置每秒最多一則，取該秒最後一筆（見 {@link TelemetryThrottle}）。 */
    record Telemetry(String deviceId, Map<String, Double> metrics, long ts) implements LiveMessage {
        @Override
        @JsonProperty("type")
        public String type() {
            return "telemetry";
        }
    }

    /** 上下線。不節流：低頻但每一則都重要。 */
    record Status(String deviceId, String state, long ts) implements LiveMessage {
        @Override
        @JsonProperty("type")
        public String type() {
            return "status";
        }
    }

    /**
     * 告警觸發或解除。不節流。
     *
     * @param ancestorIds 監控樹上從根到該裝置節點的 id，由上到下；裝置不在樹上時為空。
     *                    前端據此更新整條祖先鏈的 rollup，而不是只更新葉節點。
     */
    record Alarm(long alarmId, String deviceId, String severity, String state,
                 List<Long> ancestorIds, long ts) implements LiveMessage {
        @Override
        @JsonProperty("type")
        public String type() {
            return "alarm";
        }
    }
}
