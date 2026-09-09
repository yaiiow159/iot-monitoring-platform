-- 聚合層也要壓縮。實測未壓縮時分鐘層每列 125 B、小時層每列 167 B（含索引），
-- 「五年小時層 8.76 億列」在這個大小下是 146 GB；壓縮後才是 README 說的量級。
-- 30 天前的桶已經不會再被 refresh 改動，壓起來沒有代價。

ALTER MATERIALIZED VIEW telemetry_1m SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'device_id, metric_id',
    timescaledb.compress_orderby = 'bucket'
);
SELECT add_compression_policy('telemetry_1m', compress_after => INTERVAL '3 days');

ALTER MATERIALIZED VIEW telemetry_1h SET (
    timescaledb.compress = true,
    timescaledb.compress_segmentby = 'device_id, metric_id',
    timescaledb.compress_orderby = 'bucket'
);
SELECT add_compression_policy('telemetry_1h', compress_after => INTERVAL '30 days');
