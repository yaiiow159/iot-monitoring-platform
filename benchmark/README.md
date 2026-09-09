# benchmark

一萬台裝置壓測用的三個檔案。實測結果與判讀在 `docs/performance.md`。

| 檔案 | 用途 |
|---|---|
| `seed_10k.sql` | 補到 270 個機櫃、一萬台裝置（DEV-000000 ～ DEV-009999），機型依機櫃類型可接受的來配 |
| `sample_load.py` | 每 N 秒抓 API 與模擬器的 Prometheus 指標，印出發送／入庫速率、端到端延遲、Kafka 落後、批次寫入、連線池、堆積 |
| `publish.sh` | 用 EMQX HTTP API 對單一裝置發一筆遙測，驗證告警或回放時用 |

```bash
docker exec -i iot-timescaledb psql -U iotmon -d iotmon -q < benchmark/seed_10k.sql
python benchmark/sample_load.py 15 12          # 每 15 秒一次、12 次＝三分鐘
bash benchmark/publish.sh DEV-000001 TH-100 temperature 80 humidity 40
```

取樣欄位：`pub/s` 模擬器發送點數、`ingest/s` 入庫點數、`e2e p95/p99` 裝置時間戳到入庫的秒數、`lag max` Kafka 各分區最大落後、
`batch p95` 一批寫入耗時、`size avg` 每批點數、`hik.pend` 連線池等待數、`heap MB`、`alarm+` 這段期間新增的告警。
