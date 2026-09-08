package com.iotmon.domain.model;

import com.iotmon.domain.shared.Identifier;

import java.util.regex.Pattern;

/** 機型代號，例如 {@code TH-100}、{@code PWR-3P}。 */
public record ModelCode(String value) {

    private static final Pattern VALID = Pattern.compile("^[A-Z][A-Z0-9-]{1,31}$");

    public ModelCode {
        value = Identifier.validated(value, String::toUpperCase, VALID,
                "機型代號", "大寫英數與連字號、2~32 字");
    }

    public static ModelCode of(String value) {
        return new ModelCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
