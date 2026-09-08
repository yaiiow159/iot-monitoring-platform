package com.iotmon.infrastructure.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 稽核紀錄：只增不查改。
 *
 * <p>寫入失敗只記日誌不拋例外：稽核寫不進去不該讓使用者的操作跟著失敗，
 * 否則資料庫暫時不穩時整個設定中心都會停擺。這是取捨，不是疏忽。
 */
@Repository
public class AuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRepository.class);

    public static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditLogRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Entry(long id, Instant at, String actor, String action, String targetType, String targetId,
                        String outcome, Map<String, Object> detail) {
    }

    public void record(String actor, String action, String targetType, String targetId, String outcome,
                       Map<String, Object> detail) {
        try {
            String detailJson = detail == null ? null : json.writeValueAsString(detail);
            jdbc.update("""
                    INSERT INTO audit_log (actor, action, target_type, target_id, outcome, detail)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)
                    """, actor, action, targetType, targetId, outcome, detailJson);
        } catch (JsonProcessingException | org.springframework.dao.DataAccessException e) {
            log.error("稽核紀錄寫入失敗 actor={} action={}：{}", actor, action, e.getMessage());
        }
    }

    public List<Entry> latest(Integer limit, String actor) {
        int effective = limit == null || limit <= 0 ? 100 : Math.min(limit, MAX_LIMIT);
        String sql = "SELECT id, at, actor, action, target_type, target_id, outcome, detail::text AS detail FROM audit_log"
                + (actor == null || actor.isBlank() ? "" : " WHERE actor = ?")
                + " ORDER BY at DESC, id DESC LIMIT ?";
        Object[] args = actor == null || actor.isBlank() ? new Object[]{effective} : new Object[]{actor.trim(), effective};
        return jdbc.query(sql, (rs, i) -> new Entry(rs.getLong("id"), rs.getTimestamp("at").toInstant(),
                rs.getString("actor"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("outcome"), parse(rs.getString("detail"))), args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String detail) {
        if (detail == null) {
            return null;
        }
        try {
            return json.readValue(detail, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("raw", detail);
        }
    }
}
