import {FieldPath} from 'firebase-admin/firestore';
import {DateTime} from 'luxon';
import {callable,access,requireThat,companyRef,text} from './core.js';
import {metrics,matches,normalize,empty,finish} from './domain.js';
async function context(r,d){
 const {c,m}=await access(r,d.companyId),f=d.filters??{};requireThat(Number.isFinite(f.from)&&Number.isFinite(f.to)&&f.to>f.from&&f.to-f.from<=93*86400000,'Choose a date range of at most 92 days.');
 requireThat(!f.employees||(Array.isArray(f.employees)&&f.employees.length<=100),'Select at most 100 employees.');
 if(m.role==='EMPLOYEE')f.employees=[r.auth.uid];
 let q=companyRef(d.companyId).collection('calls').where('callTimestamp','>=',f.from).where('callTimestamp','<',f.to);
 if(f.employees?.length===1)q=q.where('employeeUid','==',f.employees[0]);
 if(f.phone){f.normalizedPhone=normalize(text(f.phone,100),c.country);q=q.where('normalizedNumber','==',f.normalizedPhone);}
 return {c,m,f,q};
}
export const listCalls=callable(async(r,d)=>{
 const {f,q:base}=await context(r,d);const dir=['OLDEST','SHORTEST'].includes(d.sort)?'asc':'desc',duration=['LONGEST','SHORTEST'].includes(d.sort);let q=base;
 if(duration)q=q.where('durationSeconds','>=',0).orderBy('durationSeconds',dir);
 q=q.orderBy('callTimestamp',dir).orderBy(FieldPath.documentId(),dir);
 if(d.cursor){requireThat(Array.isArray(d.cursor)&&d.cursor.length===(duration?3:2),'Invalid cursor.');q=q.startAfter(...d.cursor);}
 const found=[];let cursor=null,done=false,scanned=0;
 while(found.length<50&&scanned<2000){const page=await q.limit(100).get();if(page.empty){done=true;break;}
 for(const doc of page.docs){scanned++;const c=doc.data();cursor=duration?[c.durationSeconds,c.callTimestamp,doc.id]:[c.callTimestamp,doc.id];if(matches(c,f))found.push({...c,id:doc.id});if(found.length===50)break;}
 if(found.length===50)break;if(page.size<100){done=true;break;}q=q.startAfter(...cursor);
 }
 return {calls:found,cursor:done?null:cursor,hasMore:!done};
});
export const getReport=callable(async(r,d)=>{
 const {c,m,f,q}=await context(r,d),cr=companyRef(d.companyId),members=await (m.role==='EMPLOYEE'?cr.collection('members').where('uid','==',r.auth.uid):cr.collection('members')).get();
 const profiles=members.docs.map(s=>({id:s.id,...s.data()})).filter(x=>x.role!=='OWNER'&&(!f.employees?.length||f.employees.includes(x.id))&&(!f.department||x.department===f.department));
 // Default whole-day reports read precomputed summaries. Arbitrary filters use bounded server queries.
 const start=DateTime.fromMillis(f.from,{zone:c.timeZone}),end=DateTime.fromMillis(f.to,{zone:c.timeZone});
 const simple=!f.department&&!f.types?.length&&!f.connection&&!f.minDuration&&!f.maxDuration&&!f.phone&&start.startOf('day').toMillis()===f.from&&end.startOf('day').toMillis()===f.to;
 let result;
 if(simple){
 const docs=await cr.collection('daily').where('day','>=',start.toISODate()).where('day','<',end.toISODate()).get();
 const selected=docs.docs.filter(s=>s.data().employeeUid!=='ALL'&&(!f.employees?.length||f.employees.includes(s.data().employeeUid)));
 const all=empty(),staff=new Map(profiles.map(p=>[p.id,{...p,...empty(),lastCallAt:0}])),timeline=new Map(),unique=new Set(),staffNumbers=new Map();let count=0;
 for(const s of selected){const v=s.data(),row=staff.get(v.employeeUid)??{id:v.employeeUid,name:'Former employee',...empty(),lastCallAt:0};
 for(const k of Object.keys(empty()).filter(k=>k!=='unique')){all[k]+=v[k]??0;row[k]+=v[k]??0;}row.lastCallAt=Math.max(row.lastCallAt,v.lastCallAt??0);staff.set(v.employeeUid,row);
 const nums=await s.ref.collection('numbers').select().get();count+=nums.size;requireThat(count<=100000,'Narrow the date range for exact unique-number reporting.','resource-exhausted');const own=staffNumbers.get(v.employeeUid)??new Set();for(const n of nums.docs){unique.add(n.id);own.add(n.id);}staffNumbers.set(v.employeeUid,own);
 if(end.diff(start,'days').days<=1){for(const [h,n] of Object.entries(v.hours??{}))timeline.set(h,(timeline.get(h)??0)+n);}else timeline.set(v.day,(timeline.get(v.day)??0)+v.total);
 }
 all.unique=unique.size;result={metrics:finish(all),staff:[...staff.values()].map(v=>finish({...v,unique:staffNumbers.get(v.id)?.size??0})),timeline:[...timeline].sort((a,b)=>a[0].localeCompare(b[0],undefined,{numeric:true})).map(([label,value])=>({label,value})),source:'DAILY_SUMMARIES'};
 }else{
 const rows=[];let query=q.orderBy('callTimestamp').orderBy(FieldPath.documentId()),scanned=0;
 for(;;){const page=await query.limit(500).get();scanned+=page.size;requireThat(scanned<=50000,'This filtered report is too large. Narrow the date range.','resource-exhausted');for(const s of page.docs)if(matches(s.data(),f))rows.push(s.data());if(page.size<500)break;query=query.startAfter(page.docs.at(-1));}
 const staff=profiles.map(p=>({...p,...metrics(rows.filter(x=>x.employeeUid===p.id)),lastCallAt:rows.filter(x=>x.employeeUid===p.id).reduce((v,x)=>Math.max(v,x.callTimestamp),0)})),timeline=new Map();
 for(const x of rows){const key=f.to-f.from<=86400000?String(x.hour):x.callDate;timeline.set(key,(timeline.get(key)??0)+1);}result={metrics:metrics(rows),staff,timeline:[...timeline].sort((a,b)=>a[0].localeCompare(b[0],undefined,{numeric:true})).map(([label,value])=>({label,value})),source:'FILTERED_RAW_CALLS'};
 }
 const devices=await (m.role==='EMPLOYEE'?cr.collection('devices').where('employeeUid','==',r.auth.uid):cr.collection('devices')).get();result.staff=result.staff.map(s=>({...s,lastSyncAt:devices.docs.filter(d=>d.data().employeeUid===s.id&&d.data().active).reduce((n,d)=>Math.max(n,d.data().lastSuccessfulSyncAt??0),0)}));
 return {...result,generatedAt:Date.now()};
});
