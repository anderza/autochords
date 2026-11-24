const fs = require('node:fs');
const assert = require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
assert.ok(html.trim());
const scripts=[...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)];
assert.ok(scripts.length);
for(const [,script] of scripts){const r=spawnSync(process.execPath,['--check'],{input:script,stdio:['pipe','inherit','inherit']});assert.equal(r.status,0);}
