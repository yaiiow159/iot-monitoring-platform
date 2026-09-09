#!/usr/bin/env node
'use strict';
/** 把目前 HEAD 標記為已審查，pre-push-gate.js 據此放行。標記在 .git/ 底下，不進版控。 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot, git } = require('../../lib/walk');

const root = findRepoRoot();
const gitDir = git(root, 'rev-parse --absolute-git-dir');
const head = git(root, 'rev-parse HEAD');
if (!gitDir || !head) {
  console.error('不在 git repo 裡');
  process.exit(1);
}
fs.writeFileSync(path.join(gitDir, 'claude-push-review-state'), head + '\n');
console.log(`已標記 ${head.slice(0, 7)} 為已審查`);
