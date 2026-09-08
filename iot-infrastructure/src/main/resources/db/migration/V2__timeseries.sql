-- 時序資料與三層降採樣。
--
-- 連續聚合的建立與政策設定不能包在交易裡，因此整份腳本關閉交易。
-- flyway:executeInTransaction=false

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 遙測主表。
--
-- 欄位刻意壓到最少：時間、裝置、指標、讀數。
-- device_id 用 INTEGER、metric_id 用 SMALLINT 而不是字串，
-- 是「兩年歷史放得下」的前提（見 README 的儲存試算）。
-- 沒有主鍵：每秒五萬次寫入，維護唯一索引的成本遠高於它擋掉的重複，
-- 而重複點在聚合時本來就會被 avg 吸收。
CREATE TABLE telemetry (
    time      TIMESTAMPTZ      NOT NULL,
    device_id INTEGER          NOT NULL,
    metric_id SMALLINT         NOT NULL,
    value     DOUBLE PRECISION NOT NULL
);

-- chunk 切一天：太小會讓兩年的查詢要開幾千個 chunk，太大則壓縮與刪除的粒度太粗。
-- 一天 = 43.2 億筆 ÷ 730 個 chunk，落在 TimescaleDB 建議的區間內。
SELECT create_hypertable('telemetry', 'time', chunk_time_interval => INTERVAL '1 day');

-- 查詢一律帶 device_id 與時間範圍，這個順序讓 chunk 排除之後還能用索引定位裝置
CREATE INDEX idx_telemetry_device_time ON telemetry (device_id, metric_id, time DESC);

-- ── 第二層：1 分鐘聚合 ────────────────────────────────────────────────────────
--
-- 儀表板的預設視窗是「過去 24 小時」，那個範圍讀這一層。
-- 存 avg／min／max／count 四個值而不是只存 avg：
-- 尖峰值被平均吃掉之後就再也回不來了，而告警回顧最需要看的就是尖峰。
CREATE MATERIALIZED VIEW telemetry_1m
    WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 minute', time) AS bucket,
       device_id,
       metric_id,
       avg(value)   AS avg_value,
       min(value)   AS min_value,
       max(value)   AS max_value,
       count(*)     AS sample_count
FROM telemetry
GROUP BY bucket, device_id, metric_id
WITH NO DATA;

-- ── 第三層：1 小時聚合 ────────────────────────────────────────────────────────
--
-- 從 1 分鐘那一層再上捲，而不是回頭重掃原始表。
-- 原始表七天後就會被刪掉，若小時層依賴它，超過七天的資料就再也算不出來。
CREATE MATERIALIZED VIEW telemetry_1h
    WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 hour', bucket) AS bucket,
       device_id,
       metric_id,
       avg(avg_value) AS avg_value,
       min(min_value) AS min_value,
       max(max_value) AS max_value,
       sum(sample_count) AS sample_count
FROM telemetry_1m
GROUP BY 1, 2, 3
WITH NO DATA;

-- ── 重新整理政策 ─────────────────────────────────────────────────────────────
--
-- start_offset 留一段緩衝，讓遲到的資料還有機會被算進去；
-- end_offset 不做到「現在」，因為最新的那個桶還在累積，算了也會被推翻。
SELECT add_continuous_aggregate_policy('telemetry_1m',
    start_offset => INTERVAL '10 minutes',
    end_offset   => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

SELECT add_continuous_aggregate_policy('telemetry_1h',
    start_offset => INTERVAL '3 hours',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '10 minutes');

-- ── 壓縮 ────────────────────────────────────────────────────────────────────
--
-- segmentby 決定壓縮後的資料怎麼分組；用 device_id + metric_id 讓
-- 「單一裝置單一指標的時間範圍查詢」只需要解壓縮它自己那一段。
ALTER TABLE telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_id',
    timescaledb.compress_orderby = 'time DESC'
);

-- 一天前的 chunk 不會再被寫入，可以安全壓縮
SELECT add_compression_policy('telemetry', INTERVAL '1 day');

-- ── 保留 ────────────────────────────────────────────────────────────────────
--
-- 保留期限刻意分三段。這裡的數字是「單機放得下」的結果而不是隨手訂的，
-- 推導過程見 README。要延長任何一層之前，先重算儲存量。
SELECT add_retention_policy('telemetry', INTERVAL '7 days');
SELECT add_retention_policy('telemetry_1m', INTERVAL '30 days');
SELECT add_retention_policy('telemetry_1h', INTERVAL '5 years');
