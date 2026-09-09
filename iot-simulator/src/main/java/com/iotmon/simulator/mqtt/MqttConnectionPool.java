package com.iotmon.simulator.mqtt;

import com.hivemq.client.mqtt.MqttClientExecutorConfig;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.iotmon.domain.device.DeviceId;
import com.iotmon.simulator.config.SimulatorProperties;
import com.iotmon.simulator.fault.FaultType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 建立並持有所有 MQTT 連線。預設一台裝置一條連線：LWT 綁在連線上不是裝置上，
 * 一千台共用十條連線時拔掉一條只會產生 1 則 OFFLINE 而不是 100 則，斷線偵測就等於沒測到。
 * 調大 devices-per-connection 是拿斷線偵測的解析度換吞吐，只有測試重點不在斷線時才該這麼做。
 * 連線數不等於執行緒數，一萬條連線共用一組 Netty event loop。
 */
@Component
public class MqttConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionPool.class);

    private final SimulatorProperties properties;
    private final PayloadCodec codec;
    private final List<MqttConnection> connections = new ArrayList<>();

    public MqttConnectionPool(SimulatorProperties properties, PayloadCodec codec) {
        this.properties = properties;
        this.codec = codec;
    }

    /**
     * 依裝置清單配出連線，回傳「裝置索引 → 連線」的對應。
     *
     * @param faults 故障指派。間歇斷線的裝置一律獨佔連線——它斷線時會把整條連線拉掉，
     *               跟它共用連線的鄰居會被無辜牽連，那不是要模擬的故障。
     */
    public MqttConnection[] allocate(List<DeviceId> deviceIds, FaultType[] faults) {
        SimulatorProperties.Mqtt mqtt = properties.mqtt();
        MqttClientExecutorConfig executor = MqttClientExecutorConfig.builder()
                .nettyThreads(mqtt.resolvedNettyThreads())
                .build();

        MqttConnection[] byDevice = new MqttConnection[deviceIds.size()];
        MqttConnection current = null;
        int inCurrent = 0;

        for (int i = 0; i < deviceIds.size(); i++) {
            DeviceId deviceId = deviceIds.get(i);
            boolean dedicated = faults[i] == FaultType.OFFLINE_FLAPPING;

            if (dedicated) {
                byDevice[i] = newConnection(deviceId, executor, mqtt, true);
                continue;
            }
            if (current == null || inCurrent >= mqtt.devicesPerConnection()) {
                current = newConnection(deviceId, executor, mqtt, false);
                inCurrent = 0;
            }
            byDevice[i] = current;
            inCurrent++;
        }

        log.info("配出 {} 條 MQTT 連線給 {} 台裝置（每連線 {} 台，間歇斷線者獨佔）",
                connections.size(), deviceIds.size(), mqtt.devicesPerConnection());

        // 沒有自己遺言的裝置，異常斷線時平台只能靠心跳逾時發現。把數字說出來，免得壓測結論寫錯
        long withoutOwnWill = 0;
        for (int i = 0; i < deviceIds.size(); i++) {
            if (!byDevice[i].lwtOwner().equals(deviceIds.get(i))) {
                withoutOwnWill++;
            }
        }
        if (withoutOwnWill > 0) {
            log.warn("{} 台裝置沒有自己的 LWT，異常斷線只會由所屬連線的代表發出一則遺言", withoutOwnWill);
        }
        return byDevice;
    }

    private MqttConnection newConnection(DeviceId lwtOwner, MqttClientExecutorConfig executor,
                                         SimulatorProperties.Mqtt mqtt, boolean flapping) {
        String clientId = mqtt.clientIdPrefix() + "-" + lwtOwner.value();
        Mqtt5ClientBuilder builder = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(mqtt.host())
                .serverPort(mqtt.port())
                .executorConfig(executor)
                .willPublish()
                .topic(MqttTopics.status(lwtOwner))
                .payload(codec.willPayload(lwtOwner))
                .qos(MqttQos.AT_LEAST_ONCE)
                // retain 讓平台重啟後訂閱就能立刻看到這台裝置是離線的，不必等下次狀態變化
                .retain(true)
                .applyWillPublish();

        // 間歇斷線的裝置自己管理連線週期；自動重連會在我們刻意拔線後立刻接回來，那就模擬不出斷線
        if (!flapping) {
            builder = builder.automaticReconnectWithDefaultConfig();
        }

        MqttConnection connection = new MqttConnection(
                clientId, lwtOwner, builder.buildAsync(), mqtt.keepAliveSeconds());
        connections.add(connection);
        return connection;
    }

    public List<MqttConnection> connections() {
        return List.copyOf(connections);
    }
}
