import {DateTime} from 'luxon';
import {onDocumentWritten} from 'firebase-functions/v2/firestore';
import {db,REGION,callable,access,requireThat,id,text,companyRef,memberRef,audit} from './core.js';
import {callId,normalize,TYPES,counters,hash} from './domain.js';
export const registerDevice=callable(async(r,d)=>{
 const {m}=await access(r,d.companyId);requireThat(m.role!=='OWNER'&&m.consentAt,'Employee consent is required.','failed-precondition');const cr=companyRef(d.companyId),dr=cr.collection('devices').doc(id(d.deviceId)),mr=memberRef(cr.id,r.auth.uid);
 await db.runTransaction(async tx=>{const [ms,ds]=await Promise.all([tx.get(mr),tx.get(dr)]);const member=ms.data(),old=ds.data();requireThat(!old||old.employeeUid===r.auth.uid,'Device belongs to another employee.','permission-denied');requireThat(member.status==='ACTIVE','Membership inactive.','permission-denied');
 if(member.activeDeviceId&&member.activeDeviceId!==dr.id){requireThat(d.replace===true,'Another device is active. Choose Replace Existing Device.','failed-precondition');tx.set(cr.collection('devices').doc(member.activeDeviceId),{active:false},{merge:true});}
 tx.set(dr,{employeeUid:r.auth.uid,employeeName:member.name,installationId:dr.id,manufacturer:text(d.manufacturer),model:text(d.model),androidVersion:text(d.androidVersion),appVersion:text(d.appVersion),registeredAt:old?.registeredAt??Date.now(),lastSeenAt:Date.now(),active:true,callLogPermission:d.callLogPermission===true,notificationPermission:d.notificationPermission===true,lastSyncError:''},{merge:true});tx.update(mr,{activeDeviceId:dr.id});audit(tx,cr.id,r.auth.uid,'DEVICE_REGISTERED',dr.id);});return {ok:true};
});
export const deviceHeartbeat=callable(async(r,d)=>{
 const {c,m}=await access(r,d.companyId);requireThat(m.activeDeviceId===d.deviceId,'This device has been replaced.','permission-denied');
 const ref=companyRef(d.companyId).collection('devices').doc(id(d.deviceId));
 await db.runTransaction(async tx=>{const device=(await tx.get(ref)).data();requireThat(device?.active&&device.employeeUid===r.auth.uid,'Device revoked.','permission-denied');
 tx.update(ref,{lastSeenAt:Date.now(),callLogPermission:d.callLogPermission===true,notificationPermission:d.notificationPermission===true,pendingRecordCount:Math.max(0,Number(d.pendingRecordCount)||0),lastSyncError:text(d.error,300),lastSyncAttemptStatus:d.success?'SUCCESS':text(d.phase||'CHECKING'),...(d.success?{lastSuccessfulSyncAt:Date.now()}:{}),appVersion:text(d.appVersion)});});
 return {initialSyncFrom:m.initialSyncFrom,retentionDays:c.retentionDays};
});
export const ingestCalls=callable(async(r,d)=>{
 const {c,m}=await access(r,d.companyId);requireThat(Array.isArray(d.calls)&&d.calls.length>0&&d.calls.length<=100,'Send 1–100 calls per batch.');const cr=companyRef(d.companyId),device=id(d.deviceId),now=Date.now(),cutoff=Math.max(m.initialSyncFrom??now,now-c.retentionDays*86400000);
 const calls=d.calls.map(x=>{
 const row=String(x.androidCallLogId),ts=Number(x.callTimestamp),seconds=Number(x.durationSeconds);requireThat(/^\d{1,20}$/.test(row)&&Number.isSafeInteger(ts)&&ts>=cutoff&&ts<=now+300000,'Call is outside the permitted monitoring period.');requireThat(Number.isInteger(seconds)&&seconds>=0&&seconds<=604800,'Invalid call duration.');
 const phone=text(x.phoneNumber,100);return {id:callId(device,row,ts),companyId:cr.id,employeeUid:r.auth.uid,employeeId:m.employeeId,employeeName:m.name,department:m.department??'',deviceId:device,androidCallLogId:row,phoneNumber:phone,normalizedNumber:normalize(phone,c.country),callType:TYPES.includes(x.callType)?x.callType:'UNKNOWN',callTimestamp:ts,durationSeconds:seconds,callDate:DateTime.fromMillis(ts,{zone:c.timeZone}).toISODate(),hour:DateTime.fromMillis(ts,{zone:c.timeZone}).hour,source:'ANDROID_CALL_LOG',syncVersion:1};});
 requireThat(new Set(calls.map(x=>x.id)).size===calls.length,'Duplicate records in batch.');
 await db.runTransaction(async tx=>{const [ms,ds,cs]=await Promise.all([tx.get(memberRef(cr.id,r.auth.uid)),tx.get(cr.collection('devices').doc(device)),tx.get(cr)]);requireThat(cs.data()?.status==='ACTIVE'&&ms.data()?.status==='ACTIVE'&&ms.data()?.activeDeviceId===device&&ds.data()?.active&&ds.data()?.employeeUid===r.auth.uid,'Device or membership revoked.','permission-denied');
 const existing=await tx.getAll(...calls.map(x=>cr.collection('calls').doc(x.id)));
 calls.forEach((x,i)=>tx.set(cr.collection('calls').doc(x.id),{...x,createdAt:existing[i].data()?.createdAt??now,syncedAt:now}));});return {confirmedIds:calls.map(x=>x.id)};
});
// Read current source state instead of event payload: retries and out-of-order events converge.
export const aggregateCall=onDocumentWritten({document:'companies/{cid}/calls/{callId}',region:REGION,retry:true},async event=>{
 const cr=companyRef(event.params.cid),ref=cr.collection('calls').doc(event.params.callId),con=cr.collection('contributions').doc(event.params.callId);
 await db.runTransaction(async tx=>{
 const [cs,raw,prior]=await Promise.all([tx.get(cr),tx.get(ref),tx.get(con)]);if(cs.data()?.status!=='ACTIVE')return;
 const current=raw.data()??null,old=prior.data()?.call??null;
 const stable=c=>c?JSON.stringify([c.employeeUid,c.callDate,c.hour,c.normalizedNumber,c.callType,c.durationSeconds]):'';
 if(stable(current)===stable(old))return;
 const groups=new Map(),refs=new Map();
 for(const [c,sign] of [[old,-1],[current,1]])if(c){for(const who of ['ALL',c.employeeUid]){
 const key=`${c.callDate}_${who}`;if(!groups.has(key))groups.set(key,{delta:{},day:c.callDate,uid:who,hours:{},last:0});const g=groups.get(key);
 for(const [k,v] of Object.entries(counters(c)))g.delta[k]=(g.delta[k]??0)+sign*v;
 g.hours[c.hour]=(g.hours[c.hour]??0)+sign;g.last=Math.max(g.last,c.callTimestamp);
 if(c.normalizedNumber){const path=`${key}/numbers/${hash(c.normalizedNumber)}`;refs.set(path,(refs.get(path)??0)+sign);}
 }}
 const groupDocs=new Map(),numDocs=new Map();
 for(const key of groups.keys())groupDocs.set(key,await tx.get(cr.collection('daily').doc(key)));
 for(const path of refs.keys())numDocs.set(path,await tx.get(cr.collection('daily').doc(path)));
 for(const [path,delta] of refs){const snap=numDocs.get(path),before=snap.data()?.count??0,after=before+delta;requireThat(after>=0,'Invalid number reference count.','internal');const key=path.split('/')[0],g=groups.get(key);g.unique=(g.unique??0)+(after>0?1:0)-(before>0?1:0);if(after)tx.set(snap.ref,{count:after});else tx.delete(snap.ref);}
 for(const [key,g] of groups){const snap=groupDocs.get(key),v=snap.data()??{},next={day:g.day,employeeUid:g.uid,updatedAt:Date.now(),lastCallAt:Math.max(v.lastCallAt??0,g.last),unique:(v.unique??0)+(g.unique??0),hours:{...(v.hours??{})}};
 for(const [k,delta] of Object.entries(g.delta))next[k]=(v[k]??0)+delta;
 for(const [h,delta] of Object.entries(g.hours))next.hours[h]=(next.hours[h]??0)+delta;
 if(next.total===0)tx.delete(snap.ref);else tx.set(snap.ref,next);
 }
 if(current)tx.set(con,{call:current});else tx.delete(con);
 });
});
