package com.iotmon.simulator.mqtt;

import com.iotmon.domain.device.DeviceId;

/**
 * 主題組裝。字串樣板集中在這裡，避免各處各拼一次而拼錯——
 * 主題拼錯不會有錯誤訊息，只會安靜地沒有人收到。
 */
public final class MqttTopics {

    private MqttTopics() {
    }

    /** {@code iot/telemetry/{modelCode}/{deviceId}}，見 docs/contracts.md。 */
    public static String telemetry(String modelCode, DeviceId deviceId) {
        return "iot/telemetry/" + modelCode + "/" + deviceId.value();
    }

    /** {@code iot/status/{deviceId}}，上線宣告與 LWT 遺言共用。 */
    public static String status(DeviceId deviceId) {
        return "iot/status/" + deviceId.value();
    }
}
