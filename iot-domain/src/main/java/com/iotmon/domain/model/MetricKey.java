package com.iotmon.domain.model;

import java.util.regex.Pattern;

/**
 * 指標代號，例如 {@code temperature}、{@code voltage_l1}。
 *
 * <p>做成值物件而不是直接用 String，是因為指標代號會出現在 MQTT 主題、
 * Kafka 訊息、資料庫欄位與前端查詢參數裡。只要有一個地方大小寫或分隔符不一致，
 * 資料就會安靜地分裂成兩個序列，而且要等到查詢時才會發現。
 * 在這裡一次收斂格式，比在五個地方各自 trim 與 toLowerCase 可靠。
 */
public record MetricKey(String value) implements Comparable<MetricKey> {

    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public MetricKey {
        if (value == null) {
            throw new IllegalArgumentException("指標代號不可為 null");
        }
        value = value.trim().toLowerCase();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "指標代號只允許小寫英數與底線、需以字母開頭、長度 1~63：" + value);
        }
    }

    public static MetricKey of(String value) {
        return new MetricKey(value);
    }

    @Override
    public int compareTo(MetricKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
