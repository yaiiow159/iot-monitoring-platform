package com.iotmon.api.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手驗 token。瀏覽器的 WebSocket API 不能設 Authorization 標頭，
 * 所以 token 走查詢參數 {@code ?token=}；握手完成後這個 URL 不會再出現在任何日誌以外的地方。
 */
@Component
public class WsTokenHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwt;

    public WsTokenHandshakeInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        return jwt.verify(token).map(p -> {
            attributes.put("user", p);
            return true;
        }).orElseGet(() -> {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        });
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
