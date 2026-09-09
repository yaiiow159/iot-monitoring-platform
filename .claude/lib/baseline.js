'use strict';
/**
 * 基準線：既有的 findings 記在 .claude/config/scan-baseline.json，hooks 只回報「新出現的」。
 * 沒有基準線的話，每次編輯都會被一整串舊問題淹沒，真正新引入的那一條反而沒人看。
 * 修完一批舊問題後跑 `node .claude/lib/baseline.js --update` 讓基準線跟著縮小。
 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot, listSourceFiles } = require('./walk');
const { scanContent } = require('./rules');

const BASELINE_PATH = '.claude/config/scan-baseline.json';

/** 指紋不含行號：行號會因為上面多了一行而全部漂移 */
function fingerprint(file, finding, lineText) {
  return `${file}|${finding.rule}|${(lineText || '').trim().slice(0, 80)}`;
}

function load(root) {
  try {
    return new Set(JSON.parse(fs.readFileSync(path.join(root, BASELINE_PATH), 'utf8')).fingerprints);
  } catch {
    return new Set();
  }
}

function scanAll(root) {
  const all = [];
  for (const file of listSourceFiles(root)) {
    const text = fs.readFileSync(path.join(root, file), 'utf8');
    const lines = text.split('\n');
    for (const f of scanContent(file, text)) {
      all.push({ file, ...f, lineText: lines[f.line - 1] || '' });
    }
  }
  return all;
}

function update(root) {
  const findings = scanAll(root);
  const fingerprints = [...new Set(findings.map((f) => fingerprint(f.file, f, f.lineText)))].sort();
  const target = path.join(root, BASELINE_PATH);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, JSON.stringify({ updatedAt: new Date().toISOString(), count: fingerprints.length, fingerprints }, null, 2) + '\n');
  return fingerprints.length;
}

if (require.main === module) {
  const root = findRepoRoot();
  if (process.argv.includes('--update')) {
    console.log(`基準線已更新：${update(root)} 筆既有 findings`);
  } else {
    console.log(`基準線：${load(root).size} 筆（--update 重新產生）`);
  }
}

module.exports = { fingerprint, load, scanAll, update, BASELINE_PATH };
