# 可觀測性

Prometheus（`localhost:9091`）每 10 秒抓 API 與模擬器的 `/actuator/prometheus`；
Grafana（`localhost:3002`，匿名可看）以檔案佈建資料來源與儀表板，`docker compose up` 起來就有圖。

儀表板：`deploy/grafana/dashboards/iot-pipeline.json`，五列，由上到下對應遙測從裝置到畫面的路徑。

## 該看哪個指標

| 想知道 | 指標 | 為什麼是它 |
|---|---|---|
| 消費端跟不跟得上 | `telemetry_ingest_latency_seconds{quantile="0.95"}` | lag 是積壓的訊息數，延遲才是使用者感受得到的秒數。超過 1s 就是跟不上 |
| 哪個消費群組慢 | `kafka_consumer_fetch_manager_records_lag_max` by `client_id` | 三個群組獨立（ADR-0003），寫入端落後是資料缺口風險，推播端落後只影響畫面 |
| 批次有沒有發揮作用 | `telemetry_ingest_batch_size` 平均 vs `telemetry_write_batch_seconds` p95 | 批次太小＝往返太多；耗時漲但批次沒漲＝資料庫本身變慢 |
| 查詢端有沒有被寫入端餓死 | `hikaricp_connections_pending` | 持續 > 0 就是前兆，連線池上限在 application.yml 有解釋 |
| 告警狀態機有沒有漏水 | `alarm_evaluator_tracked` | 一直漲不回落代表離線裝置的累積狀態沒被清掉 |
| 瀏覽器端有沒有被淹沒 | `live_messages_dropped_total` | 慢連線會被丟訊息而不是拖慢所有人；丟得多代表該做按節點訂閱了 |
| REST 有沒有守住一秒 | `http_server_requests_seconds_max` by `uri` | 看最大值不看平均，寧可看最壞的 |

## 自己加的指標

| 名稱 | 型別 | 位置 |
|---|---|---|
| `telemetry.ingested` / `telemetry.rejected` | counter | `TelemetryIngestConsumer` |
| `telemetry.ingest.latency` | timer（p50/p95/p99） | 同上，只量每批最舊的一則 |
| `telemetry.ingest.batch.size` | summary | 同上 |
| `telemetry.write.batch` | timer | `TimescaleTelemetryWriter` |
| `mqtt.bridge.forwarded` / `mqtt.bridge.malformed` | counter | `MqttBridge` |
| `alarm.fired` / `alarm.resolved` | counter | `AlarmEngineConsumer` |
| `alarm.evaluator.tracked` | gauge | 同上 |
| `live.sessions` | gauge | `LiveSessionRegistry` |
| `live.messages.pushed` / `live.messages.dropped` | counter | 同上 |
| `simulator.*` | counter／gauge／timer | `iot-simulator`，port 8081 |

Kafka 消費端、HikariCP、JVM、HTTP 的指標由 Spring Boot 自動註冊，不用自己寫。

## 刻意不做的

- **不用 histogram 桶**。Micrometer 的百分位是 client 端算好的 gauge，
  Prometheus 這邊做不了跨實例的 `histogram_quantile`；但這個系統只有一個 API 實例，
  用 histogram 只是多幾十條時間序列。多實例時再改 `publishPercentileHistogram`。
- **延遲只量每批最舊的一則**。批次內的差距是毫秒級，逐筆記錄每秒要多五萬次計時。
- **沒有告警規則（Alertmanager）**。壓測前先把圖看熟，知道正常長什麼樣再訂門檻。
