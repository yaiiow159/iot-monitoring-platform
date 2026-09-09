package com.iotmon.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;

/** JDBC 讀列的共用小工具：可空數值、時間戳轉換、IN 子句佔位符。 */
public final class Rows {

    private Rows() {
    }

    /** Postgres 驅動拒絕把 int4 轉成 java.lang.Long，getLong + wasNull 才是可空整數的正解 */
    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    public static Short nullableShort(ResultSet rs, String column) throws SQLException {
        short v = rs.getShort(column);
        return rs.wasNull() ? null : v;
    }

    public static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** {@code ?,?,?}，給 IN 子句用；個數為 0 時呼叫端應該先短路，不要送出 IN () */
    public static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
