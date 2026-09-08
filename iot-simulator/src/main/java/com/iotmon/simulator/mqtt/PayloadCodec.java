package com.iotmon.simulator.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.iotmon.domain.device.DeviceId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 訊息序列化。用 Jackson 而不是手拼字串，是為了讓 payload 的形狀只有契約一個來源。
 */
@Component
public class PayloadCodec {

    // ObjectWriter 是不可變且執行緒安全的，先綁定型別可省下每則訊息的型別查找
    private final ObjectWriter telemetryWriter;
    private final ObjectWriter statusWriter;

    public PayloadCodec() {
        ObjectMapper mapper = new ObjectMapper();
        this.telemetryWriter = mapper.writerFor(TelemetryMessage.class);
        this.statusWriter = mapper.writerFor(StatusMessage.class);
    }

    public byte[] telemetry(DeviceId deviceId, long ts, Map<String, Double> metrics) {
        return write(telemetryWriter, new TelemetryMessage(deviceId.value(), ts, metrics), "遙測", deviceId);
    }

    public byte[] status(DeviceId deviceId, String state, long ts) {
        return write(statusWriter, new StatusMessage(deviceId.value(), state, ts), "狀態", deviceId);
    }

    /**
     * LWT 的內容在連線建立時就要定案，之後改不了。
     * 時間戳因此只能填註冊當下的時間；平台判讀離線時要以自己收到的時間為準。
     */
    public byte[] willPayload(DeviceId deviceId) {
        return status(deviceId, StatusMessage.OFFLINE, System.currentTimeMillis());
    }

    /**
     * 序列化失敗只可能是程式錯誤（record 的形狀跟 writer 不符），不是執行期狀況，
     * 所以包成 IllegalStateException 往上丟而不是回傳 null 讓呼叫端各自判斷。
     */
    private static byte[] write(ObjectWriter writer, Object message, String kind, DeviceId deviceId) {
        try {
            return writer.writeValueAsBytes(message);
        } catch (Exception e) {
            throw new IllegalStateException(kind + "序列化失敗：" + deviceId, e);
        }
    }
}
