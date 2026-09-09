# ADR-0007：MQTT 橋接用多條連線共享訂閱，回呼離開事件迴圈

狀態：已採納　日期：2026-09-09

## 背景

橋接是 MQTT 與 Kafka 之間唯一的一段，也是整條路徑上最不能塞車的地方。
原本的實作是一條 HiveMQ 連線訂閱 `iot/telemetry/+/+`，回呼直接在 Netty 事件迴圈上解析 JSON、丟進 Kafka。

一萬台裝置的實測（見 performance.md）把它打穿了：每秒一萬則時事件迴圈跑滿，
PINGRESP 來不及處理、被 broker 當成 keepalive 逾時踢掉；重連後訂閱佇列滿了就丟；
EMQX 的統計是「收到 419 萬則、送出 44 萬則、佇列滿丟掉 90 萬則、沒有訂閱者丟掉 118 萬則」。
入庫只剩發送量的五分之一，而 Kafka 的 lag 是 0——因為訊息根本沒到 Kafka。

## 決策

1. **多條連線用共享訂閱分流**：訂閱 `$share/iot-bridge/iot/telemetry/+/+`，
   broker 把訊息輪流派給群組內的連線。連線數是設定值（`iot.mqtt.bridge-connections`，預設 8）。
2. **回呼在獨立的執行緒池**：`publishes(filter, callback, executor)`，事件迴圈只收封包、回 PUBACK。
3. **`cleanStart=true`**：共享訂閱不需要持久會話。斷線期間的訊息由其他連線接手，
   而不是堆在一個沒人讀的會話佇列裡等它回來；每次 CONNACK 都重新訂閱。
4. **EMQX 的 `force_shutdown.max_mailbox_size` 調大**：預設 1000 則，共享訂閱把每秒數千則塞進單一連線程序的信箱，
   超過就被強制關閉，表現為「Server closed connection without DISCONNECT」每秒一次。
   這是 broker 側的設定，不是程式碼能解的。
5. **`max_mqueue_len` 故意不放大**（10,000）：超載時遙測寧可丟（ADR-0003 允許），
   也不要堆成三分鐘前的舊資料——實測佇列放到十萬時端到端延遲爬到 190 秒。

## 否決的方案

**EMQX 內建的 Kafka 資料整合。** 最乾淨，但那是企業版功能，開源版沒有。

**QoS 0。** 能省掉 PUBACK 的成本，但 ADR-0003 的前提是「遙測允許重複、不允許遺失」，
QoS 0 在 broker 稍有壓力時就會安靜地丟，而且丟了沒有任何指標看得到。

**單一連線、調大 receive maximum。** 治標：事件迴圈仍是單一執行緒，keepalive 的問題不會消失。

## 後果

- 同一台裝置的訊息可能落在不同的橋接連線上，但分區鍵仍是 deviceId，Kafka 內的順序不受影響；
  跨連線的先後在毫秒級，聚合時無感。
- `mqtt.bridge.connections` 這個 gauge 應該恆等於設定值，掉下來就是 broker 在踢人。
- 這台開發機的上限是每秒約 1.25 萬則（3.75 萬點）：再往上是 EMQX 與主機 CPU 到頂，
  不是橋接程式碼——工作執行緒在傾印裡全是閒置的。要到每秒五萬點，broker 要有自己的機器。
