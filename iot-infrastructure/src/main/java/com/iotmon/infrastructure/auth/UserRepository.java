package com.iotmon.infrastructure.auth;

import com.iotmon.domain.auth.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 使用者的讀寫。密碼雜湊的產生與比對不在這裡，那是 API 層安全設定的事。 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(long id, String username, String passwordHash, Role role, String displayName,
                          boolean enabled, Instant createdAt) {
    }

    private static final String SELECT =
            "SELECT id, username, password_hash, role, display_name, enabled, created_at FROM app_user";

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.query(SELECT + " WHERE username = ?", this::map, username).stream().findFirst();
    }

    public List<UserRow> findAll() {
        return jdbc.query(SELECT + " ORDER BY id", this::map);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
        return n == null ? 0 : n;
    }

    public UserRow insert(String username, String passwordHash, Role role, String displayName) {
        Long id = jdbc.queryForObject("""
                INSERT INTO app_user (username, password_hash, role, display_name) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, username, passwordHash, role.name(), displayName);
        return new UserRow(id, username, passwordHash, role, displayName, true, Instant.now());
    }

    private UserRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")), rs.getString("display_name"), rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant());
    }
}
