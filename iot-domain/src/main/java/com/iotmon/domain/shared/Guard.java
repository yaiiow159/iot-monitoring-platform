package com.iotmon.domain.shared;

/**
 * 領域層的前置條件檢查。抽出來是為了錯誤訊息一致：前端會直接把這些訊息顯示給使用者。
 * 每個方法都回傳被檢查的值，可寫成 {@code this.name = Guard.notBlank(name, "名稱")}。
 */
public final class Guard {

    private Guard() {
    }

    public static <T> T notNull(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + "不可為 null");
        }
        return value;
    }

    public static String notBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不可為空");
        }
        return value;
    }

    /** NaN 與無限大在時序系統裡會一路汙染聚合結果，而且 SQL 端不會報錯。 */
    public static double finite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + "必須是有限數：" + value);
        }
        return value;
    }

    public static int positive(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + "必須為正：" + value);
        }
        return value;
    }

    public static short positive(short value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + "必須為正：" + value);
        }
        return value;
    }

    /** 條件不成立就拋例外。用在無法歸類成上面幾種的業務規則。 */
    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
