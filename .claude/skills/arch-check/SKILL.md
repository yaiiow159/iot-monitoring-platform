---
name: arch-check
description: 跑六角架構的依賴規則（ArchUnit）與前端型別檢查，確認分層沒有被打穿、契約型別沒有被繞過。當使用者說要檢查架構、確認分層、發版前確認、或改過 pom／模組依賴／types.ts 之後使用。
---

# Arch check

```bash
node .claude/skills/arch-check/run.js
```

做三件事：`ArchitectureTest`（五條分層規則）、領域與應用層的單元測試（不需要 Docker）、`web` 的 `tsc --noEmit`。
任何一項失敗就是紅燈。

## 規則在哪

- `iot-api/src/test/java/com/iotmon/ArchitectureTest.java`：領域層零框架、應用層只認 Port、依賴只能由外往內。
- `.claude/lib/rules.js`：同一組規則的即時版（編輯當下就提醒），但 ArchUnit 才是最終裁判。

## 不要為了讓測試過而放寬規則

規則被放寬過一次就再也收不回來。要用框架的東西，把它推到 infrastructure 或抽成 Port（例：`LiveSubscriber`）。
