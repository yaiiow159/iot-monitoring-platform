package com.iotmon.simulator.mqtt;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import com.iotmon.domain.device.DeviceId;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一條 MQTT 連線。可能只承載一台裝置，也可能承載一組裝置——差別在斷線偵測的粒度，
 * 見 {@link MqttConnectionPool}。
 */
public final class MqttConnection {

    private final String clientId;
    private final DeviceId lwtOwner;
    private final Mqtt5AsyncClient client;
    private final int keepAliveSeconds;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    MqttConnection(String clientId, DeviceId lwtOwner, Mqtt5AsyncClient client, int keepAliveSeconds) {
        this.clientId = clientId;
        this.lwtOwner = lwtOwner;
        this.client = client;
        this.keepAliveSeconds = keepAliveSeconds;
    }

    /**
     * cleanStart ＋ 不保留 session：模擬器重啟時不該繼承上一輪的訂閱與未確認訊息，
     * 否則壓測的第一分鐘會混進上一次執行的殘留流量。
     */
    public CompletableFuture<Void> connect() {
        return client.connectWith()
                .cleanStart(true)
                .noSessionExpiry()
                .keepAlive(keepAliveSeconds)
                .send()
                .thenAccept(ack -> connected.set(true));
    }

    public CompletableFuture<Mqtt5PublishResult> publish(String topic, byte[] payload, boolean retain) {
        return client.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send();
    }

    /**
     * 斷線但要求 broker 發出遺言。一般的 DISCONNECT 會讓 broker 丟棄 will，
     * 那樣模擬出來的「斷線」平台永遠收不到，OFFLINE_FLAPPING 也就驗不到 LWT 這條路徑。
     */
    public CompletableFuture<Void> dropWithWill() {
        connected.set(false);
        return client.disconnectWith()
                .reasonCode(Mqtt5DisconnectReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
                .send();
    }

    /** 正常關機：裝置已自行送出 OFFLINE，這裡不需要 broker 再補一則遺言。 */
    public CompletableFuture<Void> closeNormally() {
        connected.set(false);
        return client.disconnect();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String clientId() {
        return clientId;
    }

    /** 這條連線的遺言掛在哪台裝置身上。連線異常中斷時，只有它會被 broker 宣告離線。 */
    public DeviceId lwtOwner() {
        return lwtOwner;
    }
}
