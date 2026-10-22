
const { DatabaseSync } = require('node:sqlite');
const p = 'I:/AMFlymeLyric/lsposed/db/modules_config.db';
let db;
try { db = new DatabaseSync(p, { readOnly: true }); }
catch (e) { console.log('RO open failed: ' + e.message); db = new DatabaseSync(p); }
const t = db.prepare("SELECT type,name,sql FROM sqlite_master").all();
for (const r of t) console.log(r.type + ' | ' + r.name + ' | ' + String(r.sql).slice(0, 400));
console.log('--- tables row counts ---');
for (const r of t.filter(x => x.type === 'table' && x.name.startsWith('sqlite') === false)) {
  try { const c = db.prepare('SELECT count(*) c FROM "' + r.name + '"').get(); console.log(r.name + ': ' + c.c); } catch (e) { console.log(r.name + ': ERR ' + e.message); }
}
console.log('--- modules rows ---');
try {
  const rows = db.prepare('SELECT * FROM modules').all();
  for (const r of rows) console.log(JSON.stringify(r).slice(0, 300));
} catch (e) { console.log('modules err ' + e.message); }
