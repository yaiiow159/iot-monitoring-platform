package com.iotmon.api.ws;

import com.iotmon.api.security.WsTokenHandshakeInterceptor;
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
    private final WsTokenHandshakeInterceptor auth;

    public WebSocketConfig(LiveWebSocketHandler handler, WsTokenHandshakeInterceptor auth) {
        this.handler = handler;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 握手驗 token：REST 鎖了、推播不鎖，等於遙測可以匿名看
        registry.addHandler(handler, "/ws/live").addInterceptors(auth).setAllowedOriginPatterns("*");
    }
}
