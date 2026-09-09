'use strict';
/** 找 repo 根、列出原始碼、讀 git 暫存區與 diff。hooks 與 skills 共用，不依賴任何套件。 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const SOURCE_ROOTS = ['iot-domain/src', 'iot-application/src', 'iot-infrastructure/src', 'iot-api/src',
  'iot-simulator/src', 'web/src'];
const EXTENSIONS = new Set(['.java', '.ts', '.tsx', '.sql', '.yml', '.yaml']);
const SKIP_DIRS = new Set(['node_modules', 'target', 'dist', '.git']);

function findRepoRoot(start = process.env.CLAUDE_PROJECT_DIR || process.cwd()) {
  let dir = path.resolve(start);
  for (let i = 0; i < 8; i++) {
    if (fs.existsSync(path.join(dir, 'pom.xml')) && fs.existsSync(path.join(dir, 'web'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return path.resolve(start);
}

function listSourceFiles(root) {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (SKIP_DIRS.has(entry.name)) continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (EXTENSIONS.has(path.extname(entry.name))) out.push(path.relative(root, full).replace(/\\/g, '/'));
    }
  };
  for (const r of SOURCE_ROOTS) {
    const full = path.join(root, r);
    if (fs.existsSync(full)) walk(full);
  }
  out.push('docker-compose.yml');
  return out.filter((f) => fs.existsSync(path.join(root, f)));
}

function git(root, args) {
  try {
    return execSync(`git ${args}`, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch {
    return '';
  }
}

function stagedFiles(root) {
  return git(root, 'diff --cached --name-only --diff-filter=ACMR').split('\n').filter(Boolean);
}

/** diff 裡「新增的行」：只審這次改動有沒有讓情況變差，既有問題另有去處 */
function addedLines(root, args) {
  const diff = git(root, `diff ${args} --unified=0 --no-color`);
  const result = new Map();
  let file = null;
  let line = 0;
  for (const raw of diff.split('\n')) {
    if (raw.startsWith('+++ ')) {
      file = raw.slice(4).replace(/^b\//, '');
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)/.exec(raw);
    if (hunk) {
      line = Number(hunk[1]);
      continue;
    }
    if (raw.startsWith('+') && !raw.startsWith('+++') && file) {
      if (!result.has(file)) result.set(file, []);
      result.get(file).push({ line, text: raw.slice(1) });
      line++;
    } else if (!raw.startsWith('-') && !raw.startsWith('\\')) {
      line++;
    }
  }
  return result;
}

module.exports = { findRepoRoot, listSourceFiles, stagedFiles, addedLines, git, SOURCE_ROOTS };
