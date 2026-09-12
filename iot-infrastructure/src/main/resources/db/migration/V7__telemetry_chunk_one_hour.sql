-- 原始表的 chunk 從一天改成一小時。
--
-- V2 的註解把理由算錯了：它用「兩年 ÷ 730 個 chunk」推出一天，但原始表只保留 7 天，
-- 最多就 7 個 chunk。chunk 大小要用「活躍 chunk 的索引放不放得下記憶體」決定——
-- 寫入永遠只打在最新那一個 chunk 上，它的索引一旦超過 shared_buffers，
-- 每次 INSERT 就要去翻磁碟上的索引頁。
--
-- 實測與推算見 docs/performance.md 與 ADR-0008：一天的切法在設計目標（5 萬點/秒）下
-- 活躍 chunk 會長到 225 GB，那不是調參能救的量級。

SELECT set_chunk_time_interval('telemetry', INTERVAL '1 hour');
