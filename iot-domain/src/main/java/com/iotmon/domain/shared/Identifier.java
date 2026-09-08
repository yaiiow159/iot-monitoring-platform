package com.iotmon.domain.shared;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * 識別碼型值物件的共用驗證。
 *
 * <p>MetricKey、ModelCode、DeviceId 做的事完全一樣：拒絕 null、去頭尾空白、
 * 視情況正規化大小寫、比對格式、不符就拋出帶原值的訊息。
 * 三份各寫一遍時，「正規化在比對之前還是之後」這種順序差異就會悄悄出現，
 * 而那正是同一個識別碼分裂成兩個序列的來源。
 */
public final class Identifier {

    private Identifier() {
    }

    /**
     * @param raw        原始輸入
     * @param normalize  正規化步驟（例如轉小寫）；在格式比對**之前**套用
     * @param pattern    合法格式
     * @param label      識別碼名稱，用在錯誤訊息
     * @param formatHint 格式說明，用在錯誤訊息
     * @return 正規化後的值
     */
    public static String validated(String raw, UnaryOperator<String> normalize,
                                   Pattern pattern, String label, String formatHint) {
        Guard.notNull(raw, label);
        String value = normalize.apply(raw.trim());
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "格式不符（" + formatHint + "）：" + value);
        }
        return value;
    }
}
