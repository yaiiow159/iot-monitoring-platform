package com.iotmon.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iotmon.infrastructure.live.LiveSessionRegistry;
import com.iotmon.infrastructure.live.LiveSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * {@code /ws/live} 的入站處理：只認得一種指令——訂閱。
 *
 * <p>推播本身不在這裡，這裡只維護「誰訂了什麼」。推播來源是 Kafka 消費端，
 * 兩者透過 {@link LiveSessionRegistry} 解耦；registry 看到的是 {@link LiveSubscriber}，
 * 這裡負責把 {@link WebSocketSession} 包成它。
 */
@Component
public class LiveWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);
    /** 單一連線的訂閱上限：擋下「訂閱全部一萬台」這種會把自己打爆的請求 */
    private static final int MAX_SUBSCRIPTIONS = 2000;

    private final LiveSessionRegistry registry;
    private final ObjectMapper json;

    public LiveWebSocketHandler(LiveSessionRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(new WebSocketSubscriber(session));
        log.debug("WebSocket 連線 {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = json.readTree(message.getPayload());
        if (!"subscribe".equals(root.path("action").asText())) {
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"只支援 subscribe\"}"));
            return;
        }
        Set<String> deviceIds = new HashSet<>();
        for (JsonNode id : root.path("deviceIds")) {
            deviceIds.add(id.asText());
        }
        if (deviceIds.size() > MAX_SUBSCRIPTIONS) {
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"單一連線最多訂閱 "
                    + MAX_SUBSCRIPTIONS + " 台裝置\"}"));
            return;
        }
        registry.subscribe(session.getId(), deviceIds);
        session.sendMessage(new TextMessage("{\"type\":\"subscribed\",\"count\":" + deviceIds.size() + "}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        registry.unregister(session.getId());
    }

    /** 把 WebSocketSession 包成 registry 認得的訂閱者 */
    private static final class WebSocketSubscriber implements LiveSubscriber {

        private final WebSocketSession session;

        WebSocketSubscriber(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public String id() {
            return session.getId();
        }

        @Override
        public boolean isOpen() {
            return session.isOpen();
        }

        @Override
        public void send(String payload) throws IOException {
            // sendMessage 在同一條 session 上不是執行緒安全的；推播來源有消費端執行緒
            // 與節流排程兩個，可能同時送，所以鎖住 session 本身
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        }

        @Override
        public void close() {
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // 已經送不出去了，關不掉也不會更糟
            }
        }
    }
}
