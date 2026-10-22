import fs from 'fs';

function uleb(buf, p) {
  let result = 0, shift = 0, b;
  do { b = buf[p++]; result |= (b & 0x7f) << shift; shift += 7; } while (b & 0x80);
  return [result >>> 0, p];
}

export function dexStrings(path) {
  const buf = fs.readFileSync(path);
  if (buf.toString('latin1', 0, 4) !== 'dex\n') throw new Error('not dex: ' + path);
  const stringIdsSize = buf.readUInt32LE(0x38);
  const stringIdsOff = buf.readUInt32LE(0x3c);
  const out = [];
  for (let i = 0; i < stringIdsSize; i++) {
    const off = buf.readUInt32LE(stringIdsOff + i * 4);
    let p = off;
    let utf16len; [utf16len, p] = uleb(buf, p);
    let end = p;
    while (buf[end] !== 0) end++;
    out.push(buf.toString('utf8', p, end));
  }
  return out;
}

if (process.argv[2]) {
  const s = dexStrings(process.argv[2]);
  console.log(JSON.stringify(s));
}
