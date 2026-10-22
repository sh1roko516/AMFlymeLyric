import fs from 'fs';
function uleb(buf, p) { let r=0,s=0,b; do { b=buf[p++]; r|=(b&0x7f)<<s; s+=7; } while(b&0x80); return [r>>>0,p]; }
function dexString(buf, off) { let p=off; let l; [l,p]=uleb(buf,p); let e=p; while(buf[e]!==0)e++; return buf.toString('utf8', p, e); }
export function definedClasses(path) {
  const buf = fs.readFileSync(path);
  const classDefsSize = buf.readUInt32LE(0x60);
  const classDefsOff  = buf.readUInt32LE(0x64);
  const typeIdsOff    = buf.readUInt32LE(0x44);
  const stringIdsOff  = buf.readUInt32LE(0x3c);
  const out = [];
  for (let i=0;i<classDefsSize;i++) {
    const typeIdx = buf.readUInt32LE(classDefsOff + i*32);
    const strIdx  = buf.readUInt32LE(typeIdsOff + typeIdx*4);
    const strOff  = buf.readUInt32LE(stringIdsOff + strIdx*4);
    out.push(dexString(buf, strOff));
  }
  return out;
}
if (process.argv[2]) console.log(JSON.stringify(definedClasses(process.argv[2])));
