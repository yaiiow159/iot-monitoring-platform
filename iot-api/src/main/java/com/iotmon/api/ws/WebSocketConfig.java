package com.iotmon.api.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 用原生 WebSocket 而不是 STOMP：訊息只有三種、前端只要一個訂閱指令，
 * STOMP 的 broker relay 與 destination 語意在這裡全是多餘的層。
 *
 * <p>allowedOrigins 開放是為了本機開發（前端在 3100，dev proxy 會轉發）；
 * 正式環境要收斂到實際網域，這是部署設定不是程式碼決定。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveWebSocketHandler handler;

    public WebSocketConfig(LiveWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/live").setAllowedOriginPatterns("*");
    }
}
