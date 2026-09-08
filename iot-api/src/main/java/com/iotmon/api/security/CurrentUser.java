package com.iotmon.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** 從 SecurityContext 取目前登入者；沒登入為 empty。集中一處免得每個控制器各自轉型。 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<JwtService.Principal> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtService.Principal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }

    public static String usernameOr(String fallback) {
        return get().map(JwtService.Principal::username).orElse(fallback);
    }
}
