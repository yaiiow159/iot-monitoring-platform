#!/usr/bin/env node
'use strict';
/**
 * PreToolUse（Bash）：攔 git push，要求先對即將推送的 commit 跑過 code-review skill。
 * 標記檔放在 .git/ 底下記 HEAD 的 sha；HEAD 一變（新 commit、amend、rebase）標記就失效。
 */

const fs = require('fs');
const path = require('path');
const { findRepoRoot, git } = require('../lib/walk');

const MARKER = 'claude-push-review-state';

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
  if (!/\bgit push\b/.test(command) || /--dry-run|\s-n\b/.test(command)) return;

  const root = findRepoRoot();
  const gitDir = git(root, 'rev-parse --absolute-git-dir');
  const head = git(root, 'rev-parse HEAD');
  if (!gitDir || !head) return;
  const ahead = git(root, 'rev-list --count @{upstream}..HEAD');
  if (ahead === '0') return;

  let reviewed = '';
  try {
    reviewed = fs.readFileSync(path.join(gitDir, MARKER), 'utf8').trim();
  } catch {
    // 沒有標記
  }
  if (reviewed === head) return;

  deny(`即將推送 ${ahead || '?'} 個 commit，但 HEAD ${head.slice(0, 7)} 還沒審查過。\n`
    + '先跑 code-review skill（node .claude/skills/code-review/collect.js 蒐集材料、人工判讀），\n'
    + '審查完成後執行 node .claude/skills/code-review/mark.js 標記，再 push。');
}

main();
