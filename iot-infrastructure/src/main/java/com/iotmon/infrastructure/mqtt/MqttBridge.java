package com.iotmon.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MQTT → Kafka 橋接。
 *
 * <p>整條路徑上最不能塞車的一段：它是唯一與裝置直接相連的環節，
 * 這裡一慢，MQTT broker 的佇列就會堆積，最終導致裝置端被斷線。
 *
 * <p>因此它只做三件事：收訊息、確認格式能解析、丟進 Kafka。
 * 驗證裝置是否註冊、指標是否合法、讀數是否超出量程——全部留給消費端，
 * 那些工作可以慢慢做，這裡不行（見 ADR-0003）。
 */
@Component
public class MqttBridge {

    private static final Logger log = LoggerFactory.getLogger(MqttBridge.class);

    public static final String TOPIC_TELEMETRY = "iot.telemetry";
    public static final String TOPIC_DEVICE_STATUS = "iot.device-status";

    private final ObjectMapper json;
    private final KafkaTemplate<String, Object> kafka;
    private final Counter forwarded;
    private final Counter malformed;

    private final String host;
    private final int port;
    private final String telemetryTopic;
    private final String statusTopic;

    private Mqtt5AsyncClient client;

    public MqttBridge(ObjectMapper json,
                      KafkaTemplate<String, Object> kafka,
                      MeterRegistry registry,
                      @Value("${iot.mqtt.host}") String host,
                      @Value("${iot.mqtt.port}") int port,
                      @Value("${iot.mqtt.telemetry-topic}") String telemetryTopic,
                      @Value("${iot.mqtt.status-topic}") String statusTopic) {
        this.json = json;
        this.kafka = kafka;
        this.host = host;
        this.port = port;
        this.telemetryTopic = telemetryTopic;
        this.statusTopic = statusTopic;
        this.forwarded = Counter.builder("mqtt.bridge.forwarded")
                .description("成功轉送到 Kafka 的訊息數").register(registry);
        this.malformed = Counter.builder("mqtt.bridge.malformed")
                .description("無法解析而丟棄的訊息數").register(registry);
    }

    @PostConstruct
    public void start() {
        // client id 加上隨機字尾：同一個 id 重複連線時 broker 會踢掉前一條，
        // 部署滾動更新期間新舊實例並存會互踢，訊息就在那個空窗流失。
        client = Mqtt5Client.builder()
                .identifier("iot-bridge-" + UUID.randomUUID().toString().substring(0, 8))
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .buildAsync();

        client.connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(300)
                .send()
                .whenComplete((ack, error) -> {
                    if (error != null) {
                        log.error("MQTT 連線失敗：{}", error.getMessage());
                        return;
                    }
                    log.info("MQTT 已連線 {}:{}", host, port);
                    subscribe();
                });
    }

    private void subscribe() {
        // QoS 1：至少一次。遙測允許重複（聚合時被 avg 吸收），不允許遺失。
        client.subscribeWith().topicFilter(telemetryTopic).qos(MqttQos.AT_LEAST_ONCE).send();
        client.subscribeWith().topicFilter(statusTopic).qos(MqttQos.AT_LEAST_ONCE).send();

        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, publish -> {
            String topic = publish.getTopic().toString();
            String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            try {
                if (topic.startsWith("iot/telemetry/")) {
                    TelemetryEnvelope envelope = json.readValue(payload, TelemetryEnvelope.class);
                    // 分區鍵用 deviceId：同一台裝置的訊息落在同一分區，順序才有保證
                    kafka.send(TOPIC_TELEMETRY, envelope.deviceId(), envelope);
                } else if (topic.startsWith("iot/status/")) {
                    StatusEnvelope status = json.readValue(payload, StatusEnvelope.class);
                    kafka.send(TOPIC_DEVICE_STATUS, status.deviceId(), status);
                }
                forwarded.increment();
            } catch (Exception e) {
                // 單一則訊息解析失敗不該影響其餘訊息。計數器讓這件事可觀測，
                // 否則格式不符的裝置會安靜地什麼都沒送到。
                malformed.increment();
                log.debug("丟棄無法解析的訊息 topic={} 原因={}", topic, e.getMessage());
            }
        });

        log.info("已訂閱 {} 與 {}", telemetryTopic, statusTopic);
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            client.disconnect();
            log.info("MQTT 已中斷連線");
        }
    }
}
