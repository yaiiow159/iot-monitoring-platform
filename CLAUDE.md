# IoT 裝置監控平台

模擬 PDS Connect 那類設定中心＋告警監控中心：一萬台裝置、每秒數萬點、兩年歷史、前端一秒內。
Java 21／Spring Boot 3.3 六角架構＋Vite React 18；註解與 UI 用繁體中文。決策寫在 `docs/adr/`，數字寫在 `docs/performance.md`。

## 分層（ArchUnit 強制）

`iot-domain`（零框架）→ `iot-application`（只認 Port，只允許 spring-context／tx）→ `iot-infrastructure`（JDBC、Kafka、MQTT、推播）→ `iot-api`（REST、WebSocket、Security）。
依賴只能由外往內。要用框架的東西，推到 infrastructure 或抽 Port（例：`LiveSubscriber`）。**不要為了過測試放寬規則。**

## 共用做法（新程式碼一律用這些）

| 事 | 用什麼 |
|---|---|
| 控制器的錯誤回應 | 直接拋 `IllegalArgumentException`（400）或 `ApiException.notFound／conflict`；`ApiExceptionHandler` 統一轉成 `{"message"}` |
| 解析列舉／時間參數 | `Params.enumOf／enumOrNull／instant／present` |
| JDBC 可空欄位、時間戳、IN 佔位符 | `Rows.nullableLong／nullableShort／nullableDouble／instant／ts／placeholders` |
| 前端表單送出、回饋、權限閘門 | `web/src/components/forms.tsx`：`useSubmit`、`<Feedback>`、`<Gate>` |
| 前端請求 | 只走 `api.*`（`client.ts` 管 token、401、錯誤訊息）；`types.ts` 是 `docs/contracts.md` 的投影，`mock.ts` 要同步 |
| 領域不變條件 | `Guard`、`Identifier`；規則放聚合根，控制器不含規則 |

## 三條不能破的契約

1. 監控樹子節點順序來自後端 `(sortOrder, id)`，前端不排序。
2. 告警要一路上浮到 Equipment；rollup 由子樹重算，不用計數器。
3. 歷史查詢的層級由後端依跨度決定，回應一定帶 `resolution`。

## 熱路徑

`MqttBridge`、`TelemetryIngestConsumer`、`TimescaleTelemetryWriter`、`AlarmEngineConsumer`、`LiveSessionRegistry` 每秒被呼叫上萬次。
這裡多一次資料庫查詢或序列化都要有 `performance.md` 的數字支持。

## 註解

只留「為什麼」，一到兩句。推導、數字、否決的方案放 ADR 或 performance.md。超過八行的註解 hook 會提醒。

## 工具

```bash
node .claude/skills/quality-scan/scan.js        # 重複樣態、繞過共用做法、長註解、分層違規
node .claude/skills/arch-check/run.js           # ArchUnit ＋ 單元測試 ＋ tsc ＋ 前端 lint
node .claude/skills/code-review/collect.js      # 審查材料；審完 mark.js 才能 push
node .claude/lib/baseline.js --update           # 修完一批舊問題後更新基準線
```

hooks：commit 前擋 error 等級（密鑰、分層違規、前端排序樹）；push 前要求 code-review；每次編輯後只回報新引入的問題。

## 已知限制

告警狀態機在記憶體，API 只能單實例。這台開發機的上限是每秒 1.25 萬則／3.75 萬點，再往上是 broker 的 CPU。
`GET /devices` 不帶篩選會撞 1000 筆上限，前端依機櫃查。
