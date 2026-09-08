package com.iotmon.api.security;

import com.iotmon.infrastructure.auth.AuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 稽核：每一個會改東西的請求（非 GET 的 /api/v1/**）都記下誰、對什麼、做了什麼、結果如何。
 *
 * <p>用 filter 而不是在每個控制器裡呼叫 record()：控制器有十幾個寫入方法，
 * 漏掉一個就是稽核缺口，而 filter 只有一處。代價是記錄的是 HTTP 語意（方法、路徑、請求體）
 * 而不是業務語意（「把 TH-100 的溫度門檻從 60 改成 65」）——內部系統先求有再求好。
 *
 * <p>被拒絕的請求（400／403／409）也記：「誰試圖做什麼但被擋下」跟「誰做了什麼」一樣重要。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // 要包在 Security 外面，被 401／403 擋下的請求才記得到
public class AuditFilter extends OncePerRequestFilter {

    /** 請求體最多記這麼多字元，避免有人 POST 一個 10MB 的機型定義把稽核表撐爆 */
    private static final int MAX_BODY_CHARS = 2000;

    private final AuditLogRepository audit;
    private final JwtService jwt;

    public AuditFilter(AuditLogRepository audit, JwtService jwt) {
        this.audit = audit;
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equals(request.getMethod()) || !path.startsWith("/api/v1/")
                || path.startsWith("/api/v1/auth/"); // 登入由 AuthController 自己記，這裡記會把密碼寫進稽核表
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(req, res);
        } finally {
            record(req, res);
            res.copyBodyToResponse();
        }
    }

    private void record(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res) {
        int status = res.getStatus();
        String path = req.getRequestURI();
        String[] segments = path.substring("/api/v1/".length()).split("/");
        String targetType = segments.length > 0 ? segments[0] : "unknown";
        String targetId = segments.length > 1 ? segments[1] : null;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("method", req.getMethod());
        detail.put("path", path);
        detail.put("status", status);
        String body = new String(req.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (!body.isBlank()) {
            detail.put("body", body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) + "…" : body);
        }
        if (status >= 400) {
            String reply = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (!reply.isBlank()) {
                detail.put("reply", reply.length() > 500 ? reply.substring(0, 500) : reply);
            }
        }

        String outcome = status < 300 ? "OK" : status == 401 || status == 403 ? "DENIED"
                : status < 500 ? "REJECTED" : "FAILED";
        // 這個 filter 在 Security 外層，SecurityContext 在這裡已被清掉，操作者要自己從 token 解
        String header = req.getHeader("Authorization");
        String actor = header != null && header.startsWith("Bearer ")
                ? jwt.verify(header.substring(7)).map(JwtService.Principal::username).orElse("anonymous")
                : "anonymous";
        audit.record(actor, req.getMethod() + " " + path, targetType, targetId, outcome, detail);
    }
}
