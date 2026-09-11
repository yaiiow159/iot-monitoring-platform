package com.iotmon.api.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * 授權規則集中在這一處，控制器不檢查角色：漏掉一個註解就是一個越權漏洞，集中的清單一眼能審。
 * 讀：任何登入者；機型、機櫃、規則、使用者、稽核：ADMIN；註冊裝置、監控樹：ADMIN 或 OPERATOR。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // /error 是容器的錯誤轉送目標：不放行的話，任何 500 都會變成 401，前端會誤判成登入過期
                        .requestMatchers("/api/v1/auth/login", "/actuator/**", "/ws/**", "/error",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/audit/**", "/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/v1/models/**", "/api/v1/cabinets/**", "/api/v1/alarm-rules/**",
                                "/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/devices/**", "/api/v1/tree/**",
                                "/api/v1/alarms/**").hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> reject(res, HttpServletResponse.SC_UNAUTHORIZED, "未登入或登入已過期"))
                        .accessDeniedHandler((req, res, ex) -> reject(res, HttpServletResponse.SC_FORBIDDEN, "權限不足")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 錯誤形狀與其他端點一致：{"message": ...}，前端只要認一種 */
    private static void reject(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
