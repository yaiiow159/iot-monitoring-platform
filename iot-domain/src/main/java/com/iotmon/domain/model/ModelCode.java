package com.iotmon.domain.model;

import java.util.regex.Pattern;

/** 機型代號，例如 {@code TH-100}、{@code PWR-3P}。 */
public record ModelCode(String value) {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9-]{1,31}$");

    public ModelCode {
        if (value == null) {
            throw new IllegalArgumentException("機型代號不可為 null");
        }
        value = value.trim().toUpperCase();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("機型代號格式不符（大寫英數與連字號，2~32 字）：" + value);
        }
    }

    public static ModelCode of(String value) {
        return new ModelCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
