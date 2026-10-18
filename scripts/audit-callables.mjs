import { readFileSync, readdirSync } from 'node:fs';
const files = dir => readdirSync(dir,{withFileTypes:true}).flatMap(e => e.isDirectory()?files(`${dir}/${e.name}`):[`${dir}/${e.name}`]);
const app = files('app/src/main/java').filter(p=>p.endsWith('.kt')).map(p=>readFileSync(p,'utf8')).join('\n');
const refs = [...new Set([...app.matchAll(/(?:backend\.call|model\.action|\.getHttpsCallable)\(\s*"(\w+)"/g)].map(m=>m[1]))].sort();
const source = files('functions/src').map(p=>readFileSync(p,'utf8')).join('\n');
const exports = [...source.matchAll(/export const (\w+) = callable/g)].map(m=>m[1]);
const direct = ['registerDevice','deviceHeartbeat','ingestCalls','createCompany','saveEmployee','createInvite','inspectInvite','joinCompany','approveJoin','revokeInvite','getReport','listCalls'];
console.log(JSON.stringify(refs.map(name=>({name,transport:direct.includes(name)?'Firestore':'Callable',sourceExport:exports.includes(name)})),null,2));
if(process.argv.includes('--live')) {
 await Promise.all(refs.filter(n=>n!=='approveJoin').map(async name=>{
  try { const r=await fetch(`https://europe-west1-sharaco-call-logger.cloudfunctions.net/${name}`,{signal:AbortSignal.timeout(15000)});
   console.log(`${name}: HTTP ${r.status}`);
  } catch(e) { console.log(`${name}: UNVERIFIED (${e.name})`); }
 }));
}
