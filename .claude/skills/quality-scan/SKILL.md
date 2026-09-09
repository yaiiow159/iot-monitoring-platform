---
name: quality-scan
description: 全庫掃描重複樣態與繞過共用做法的寫法——控制器自己轉譯例外、JDBC 自己處理可空欄位、前端表單自己管送出狀態、註解超過八行、分層違規、密鑰。當使用者說要整理程式碼品質、抽共用類別、減少重複、砍註解，或修完一批後要更新基準線時使用。
---

# Quality scan

```bash
node .claude/skills/quality-scan/scan.js            # 全部 findings，依規則分組
node .claude/skills/quality-scan/scan.js --new      # 只看基準線之外的
node .claude/skills/quality-scan/scan.js --json
node .claude/lib/baseline.js --update               # 修完一批後更新基準線
```

## 這些規則對應的共用做法

| 規則 | 該用什麼 |
|---|---|
| `controller-try-catch`、`controller-message-map` | `ApiExceptionHandler`：控制器直接拋 `IllegalArgumentException`（400）或 `ApiException.notFound／conflict` |
| `enum-valueof-raw` | `Params.enumOf(type, raw, 標籤)`、`Params.enumOrNull`、`Params.instant` |
| `raw-wasnull`、`raw-placeholders`、`raw-timestamp-instant` | `Rows.nullableLong／nullableShort／nullableDouble／instant／ts／placeholders` |
| `form-local-submit` | `web/src/components/forms.tsx` 的 `useSubmit`、`<Feedback>`、`<Gate>` |
| `inline-fetch` | `api.*`（`web/src/api/client.ts`） |
| `long-comment` | 註解只留「為什麼」；推導、數字、否決方案放 `docs/adr`、`docs/performance.md` |

## 抽共用類別的判準

出現兩次就抽，前提是**兩處要的是同一件事**，不只是長得像。
抽出來的東西要有名字說明它「回答什麼問題」（`Rows`、`Params`），不要叫 `Utils`。

## 砍註解的判準

留：為什麼這樣做、否決了什麼、數字來源在哪。
砍：程式碼已經說了的事、逐步的推導、對讀者的叮嚀。超過八行幾乎一定有可以搬到 ADR 的內容。
