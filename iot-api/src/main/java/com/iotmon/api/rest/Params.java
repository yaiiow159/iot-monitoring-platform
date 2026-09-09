package com.iotmon.api.rest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/** 請求參數的共用解析。錯誤訊息給使用者看，不漏 Java 類別名。 */
public final class Params {

    private Params() {
    }

    public static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + "不可為空");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " " + raw + " 不合法，可用值：" + Arrays.toString(type.getEnumConstants()));
        }
    }

    /** 可為空的列舉：空白視為「未指定」 */
    public static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw, String label) {
        return raw == null || raw.isBlank() ? null : enumOf(type, raw, label);
    }

    public static Instant instant(String raw, String label) {
        try {
            return Instant.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " 必須是 ISO-8601 時間，例如 2026-09-01T08:00:00Z");
        }
    }

    public static boolean present(String raw) {
        return raw != null && !raw.isBlank();
    }
}
