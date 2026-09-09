package com.iotmon.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MQTT → Kafka 橋接：收訊息、確認能解析、丟進 Kafka，其餘驗證留給消費端（ADR-0003）。
 * 多條連線共享訂閱、回呼離開事件迴圈的理由見 ADR-0007：單一連線在每秒一萬則時會被 broker 踢掉。
 */
@Component
public class MqttBridge {

    private static final Logger log = LoggerFactory.getLogger(MqttBridge.class);

    public static final String TOPIC_TELEMETRY = "iot.telemetry";
    public static final String TOPIC_DEVICE_STATUS = "iot.device-status";
    private static final String SHARE_GROUP = "$share/iot-bridge/";

    private final ObjectMapper json;
    private final KafkaTemplate<String, Object> kafka;
    private final Counter forwarded;
    private final Counter malformed;
    private final AtomicInteger connected = new AtomicInteger();

    private final String host;
    private final int port;
    private final String telemetryTopic;
    private final String statusTopic;
    private final int connections;

    private final List<Mqtt5AsyncClient> clients = new ArrayList<>();
    private ExecutorService executor;

    public MqttBridge(ObjectMapper json,
                      KafkaTemplate<String, Object> kafka,
                      MeterRegistry registry,
                      @Value("${iot.mqtt.host}") String host,
                      @Value("${iot.mqtt.port}") int port,
                      @Value("${iot.mqtt.telemetry-topic}") String telemetryTopic,
                      @Value("${iot.mqtt.status-topic}") String statusTopic,
                      @Value("${iot.mqtt.bridge-connections:4}") int connections) {
        this.json = json;
        this.kafka = kafka;
        this.host = host;
        this.port = port;
        this.telemetryTopic = telemetryTopic;
        this.statusTopic = statusTopic;
        this.connections = Math.max(1, connections);
        this.forwarded = Counter.builder("mqtt.bridge.forwarded")
                .description("成功轉送到 Kafka 的訊息數").register(registry);
        this.malformed = Counter.builder("mqtt.bridge.malformed")
                .description("無法解析而丟棄的訊息數").register(registry);
        Gauge.builder("mqtt.bridge.connections", connected, AtomicInteger::get)
                .description("目前連上 broker 的橋接連線數").register(registry);
    }

    @PostConstruct
    public void start() {
        executor = Executors.newFixedThreadPool(connections * 2, r -> {
            Thread t = new Thread(r, "mqtt-bridge-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < connections; i++) {
            clients.add(open(i));
        }
    }

    private Mqtt5AsyncClient open(int index) {
        // client id 加上隨機字尾：同一個 id 重複連線時 broker 會踢掉前一條，
        // 部署滾動更新期間新舊實例並存會互踢，訊息就在那個空窗流失。
        String id = "iot-bridge-" + index + "-" + UUID.randomUUID().toString().substring(0, 8);
        // listener 需要 client 本身才能下訂閱，但 client 要 build 完才存在：用一個 holder 接起來
        AtomicReference<Mqtt5AsyncClient> self = new AtomicReference<>();
        Mqtt5AsyncClient client = Mqtt5Client.builder()
                .identifier(id)
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                // 每次收到 CONNACK 都會觸發，包含自動重連；cleanStart=true 的會話沒有訂閱可以恢復，
                // 所以訂閱放在這裡，而不是只在第一次 connect 的 whenComplete
                .addConnectedListener(ctx -> {
                    connected.incrementAndGet();
                    Mqtt5AsyncClient c = self.get();
                    if (c != null) {
                        subscribe(c, index);
                    }
                })
                .addDisconnectedListener(ctx -> {
                    connected.decrementAndGet();
                    log.warn("橋接連線 {} 中斷：{}", index, ctx.getCause().getMessage());
                })
                .buildAsync();
        self.set(client);

        // 回呼在自己的執行緒池上跑，事件迴圈只收封包、回 PUBACK
        client.publishes(MqttGlobalPublishFilter.SUBSCRIBED, this::forward, executor);

        connect(client, index);
        return client;
    }

    private void connect(Mqtt5AsyncClient client, int index) {
        client.connectWith()
                .cleanStart(true)
                .sessionExpiryInterval(0)
                .keepAlive(30)
                .send()
                .whenComplete((ack, error) -> {
                    if (error != null) {
                        log.error("橋接連線 {} 連線失敗：{}", index, error.getMessage());
                    } else {
                        log.info("橋接連線 {} 已連上 {}:{}", index, host, port);
                    }
                });
    }

    private void subscribe(Mqtt5AsyncClient client, int index) {
        // QoS 1：至少一次。遙測允許重複（聚合時被 avg 吸收），不允許遺失。
        client.subscribeWith().topicFilter(SHARE_GROUP + telemetryTopic).qos(MqttQos.AT_LEAST_ONCE).send()
                .whenComplete((ack, error) -> {
                    if (error != null) {
                        log.error("橋接連線 {} 訂閱失敗 {}：{}", index, telemetryTopic, error.getMessage());
                    } else {
                        log.info("橋接連線 {} 已訂閱 {}", index, SHARE_GROUP + telemetryTopic);
                    }
                });
        client.subscribeWith().topicFilter(SHARE_GROUP + statusTopic).qos(MqttQos.AT_LEAST_ONCE).send();
    }

    private void forward(Mqtt5Publish publish) {
        String topic = publish.getTopic().toString();
        try {
            byte[] payload = publish.getPayloadAsBytes();
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
            log.debug("丟棄無法解析的訊息 topic={} 原因={}", topic,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        for (Mqtt5AsyncClient client : clients) {
            client.disconnect();
        }
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MQTT 橋接已中斷 {} 條連線", clients.size());
    }
}
