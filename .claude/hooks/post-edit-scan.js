#!/usr/bin/env node
'use strict';
/**
 * PostToolUse：檔案被 Edit／Write 改動後立刻掃這一個檔案，只回報不在基準線裡的新問題。
 * 不阻擋任何事，只把回饋縮短到「寫完當下就知道」；真正的防線是 commit 前的檢查。
 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot } = require('../lib/walk');
const { scanContent } = require('../lib/rules');
const { load, fingerprint } = require('../lib/baseline');

function main() {
  let payload;
  try {
    payload = JSON.parse(fs.readFileSync(0, 'utf8'));
  } catch {
    return;
  }
  const filePath = payload?.tool_input?.file_path;
  if (!filePath) return;
  const root = findRepoRoot();
  const rel = path.relative(root, filePath).replace(/\\/g, '/');
  if (rel.startsWith('..') || !/\.(java|tsx?|sql|ya?ml)$/.test(rel) || !fs.existsSync(filePath)) return;

  const text = fs.readFileSync(filePath, 'utf8');
  const lines = text.split('\n');
  const baseline = load(root);
  const fresh = scanContent(rel, text).filter((f) => !baseline.has(fingerprint(rel, f, lines[f.line - 1])));
  if (fresh.length === 0) return;

  const shown = fresh.slice(0, 6);
  const out = [`[quality] ${rel} 新引入 ${fresh.length} 個問題：`];
  for (const f of shown) out.push(`  ${f.severity === 'error' ? '✖' : '△'} L${f.line} ${f.rule}：${f.message}`);
  if (fresh.length > shown.length) out.push(`  …還有 ${fresh.length - shown.length} 個，跑 node .claude/skills/quality-scan/scan.js 看全部`);
  process.stdout.write(out.join('\n') + '\n');
}

main();
