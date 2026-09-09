#!/usr/bin/env node
'use strict';
/** 蒐集審查材料：改動範圍、高風險區域、新增行上的 findings。只回報事實，不做判斷。 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot, addedLines, git } = require('../../lib/walk');
const { scanContent } = require('../../lib/rules');

const HIGH_RISK = [
  [/iot-api\/.*\/security\//, '認證與授權'],
  [/db\/migration\//, '資料庫遷移（不可修改已套用的版本）'],
  [/MqttBridge|TelemetryIngestConsumer|TimescaleTelemetryWriter|LiveSessionRegistry|LivePushConsumer/, '每秒上萬次的熱路徑'],
  [/AlarmEngineConsumer|AlarmEvaluator|AlarmRulePrecedence/, '告警引擎'],
  [/MonitoringTree|TreeController|pages\/Tree\.tsx/, '監控樹的順序與上浮'],
  [/docs\/contracts\.md|api\/types\.ts|api\/mock\.ts/, 'API 契約'],
  [/docker-compose\.yml|application\.yml/, '執行環境設定'],
];

function main() {
  const root = findRepoRoot();
  const args = process.argv.slice(2);
  const staged = args.includes('--staged');
  const baseArg = args.find((a) => a.startsWith('--base='));
  const base = baseArg ? baseArg.slice(7) : (git(root, 'rev-parse --abbrev-ref @{upstream}') || 'origin/main');
  const diffArgs = staged ? '--cached' : `${base}...HEAD`;

  console.log(`# 審查材料（${staged ? '已暫存' : `${base}..HEAD`}）\n`);
  const stat = git(root, `diff ${diffArgs} --stat`);
  console.log(stat || '（沒有改動）');
  if (!staged) {
    const commits = git(root, `log --oneline ${base}..HEAD`);
    if (commits) console.log(`\n## commits\n${commits}`);
  }

  const added = addedLines(root, diffArgs);
  const risky = [];
  const findings = [];
  for (const [file, lines] of added) {
    for (const [re, label] of HIGH_RISK) {
      if (re.test(file)) risky.push(`${file} — ${label}`);
    }
    const full = path.join(root, file);
    if (!fs.existsSync(full)) continue;
    const text = fs.readFileSync(full, 'utf8');
    const addedSet = new Set(lines.map((l) => l.line));
    for (const f of scanContent(file, text)) {
      if (addedSet.has(f.line)) findings.push(`${f.severity === 'error' ? '✖' : '△'} ${file}:${f.line} ${f.rule}：${f.message}`);
    }
  }

  console.log('\n## 動到的高風險區域');
  console.log(risky.length ? [...new Set(risky)].map((r) => `- ${r}`).join('\n') : '- 無');
  console.log('\n## 新增行上的 findings');
  console.log(findings.length ? findings.join('\n') : '- 無');
  console.log('\n接著依 SKILL.md 的清單人工判讀；完成後 node .claude/skills/code-review/mark.js');
}

main();
