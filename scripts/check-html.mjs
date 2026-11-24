import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const filename = new URL('../app/src/main/assets/index.html', import.meta.url);
const html = fs.readFileSync(filename, 'utf8');
if (html.trim().length < 1024) throw new Error('index.html is empty or unexpectedly short');
const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)]
  .filter(([whole, attr]) => !/\btype\s*=\s*["'](?:application\/json|application\/ld\+json)["']/i.test(attr));
if (!scripts.length) throw new Error('No JavaScript <script> blocks found');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'autochords-syntax-'));
try {
  scripts.forEach((match, i) => {
    if (!match[2].trim()) throw new Error('Script block ' + (i + 1) + ' is empty');
    const filename = path.join(tmp, 'script-' + i + '.mjs');
    fs.writeFileSync(filename, match[2]);
    const check = spawnSync(process.execPath, ['--check', filename], {encoding: 'utf8'});
    if (check.status !== 0) throw new Error(
      'node --check failed on script ' + (i + 1) + ':\n' + check.stderr);
  });
  console.log('index.html nonempty; node --check passed for ' + scripts.length + ' script(s)');
} finally { fs.rmSync(tmp, {recursive: true, force: true}); }
