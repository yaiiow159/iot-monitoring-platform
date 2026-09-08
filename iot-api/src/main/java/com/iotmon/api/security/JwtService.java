package com.iotmon.api.security;

import com.iotmon.domain.auth.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * HS256 的簽發與驗證。
 *
 * <p>選 JWT 而不是 session：API 無狀態、WebSocket 握手也能用同一個 token，
 * 不必為兩種連線各做一套認證。代價是登出前 token 都有效——內部系統 12 小時的效期可以接受。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${iot.auth.secret}") String secret,
                      @Value("${iot.auth.token-ttl-hours:12}") long ttlHours) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("iot.auth.secret 至少 32 字元，HS256 的金鑰短於 256 bit 不安全");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record Principal(String username, Role role, String displayName) {
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public Issued issue(Principal principal) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        String token = Jwts.builder()
                .subject(principal.username())
                .claim("role", principal.role().name())
                .claim("name", principal.displayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, exp);
    }

    /** 簽章、效期任一不對就是 empty；不區分原因，免得回應變成攻擊者的探針 */
    public Optional<Principal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new Principal(claims.getSubject(),
                    Role.valueOf(claims.get("role", String.class)), claims.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
