#!/usr/bin/env node
'use strict';
/**
 * PreToolUse（Bash）：攔 git commit。暫存區裡有 error 等級的 finding（密鑰、分層違規、前端自行排序）就擋下；
 * warn 等級只列出來不擋——擋 warn 會讓人開始繞過 hook。
 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot, stagedFiles } = require('../lib/walk');
const { scanContent } = require('../lib/rules');
const { load, fingerprint } = require('../lib/baseline');

function deny(reason) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason },
  }));
  process.exit(0);
}

function main() {
  let payload;
  try {
    payload = JSON.parse(fs.readFileSync(0, 'utf8'));
  } catch {
    return;
  }
  const command = String(payload?.tool_input?.command || '');
  if (!/\bgit commit\b/.test(command)) return;

  const root = findRepoRoot();
  const baseline = load(root);
  const errors = [];
  const warns = [];
  for (const file of stagedFiles(root)) {
    if (!/\.(java|tsx?|sql|ya?ml)$/.test(file)) continue;
    const full = path.join(root, file);
    if (!fs.existsSync(full)) continue;
    const text = fs.readFileSync(full, 'utf8');
    const lines = text.split('\n');
    for (const f of scanContent(file, text)) {
      if (baseline.has(fingerprint(file, f, lines[f.line - 1]))) continue;
      (f.severity === 'error' ? errors : warns).push(`${file}:${f.line} ${f.rule}：${f.message}`);
    }
  }

  if (errors.length > 0) {
    deny(`commit 被擋下，暫存區有 ${errors.length} 個 error 等級的問題：\n` + errors.slice(0, 8).join('\n')
      + (warns.length ? `\n另有 ${warns.length} 個提醒。` : '') + '\n修正後再 commit；確定是既有問題就跑 node .claude/lib/baseline.js --update。');
  }
  if (warns.length > 0) {
    process.stdout.write(`[quality] 暫存區有 ${warns.length} 個提醒（不擋 commit）：\n` + warns.slice(0, 8).join('\n') + '\n');
  }
}

main();
