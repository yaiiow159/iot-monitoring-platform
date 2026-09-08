# 介面契約

這份文件定義模組之間的邊界。改動這裡的任何一項都會同時影響多個模組，
因此改之前先確認所有消費端都跟著改。

---

## MQTT

Broker：EMQX，`localhost:1883`（TCP）／`localhost:8083`（WebSocket）。

### 主題

| 主題 | 方向 | 說明 |
|---|---|---|
| `iot/telemetry/{modelCode}/{deviceId}` | 裝置 → 平台 | 遙測批次 |
| `iot/status/{deviceId}` | 裝置 → 平台 | 上線宣告與 LWT 遺言 |
| `iot/command/{deviceId}` | 平台 → 裝置 | 設定下發（保留給後續階段） |

主題裡帶 `modelCode` 是為了讓橋接端不必先查資料庫就能知道該套哪份指標定義。
裝置註冊後機型不會改變；真要換機型，等同換一台裝置。

### 遙測訊息

QoS 1。一則訊息帶多個指標，減少每則訊息的固定開銷——
每秒五萬點若一點一則，光是 MQTT 標頭就吃掉可觀的頻寬。

```json
{
  "deviceId": "DEV-000123",
  "ts": 1788950400000,
  "metrics": { "temperature": 24.7, "humidity": 61.2 }
}
```

`ts` 是**裝置端取樣時間**的毫秒 epoch，不是伺服器接收時間。
兩者的差距就是端到端延遲，也是「時鐘飄移」這個模擬情境要觀察的東西。

### 狀態訊息

QoS 1，`retain = true`。retain 讓平台重啟後訂閱就能立刻拿到每台裝置的最後狀態，
不必等下一次狀態變化。

```json
{ "deviceId": "DEV-000123", "state": "ONLINE", "ts": 1788950400000 }
```

LWT 在連線時註冊，`state` 為 `OFFLINE`。裝置正常斷線時會自己先發一則 `OFFLINE`
再中止連線；異常斷線則由 broker 代發遺言。兩者對平台而言沒有差別。

---

## Kafka

| 主題 | 分區 | 內容 |
|---|---|---|
| `iot.telemetry` | 12 | 通過驗證的遙測點 |
| `iot.device-status` | 3 | 裝置上下線事件 |
| `iot.alarm` | 3 | 告警觸發與解除 |

**分區鍵一律是 `deviceId`。** 同一台裝置的訊息因此落在同一個分區，
保證平台看到的順序與裝置送出的順序一致。
沒有這個保證的話，「上線→離線」有機會被處理成「離線→上線」，
裝置狀態就會卡在錯誤的值上。

`iot.telemetry` 給 12 個分區是為了讓寫入消費端能水平擴充；
另外兩個主題流量小，3 個分區足夠。

---

## REST API

Base：`http://localhost:8090/api/v1`

### 設定中心

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/models` | 列出機型 |
| POST | `/models` | 新增機型（含指標定義） |
| GET | `/cabinets` | 列出機櫃 |
| POST | `/cabinets` | 新增機櫃 |
| GET | `/devices` | 列出裝置，可依 `status`／`cabinetId`／`modelCode` 篩選 |
| GET | `/devices/{deviceId}` | 單一裝置。前端的裝置詳情頁需要，用清單再過濾在一萬台的規模下不划算 |
| POST | `/devices` | 註冊裝置 |
| GET | `/alarm-rules` | 列出告警規則 |
| POST | `/alarm-rules` | 新增告警規則 |

### 監控

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/overview` | 儀表板摘要：各狀態裝置數、未解除告警數、寫入速率 |
| GET | `/alarms?state=FIRING&deviceId=...` | 告警列表。`deviceId` 供裝置詳情頁的告警歷史使用 |
| GET | `/telemetry` | 歷史查詢，見下 |

### 歷史查詢

```
GET /telemetry?deviceId=DEV-000123&metric=temperature&from=...&to=...&maxPoints=500
```

`from` / `to` 為 ISO-8601。**回應一定帶 `resolution` 欄位**，說明這份資料來自
哪一層（`raw` / `1m` / `1h`），呼叫端才知道自己拿到的是原始值還是聚合值。

```json
{
  "deviceId": "DEV-000123",
  "metric": "temperature",
  "resolution": "1m",
  "points": [
    { "t": "2026-09-09T10:00:00Z", "avg": 24.7, "min": 24.1, "max": 25.3, "count": 60 }
  ]
}
```

層級由後端依時間跨度決定，呼叫端不能指定——
否則遲早有人對兩年的範圍要求原始精度，把資料庫拖垮。

`maxPoints` 上限 5000。超過就回 400 並說明應該縮小範圍或加大時間桶，
不會安靜地截斷——安靜截斷的圖表比沒有圖表更危險。

---

## WebSocket

端點：`ws://localhost:8090/ws/live`

連線後送一則訂閱訊息，指定要收哪些裝置的更新。
不訂閱就什麼都不推——一萬台裝置的更新全推給每個瀏覽器會直接打爆前端。

```json
{ "action": "subscribe", "deviceIds": ["DEV-000123", "DEV-000124"] }
```

伺服器推播：

```json
{ "type": "telemetry", "deviceId": "DEV-000123", "metrics": { "temperature": 24.7 }, "ts": 1788950400000 }
{ "type": "status", "deviceId": "DEV-000123", "state": "OFFLINE", "ts": 1788950400000 }
{ "type": "alarm", "alarmId": 881, "deviceId": "DEV-000123", "severity": "CRITICAL", "state": "FIRING", "ts": 1788950400000 }
```

**遙測推播會做節流**：同一台裝置最多每秒推一次，取該秒內的最後一筆。
人眼看不出每秒五次的差別，但瀏覽器會因此卡住。
告警與狀態變化不節流——那些是低頻但每一則都重要。

---

## 監控樹（Equipment → Sensor* → Device）

```
Equipment            根節點，不能有父節點
 └─ Sensor           可無限自我嵌套：Sensor 下可以再有 Sensor
     ├─ Sensor
     │   └─ Device   葉節點：Device 下不能再掛任何東西
     └─ Device
```

規則由後端強制，違反回 `400`：Equipment 只能在根、Sensor 的父節點只能是 Equipment 或 Sensor、
Device 的父節點只能是 Sensor、Device 不能有子節點。

### 順序是後端的保證，不是前端的責任

每個節點有 `sortOrder`。**所有回傳子節點的地方一律以 `(sortOrder, id)` 排序**，
`id` 是決定性的平手判斷，因此同一棵樹每次回傳的順序都完全相同。
前端**不得**自行排序，也不得用 Map／Set 這類不保證順序的結構承接子節點。

新增節點時不指定 `sortOrder` 就排在同層最後；`sortOrder` 之間留有間隔（預設 1000），
插入中間不需要重新編號整層。

### 告警上浮

每個節點回傳 `rollup`：**它自己與整個子樹**裡未解除告警的最高嚴重度與數量。
葉節點響了，它的每一層祖先直到 Equipment 都會反映——這是「父元素感知」的實作。

`rollup` 由子樹重新計算，不是遞增遞減的計數器；因此不存在
「解除一則告警後兄弟節點還在響、父節點卻變綠」的狀態。

| 方法 | 路徑 | 說明 |
|---|---|---|
| GET | `/tree` | 整棵樹，含每個節點的 `rollup`。已排序。 |
| GET | `/tree/{nodeId}` | 該節點與其子樹 |
| GET | `/tree/{nodeId}/ancestors` | 從根到該節點的路徑，**由上到下**，用來畫麵包屑與定位告警來源 |
| POST | `/tree/nodes` | 新增節點：`{ kind, name, parentId, deviceId?, sortOrder? }` |
| PATCH | `/tree/nodes/{nodeId}/order` | 調整順序：`{ sortOrder }` |

```json
{
  "id": 1, "kind": "EQUIPMENT", "name": "1 號變電站", "sortOrder": 1000,
  "rollup": { "severity": "CRITICAL", "firing": 3 },
  "children": [
    { "id": 4, "kind": "SENSOR", "name": "A 相", "sortOrder": 1000,
      "rollup": { "severity": "CRITICAL", "firing": 2 },
      "children": [
        { "id": 9, "kind": "DEVICE", "name": "電流計", "deviceId": "DEV-000012", "sortOrder": 1000,
          "rollup": { "severity": "CRITICAL", "firing": 2 }, "children": [] }
      ] },
    { "id": 5, "kind": "SENSOR", "name": "B 相", "sortOrder": 2000,
      "rollup": { "severity": null, "firing": 0 }, "children": [] }
  ]
}
```

`rollup.severity` 為 `null` 代表子樹內沒有任何未解除告警。

WebSocket 的 `alarm` 推播會多帶 `ancestorIds`（由上到下），
前端據此更新整條路徑上的節點，而不是只更新葉節點。

---

## 實作狀態（2026-09-09）

契約先於實作寫定，這張表說明「現在哪些端點真的在」。全部端點已實作並以 curl 逐一驗證，
前端在 `VITE_USE_MOCK=false` 下可以完整跑起來。

| 端點 | 狀態 |
|---|---|
| `GET /telemetry` | ✅ 三層路由、resolution 欄位、400 拒絕過大範圍 |
| `GET /tree`、`/tree/{id}`、`/tree/{id}/ancestors`、`POST /tree/nodes`、`PATCH …/order` | ✅ |
| WebSocket `/ws/live`（telemetry 節流、status、alarm 帶 ancestorIds） | ✅ |
| `GET /overview` | ✅ 四種狀態都有 key；`ingestRatePerSecond` 取過去 10 秒原始表計數 ÷ 10 |
| `GET /devices?status&cabinetId&modelCode`、`GET /devices/{id}` | ✅ 清單上限 1000 筆，排序＝機櫃→槽位→代號；單筆不存在回 404 |
| `POST /devices` | ✅ 走 `Cabinet.rejectReasonFor` 與 `Device.register`；成功後讓 `DeviceIdResolver` 的負向快取失效，裝置立刻可收遙測 |
| `GET/POST /models` | ✅ 機型與指標同一交易寫入；`DeviceModel.of` 擋空指標、重複指標 |
| `GET/POST /cabinets` | ✅ `id` 就是機櫃 `code`（前端拿它當顯示名稱與 `Device.cabinetId` 的關聯鍵）；資料表的數值 id 不外露 |
| `GET/POST /alarm-rules` | ✅ 建立前以 `AlarmRule.isMeaningfulFor` 擋掉門檻落在量程外、永遠不會觸發的規則；寫入後規則快取立即失效 |
| `GET /alarms?state&deviceId&limit` | ✅ 一次 join 裝置與規則；FIRING 排前、再依觸發時間新到舊；`limit` 上限 500 |

### 回應形狀上的取捨

- `Device.name`／`Alarm.deviceName` 用 `serial_no`：資料表沒有獨立顯示名稱欄位，加欄位前先不要憑空造一個。
- `Alarm.message` 用規則名稱：值班的人要看的是「哪條規則響了」。
- 錯誤一律 `{"message": "..."}`；領域不變條件回 400 帶原文，唯一鍵衝突回 409，列舉值不合法會列出可用值而不是漏出 Java 類別名。
- `MetricDefinition.unit` 允許空字串：功率因數、門磁這類無因次量本來就沒有單位（原本的 `notBlank` 讓 `GET /models` 直接 500）。
- 註冊後裝置的 `status` 仍是 `UNKNOWN`：上下線由 MQTT 連線／LWT 事件驅動（ADR-0004），單純送遙測不會改狀態。
