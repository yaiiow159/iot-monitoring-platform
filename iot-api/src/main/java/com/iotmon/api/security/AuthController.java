package com.iotmon.api.security;

import com.iotmon.api.rest.ApiException;
import com.iotmon.api.rest.Params;
import com.iotmon.api.rest.ApiException;
import com.iotmon.api.rest.Params;
import com.iotmon.api.rest.ApiException;
import com.iotmon.api.rest.Params;
import com.iotmon.domain.auth.Role;
import com.iotmon.infrastructure.auth.AuditLogRepository;
import com.iotmon.infrastructure.auth.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 登入、目前使用者、使用者管理、稽核查詢。 */
public final class AuthController {

    private AuthController() {
    }

    @RestController
    @RequestMapping("/api/v1/auth")
    public static class LoginController {

        private final UserRepository users;
        private final PasswordEncoder encoder;
        private final JwtService jwt;
        private final AuditLogRepository audit;

        public LoginController(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditLogRepository audit) {
            this.users = users;
            this.encoder = encoder;
            this.jwt = jwt;
            this.audit = audit;
        }

        public record LoginRequest(String username, String password) {
        }

        public record LoginResponse(String token, String username, Role role, String displayName, Instant expiresAt) {
        }

        /** 帳號不存在與密碼錯誤回同一句話，也都做一次 bcrypt 比對，讓兩者耗時一樣 */
        @PostMapping("/login")
        public ResponseEntity<?> login(@RequestBody LoginRequest request) {
            String username = request.username() == null ? "" : request.username().trim();
            Optional<UserRepository.UserRow> user = users.findByUsername(username);
            String hash = user.map(UserRepository.UserRow::passwordHash).orElse(DUMMY_HASH);
            boolean ok = encoder.matches(request.password() == null ? "" : request.password(), hash)
                    && user.isPresent() && user.get().enabled();
            if (!ok) {
                audit.record(username.isEmpty() ? "anonymous" : username, "LOGIN", "auth", null, "DENIED",
                        Map.of("reason", "帳號或密碼錯誤"));
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "帳號或密碼錯誤"));
            }
            UserRepository.UserRow u = user.get();
            JwtService.Issued issued = jwt.issue(new JwtService.Principal(u.username(), u.role(), u.displayName()));
            audit.record(u.username(), "LOGIN", "auth", null, "OK", Map.of("role", u.role().name()));
            return ResponseEntity.ok(new LoginResponse(issued.token(), u.username(), u.role(), u.displayName(),
                    issued.expiresAt()));
        }

        @GetMapping("/me")
        public ResponseEntity<?> me() {
            return CurrentUser.get()
                    .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of(
                            "username", p.username(), "role", p.role(), "displayName", p.displayName(),
                            "canConfigure", p.role().canConfigure(), "canOperate", p.role().canOperate())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "未登入")));
        }

        /** 給不存在的帳號比對用的假雜湊，避免用回應時間猜帳號是否存在 */
        private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5XBQxuS9C0S4Qpsb3d9cFzJ8j8bK2";
    }

    @RestController
    @RequestMapping("/api/v1/users")
    public static class UserController {

        private final UserRepository users;
        private final PasswordEncoder encoder;

        public UserController(UserRepository users, PasswordEncoder encoder) {
            this.users = users;
            this.encoder = encoder;
        }

        public record UserResponse(long id, String username, Role role, String displayName, boolean enabled,
                                   Instant createdAt) {
            static UserResponse from(UserRepository.UserRow u) {
                return new UserResponse(u.id(), u.username(), u.role(), u.displayName(), u.enabled(), u.createdAt());
            }
        }

        public record CreateUserRequest(String username, String password, String role, String displayName) {
        }

        @GetMapping
        public List<UserResponse> list() {
            return users.findAll().stream().map(UserResponse::from).toList();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public UserResponse create(@RequestBody CreateUserRequest request) {
            String username = request.username() == null ? "" : request.username().trim();
            if (!username.matches("[a-z0-9_.-]{3,64}")) {
                throw new IllegalArgumentException("帳號只能用小寫英數與 _ . -，3 到 64 字元");
            }
            if (request.password() == null || request.password().length() < 8) {
                throw new IllegalArgumentException("密碼至少 8 字元");
            }
            Role role = Params.enumOf(Role.class, request.role(), "角色");
            String display = Params.present(request.displayName()) ? request.displayName().trim() : username;
            try {
                return UserResponse.from(users.insert(username, encoder.encode(request.password()), role, display));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("帳號已存在");
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1/audit")
    public static class AuditController {

        private final AuditLogRepository audit;

        public AuditController(AuditLogRepository audit) {
            this.audit = audit;
        }

        @GetMapping
        public List<AuditLogRepository.Entry> latest(@RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) String actor) {
            return audit.latest(limit, actor);
        }
    }
}
