---
name: load-test
description: 用模擬器對整條路徑做一萬台裝置的壓測，取樣 Prometheus 指標並判讀哪一段是瓶頸。當使用者說要壓測、測吞吐、看每秒幾點、確認改動有沒有讓效能退化，或改過 MqttBridge／消費端／寫入端之後使用。
---

# Load test

工具在 `benchmark/`，步驟與判讀寫在 `benchmark/README.md`。實測數字與第一次跑時踩到的坑在 `docs/performance.md`「一萬台裝置滿載實測」。

## 最短流程

```bash
docker compose up -d
docker exec -i iot-timescaledb psql -U iotmon -d iotmon -q < benchmark/seed_10k.sql   # 第一次才需要
java -jar iot-api/target/iot-monitoring.jar &
java -Xmx4g -jar iot-simulator/target/iot-simulator.jar --simulator.device-count=10000 --simulator.publish-interval-ms=1000 &
python benchmark/sample_load.py 15 12
```

## 怎麼判讀

| 現象 | 意思 | 先看哪裡 |
|---|---|---|
| 發送 ≫ 入庫、Kafka 落後 ≈ 0 | 訊息在 broker 就被丟了 | EMQX `messages.dropped`、`mqtt_bridge_connections`、API 日誌「中斷」 |
| 入庫跟得上、e2e 延遲持續漲 | broker 佇列在堆 | EMQX `mqueue_len`；`max_mqueue_len` 故意不大，超載應該丟不是堆 |
| Kafka 落後在漲 | 消費端慢 | 批次寫入 p95（沒開 `reWriteBatchedInserts` 會是秒級）、`hikaricp_connections_pending` |
| 一切正常但主機 CPU 99% | 這台機器的牆 | 見 performance.md：broker 要自己的機器 |

## 這台機器的基準（2026-09-09）

每台 1000 ms：3 萬點/秒全入庫、e2e p95 0.3 秒。每台 800 ms：3.75 萬點/秒仍跟得上。700 ms 以下開始落後。
改動後跑同樣的三個間隔，數字不該變差。
