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
 * 建立並持有所有 MQTT 連線。
 *
 * <h2>連線數與斷線偵測粒度的取捨</h2>
 *
 * <p>吞吐上，遙測根本不需要每台裝置一條連線：主題已經帶了 deviceId，一條連線就能替一整組裝置發佈，
 * 連線少代表 TCP buffer、Netty channel 與 CONNECT 風暴都跟著少。
 *
 * <p>但 <b>LWT 是綁在連線上的，不是綁在裝置上</b>。一條連線只能註冊一則遺言，
 * 所以一千台裝置共用十條連線時，拔掉一條連線只會產生 1 則 OFFLINE，而不是 100 則——
 * 而平台的斷線偵測正是建立在 LWT 上（見 README「斷線怎麼偵測」）。
 * 壓測若把這條路徑的流量砍成百分之一，就等於沒有測到它。
 *
 * <p>因此預設 {@code devices-per-connection = 1}：一台裝置一條連線、一組自己的遺言，
 * 與真實場域一致（docker-compose 也把 EMQX 的連線上限開到 50000 就是為此）。
 * 連線數不等於執行緒數——所有連線共用一組 Netty event loop，一萬條連線仍然只花掉數條執行緒。
 *
 * <p>把它調大是「用斷線偵測的解析度換訊息吞吐」的明確取捨：只有每組的第一台
 * （{@link MqttConnection#lwtOwner()}）會被 broker 宣告離線，其餘要靠平台的心跳逾時補網。
 * 想壓到每秒五萬則以上、而測試重點又不在斷線偵測時才該這麼做。
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
