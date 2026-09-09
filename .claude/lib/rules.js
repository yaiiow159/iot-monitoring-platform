'use strict';
/**
 * 掃描規則。每條規則回答一個問題：「這段程式碼有沒有繞過專案已經有的共用做法或分層原則」。
 * severity：error 會擋 commit，warn 只提醒。規則要少而準——誤報一多，所有人就不看了。
 */

const path = require('path');

const MAX_COMMENT_LINES = 8;

function layerOf(file) {
  if (file.startsWith('iot-domain/')) return 'domain';
  if (file.startsWith('iot-application/')) return 'application';
  if (file.startsWith('iot-infrastructure/')) return 'infrastructure';
  if (file.startsWith('iot-api/')) return 'api';
  if (file.startsWith('iot-simulator/')) return 'simulator';
  if (file.startsWith('web/')) return 'web';
  return 'other';
}

function isTest(file) {
  return /\/src\/test\//.test(file) || /\.test\.tsx?$/.test(file);
}

/** 找出每個註解區塊的起訖行。只認獨立成行的 /*，字串裡的 "/actuator/**" 不算。 */
function commentBlocks(text) {
  const blocks = [];
  const re = /(?:^|\n)[ \t]*\/\*[\s\S]*?\*\//g;
  let m;
  while ((m = re.exec(text))) {
    const start = text.slice(0, m.index).split('\n').length + (m[0].startsWith('\n') ? 1 : 0);
    const lines = m[0].replace(/^\n/, '').split('\n').length;
    blocks.push({ start, lines });
  }
  return blocks;
}

const RULES = [
  // ---------- 分層
  {
    id: 'domain-framework-import', severity: 'error', layers: ['domain'],
    test: /^import (org\.springframework|com\.fasterxml\.jackson|org\.apache\.kafka|com\.hivemq|io\.micrometer|org\.slf4j)\./m,
    message: '領域層不得依賴框架（ArchUnit 也會擋）。規則用拋例外或回傳值表達，不寫日誌、不碰註解。',
  },
  {
    id: 'application-outer-import', severity: 'error', layers: ['application'],
    test: /^import (org\.springframework\.(web|jdbc|data|kafka)|com\.iotmon\.(infrastructure|api))\./m,
    message: '應用層只認得 Port 介面與 spring-context／spring-tx，不得依賴 web、jdbc、kafka 或外層模組。',
  },
  {
    id: 'infrastructure-imports-api', severity: 'error', layers: ['infrastructure'],
    test: /^import com\.iotmon\.api\./m,
    message: '基礎設施層不得依賴 API 層；需要傳輸層的東西時抽 Port 介面（例：LiveSubscriber）。',
  },
  // ---------- API 層：共用做法
  {
    id: 'controller-try-catch', severity: 'warn', layers: ['api'], files: /\/(rest|security)\/.*\.java$/,
    exclude: /(Params|ApiExceptionHandler)\.java$/,
    test: /catch \((IllegalArgumentException|InvalidDataAccessApiUsageException)\b/,
    message: '例外轉譯交給 ApiExceptionHandler：控制器直接拋 IllegalArgumentException／ApiException 即可。',
  },
  {
    id: 'controller-message-map', severity: 'warn', layers: ['api'], files: /\/rest\/.*\.java$/,
    exclude: /ApiExceptionHandler\.java$/,
    test: /Map\.of\("message"/,
    message: '錯誤回應的形狀由 ApiExceptionHandler 統一產生，不要在控制器手組 {"message": ...}。',
  },
  {
    id: 'enum-valueof-raw', severity: 'warn', layers: ['api'], files: /\.java$/,
    test: /\b[A-Z][A-Za-z]+\.valueOf\([^)]*toUpperCase\(\)\)/,
    message: 'Enum.valueOf 的錯誤訊息會漏 Java 類別名，改用 Params.enumOf(type, raw, 標籤)。',
  },
  // ---------- 基礎設施層：JDBC 共用
  {
    id: 'raw-wasnull', severity: 'warn', layers: ['infrastructure'], files: /\.java$/, exclude: /Rows\.java$/,
    test: /\.wasNull\(\)/,
    message: '可空欄位用 Rows.nullableLong／nullableShort／nullableDouble，不要各自寫 getX + wasNull。',
  },
  {
    id: 'raw-placeholders', severity: 'warn', layers: ['infrastructure'], files: /\.java$/, exclude: /Rows\.java$/,
    test: /Collections\.nCopies\([^)]*"\?"\)/,
    message: 'IN 子句的佔位符用 Rows.placeholders(n)。',
  },
  {
    id: 'raw-timestamp-instant', severity: 'warn', layers: ['infrastructure'], files: /\.java$/, exclude: /Rows\.java$/,
    test: /getTimestamp\([^)]*\)\s*\.toInstant\(\)|Timestamp \w+ = rs\.getTimestamp/,
    message: '時間戳轉 Instant 用 Rows.instant(rs, 欄位)，null 也一併處理。',
  },
  {
    id: 'select-star', severity: 'warn', layers: ['infrastructure'], files: /\.java$/,
    test: /SELECT \* FROM/i,
    message: 'SELECT * 會把之後新增的欄位一起撈回來；列出需要的欄位。',
  },
  // ---------- 通用 Java
  {
    id: 'sysout', severity: 'warn', layers: ['domain', 'application', 'infrastructure', 'api', 'simulator'], files: /\.java$/,
    test: /System\.(out|err)\.print/,
    message: '用 slf4j Logger，不要 System.out。',
  },
  {
    id: 'empty-catch', severity: 'warn', layers: ['domain', 'application', 'infrastructure', 'api', 'simulator'], files: /\.java$/,
    test: /catch \([^)]*\)\s*\{\s*\}/,
    message: '空的 catch 會把問題吞掉；至少記一行日誌或加上一句為什麼可以忽略。',
  },
  {
    id: 'field-autowired', severity: 'warn', files: /\.java$/,
    test: /@Autowired\s+private/,
    message: '用建構子注入，欄位注入沒辦法在測試裡不靠 Spring 建物件。',
  },
  // ---------- 前端
  {
    id: 'console-log', severity: 'warn', layers: ['web'], files: /\.tsx?$/,
    test: /console\.(log|debug)\(/,
    message: '除錯輸出不要留在程式碼裡。',
  },
  {
    id: 'any-type', severity: 'warn', layers: ['web'], files: /\.tsx?$/,
    test: /:\s*any\b|as any\b/,
    message: 'any 會讓契約型別失效；types.ts 是 contracts.md 的投影，補正確的型別。',
  },
  {
    id: 'inline-fetch', severity: 'warn', layers: ['web'], files: /\.tsx?$/, exclude: /api\/client\.ts$/,
    test: /\bfetch\(/,
    message: '所有請求走 api.*（client.ts）：Authorization、401 處理、錯誤訊息解析都在那裡。',
  },
  {
    id: 'tree-client-sort', severity: 'error', layers: ['web'], files: /pages\/Tree\.tsx$/,
    test: /\.children\.sort\(|\.sort\(\(a, b\) => a\.sortOrder/,
    message: '子節點順序是後端的保證（依 sortOrder, id），前端不得再排序。',
  },
  {
    id: 'form-local-submit', severity: 'warn', layers: ['web'], files: /pages\/.*\.tsx$/, exclude: /Login\.tsx$/,
    test: /const \[submitting, setSubmitting\] = useState|setBusy\(true\);\s*\n\s*setError\(null\)/,
    message: '表單的送出／忙碌／錯誤狀態用 components/forms.tsx 的 useSubmit 與 <Feedback>。',
  },
  // ---------- 密鑰
  {
    id: 'private-key', severity: 'error',
    test: /-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----/,
    message: '私鑰不得進版控。',
  },
  {
    id: 'aws-key', severity: 'error',
    test: /\bAKIA[0-9A-Z]{16}\b/,
    message: 'AWS access key 不得進版控。',
  },
  {
    id: 'hardcoded-secret', severity: 'error', files: /\.(java|ts|tsx)$/,
    test: /(password|secret|apiKey|api_key|token)\s*=\s*"[^"$]{12,}"/i,
    exclude: /(Test|Bootstrap|DUMMY_HASH|LoginController)/,
    message: '程式碼裡不放密碼或金鑰；走設定檔與環境變數（application.yml 的 ${...:預設值} 寫法）。',
  },
];

/** 對一個檔案跑全部規則，回傳 findings [{rule, severity, line, message}] */
function scanContent(file, text) {
  const layer = layerOf(file);
  const findings = [];
  if (isTest(file)) return findings;

  for (const rule of RULES) {
    if (rule.layers && !rule.layers.includes(layer)) continue;
    if (rule.files && !rule.files.test(file)) continue;
    if (rule.exclude && rule.exclude.test(file)) continue;
    const re = new RegExp(rule.test.source, rule.test.flags.includes('g') ? rule.test.flags : rule.test.flags + 'g');
    let m;
    while ((m = re.exec(text))) {
      const line = text.slice(0, m.index).split('\n').length;
      const snippet = text.split('\n')[line - 1] || '';
      if (rule.exclude && rule.exclude.test(snippet)) continue;
      findings.push({ rule: rule.id, severity: rule.severity, line, message: rule.message });
      if (!re.global) break;
    }
  }

  if (/\.(java|tsx?)$/.test(file)) {
    for (const block of commentBlocks(text)) {
      if (block.lines > MAX_COMMENT_LINES) {
        findings.push({
          rule: 'long-comment', severity: 'warn', line: block.start,
          message: `註解 ${block.lines} 行。只留「為什麼」（一到兩句），推導過程與數字搬到 docs/adr 或 performance.md。`,
        });
      }
    }
  }
  return findings;
}

module.exports = { RULES, scanContent, layerOf, MAX_COMMENT_LINES };
