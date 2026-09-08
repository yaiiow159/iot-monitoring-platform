package com.iotmon.domain.device;

import java.util.regex.Pattern;

/**
 * 裝置識別碼。同時是 MQTT 主題的一段，因此不允許 {@code /}、{@code +}、{@code #}
 * 這些會改變主題語意的字元——否則一台命名不當的裝置就能訂閱到別人的資料。
 */
public record DeviceId(String value) implements Comparable<DeviceId> {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$");

    public DeviceId {
        if (value == null) {
            throw new IllegalArgumentException("裝置識別碼不可為 null");
        }
        value = value.trim();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "裝置識別碼只允許英數、底線與連字號，長度 3~64：" + value);
        }
    }

    public static DeviceId of(String value) {
        return new DeviceId(value);
    }

    @Override
    public int compareTo(DeviceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
