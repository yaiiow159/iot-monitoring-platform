package com.iotmon.infrastructure.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Cache-aside 查找：先看記憶體，沒有才問來源，並記住「來源也沒有」的結果。
 *
 * <p>MetricDictionary 與 DeviceIdResolver 原本各自維護一份 ConcurrentHashMap、
 * 一份 miss 快取與一套 warmUp／invalidate。兩份程式碼一模一樣，
 * 差別只在查詢的 SQL。這種重複最危險的地方在於**修一邊忘了另一邊**——
 * 例如 miss 快取的失效邏輯，只補其中一個的話另一個就會永遠記住錯誤答案。
 *
 * <p>負向快取（記住「查不到」）是必要的：每秒五萬筆遙測裡若有一個打錯字的
 * deviceId，不記住就等於每秒打資料庫五萬次去確認它還是不存在。
 *
 * @param <K> 查找鍵
 * @param <V> 查找結果
 */
public final class CachedLookup<K, V> {

    private final Map<K, V> hits = new ConcurrentHashMap<>();
    private final Map<K, Boolean> misses = new ConcurrentHashMap<>();
    private final Function<K, Optional<V>> source;

    /**
     * @param source 查來源。回傳 empty 代表「確定不存在」，會被記住直到 invalidate；
     *               拋例外代表「暫時查不到」，不會被記住。
     */
    public CachedLookup(Function<K, Optional<V>> source) {
        this.source = source;
    }

    /** @return 找不到時回傳 null，與原本兩個類別的介面一致 */
    public V get(K key) {
        V cached = hits.get(key);
        if (cached != null) {
            return cached;
        }
        if (misses.containsKey(key)) {
            return null;
        }

        Optional<V> found = source.apply(key);
        if (found.isPresent()) {
            hits.put(key, found.get());
            return found.get();
        }
        misses.put(key, Boolean.TRUE);
        return null;
    }

    /** 直接放入已知的對應，warmUp 與「剛建立就要立刻可用」的情境使用 */
    public void put(K key, V value) {
        hits.put(key, value);
        misses.remove(key);
    }

    /** 清掉這個鍵的正向與負向快取。新註冊的裝置若不清負向快取，會被當成不存在。 */
    public void invalidate(K key) {
        hits.remove(key);
        misses.remove(key);
    }

    public int size() {
        return hits.size();
    }
}
