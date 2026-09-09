---
name: code-review
description: 審查尚未推送或已暫存的改動：分層違規、繞過共用做法（ApiExceptionHandler／Params／Rows／forms.tsx）、順序或告警上浮的契約被破壞、註解過長，並以繁體中文回報。當使用者說要 code review、審查、看一下這次改了什麼有沒有問題，或 pre-push hook 要求先審查時使用。
---

# Code review

推送前的最後一道把關。`pre-push-gate.js` 會攔下 `git push` 並指名要跑這個 skill。

## 先蒐集材料

```bash
node .claude/skills/code-review/collect.js            # 與 upstream 的差異
node .claude/skills/code-review/collect.js --staged   # 只看已暫存
node .claude/skills/code-review/collect.js --base=main
```

輸出：改動範圍與統計、動到的高風險區域、**只出現在新增行上**的 findings。腳本只回報事實，判斷在下面。

## 人工判讀的清單

依風險排序，每一條都要能回答「是」或「不適用」：

1. **分層**：domain 有沒有沾到框架？application 有沒有 import infrastructure／api？（ArchUnit 會擋，但要看有沒有人為了過測試放寬規則）
2. **契約**：`docs/contracts.md` 與 `web/src/api/types.ts` 有沒有同步？回應形狀改了，mock.ts 有沒有跟著改？
3. **順序與上浮**：監控樹的子節點順序只能來自後端；告警要能一路上浮到 Equipment。動到 `MonitoringTree`、`TreeController`、`Tree.tsx` 的改動要看這兩件事。
4. **共用做法**：新的 try/catch 轉譯例外、手組 `{"message"}`、`wasNull`、`nCopies("?")`、表單自己管 busy／error，都是繞過了既有的共用類別。
5. **熱路徑**：`TelemetryIngestConsumer`、`TimescaleTelemetryWriter`、`MqttBridge`、`LiveSessionRegistry` 每秒被呼叫上萬次，這裡多一次資料庫查詢或一次 JSON 序列化都要有數字支持（performance.md）。
6. **授權**：新端點有沒有落在 `SecurityConfig` 的規則裡？寫入端點是不是非 GET（稽核 filter 靠這個判斷）？
7. **註解**：只留「為什麼」。推導過程、數字、否決的方案搬到 ADR 或 performance.md。

## 回報格式

- 先講結論：可以推／要改什麼才能推。
- 每個問題一行：檔案:行號、是什麼、為什麼要改。
- 不要列「建議」以外的讚美。

## 審查完成

```bash
node .claude/skills/code-review/mark.js
```

把目前 HEAD 標記為已審查；HEAD 一變標記就失效。
