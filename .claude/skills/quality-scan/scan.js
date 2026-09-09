#!/usr/bin/env node
'use strict';
/** 全庫掃描，依規則分組輸出。 */

const { findRepoRoot } = require('../../lib/walk');
const { scanAll, load, fingerprint } = require('../../lib/baseline');

const root = findRepoRoot();
const args = process.argv.slice(2);
let findings = scanAll(root);
if (args.includes('--new')) {
  const baseline = load(root);
  findings = findings.filter((f) => !baseline.has(fingerprint(f.file, f, f.lineText)));
}
if (args.includes('--json')) {
  console.log(JSON.stringify(findings, null, 2));
  process.exit(0);
}
const byRule = new Map();
for (const f of findings) {
  if (!byRule.has(f.rule)) byRule.set(f.rule, []);
  byRule.get(f.rule).push(f);
}
console.log(`共 ${findings.length} 個 findings，${byRule.size} 種規則\n`);
for (const [rule, list] of [...byRule].sort((a, b) => b[1].length - a[1].length)) {
  console.log(`## ${rule}（${list.length}）— ${list[0].message}`);
  for (const f of list.slice(0, 12)) console.log(`  ${f.severity === 'error' ? '✖' : '△'} ${f.file}:${f.line}`);
  if (list.length > 12) console.log(`  …還有 ${list.length - 12} 個`);
  console.log();
}
