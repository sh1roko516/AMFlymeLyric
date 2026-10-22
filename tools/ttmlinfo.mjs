import fs from 'fs';
const path = process.argv[2] || 'build/ttml_1.xml';
const x = fs.readFileSync(path, 'utf8');
console.log('len', x.length);
const counts = {};
for (const p of ['ttm:role', 'x-translation', 'x-bg', 'x-pronunciation', '<div', '<p ', '<span', 'itunes:key']) {
  counts[p] = (x.split(p).length - 1);
}
console.log(JSON.stringify(counts));
const divs = x.match(/<div[^>]*>/g) || [];
console.log('DIVS:');
divs.forEach(d => console.log('  ' + d));
const strip = s => s.replace(/<[^>]*>/g, '')
  .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&#39;/g, "'").replace(/&quot;/g, '"').replace(/^\uFEFF/, '');
const ps = [...x.matchAll(/<p [^>]*>([\s\S]*?)<\/p>/g)];
console.log('P count', ps.length);
const rows = ps.map(m => {
  const a = m[0];
  const g = n => { const r = a.match(new RegExp(n + '="([^"]*)"')); return r ? r[1] : ''; };
  return [g('begin'), g('end'), g('itunes:key'), strip(m[1])];
});
rows.forEach(r => console.log(r[0], '|', r[1], '|', r[2], '|', JSON.stringify(r[3])));
