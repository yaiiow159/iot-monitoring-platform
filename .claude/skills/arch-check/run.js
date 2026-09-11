#!/usr/bin/env node
'use strict';
/** ArchUnit ＋ 領域／應用層單元測試 ＋ 前端型別檢查與 lint。任一失敗就以非零結束。 */

const { execSync } = require('child_process');
const path = require('path');
const { findRepoRoot } = require('../../lib/walk');

const root = findRepoRoot();
const steps = [
  ['ArchUnit 與領域／應用層測試', 'mvn -q -pl iot-domain,iot-application,iot-api -am test -Dtest=ArchitectureTest,*Test -Dsurefire.failIfNoSpecifiedTests=false', root],
  ['前端型別檢查', 'npm run -s typecheck', path.join(root, 'web')],
  ['前端 lint', 'npm run -s lint', path.join(root, 'web')],
];
let failed = 0;
for (const [label, cmd, cwd] of steps) {
  process.stdout.write(`▶ ${label} … `);
  try {
    execSync(cmd, { cwd, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
    console.log('通過');
  } catch (e) {
    failed++;
    console.log('失敗');
    console.log((e.stdout || '') + (e.stderr || ''));
  }
}
process.exit(failed ? 1 : 0);
