import { collection, doc, getDocFromServer, getDocsFromServer, query, where, orderBy, documentId, limit, startAfter, runTransaction, serverTimestamp, writeBatch, Timestamp, onSnapshot } from 'firebase/firestore';
import { firebase, Row } from './firebase';

const statusNames = [['interested','Interested'],['not_interested','Not interested'],['call_back','Call back later'],['no_answer','No answer'],['busy','Busy'],['wrong_number','Wrong number'],['unreachable','Unreachable'],['qualified','Qualified lead'],['appointment','Appointment booked'],['converted','Converted'],['do_not_call','Do not call']];
export const plain = (data:Record<string,unknown>):Record<string,unknown> => Object.fromEntries(Object.entries(data).map(([k,v])=>[k,v instanceof Timestamp?v.toMillis():v]));
function check(ok:unknown,message:string):asserts ok {if(!ok)throw new Error(message);}
export function watchRows(cid:string,kind:string,uid:string,admin:boolean,changed:(rows:Row[],next:string|null)=>void,failed:(e:Error)=>void){
 const constraints=[];
 if(!admin&&kind!=='dispositionStatuses')constraints.push(where(kind==='leads'?'assignedTo':'employeeUid','==',uid));
 constraints.push(orderBy(documentId()),limit(51));
 return onSnapshot(query(collection(firebase().db,'companies',cid,kind),...constraints),s=>{const rows=s.docs.slice(0,50).map(d=>({...plain(d.data()),id:d.id}));changed(rows,s.size>50?rows.at(-1)!.id:null);},failed);
}
export async function sparkApi(cid:string,path:string,input?:unknown,operationKey?:string):Promise<any> {
 const {auth,db}=firebase(),uid=auth.currentUser?.uid;
 check(uid,'UNAUTHENTICATED: Sign in again.');check(/^[\w-]+$/.test(cid),'INVALID_COMPANY');
 const ref=(name:string,id:string)=>doc(db,'companies',cid,name,id);
 const member=await getDocFromServer(ref('members',uid));check(member.data()?.approved,'MEMBERSHIP_REQUIRED: Company access is not active.');
 const admin=['owner','admin'].includes(member.data()!.role);
 const url=new URL(path,'https://local.invalid'),action=url.pathname.slice(1);
 if(input===undefined){
  check(['leads','campaigns','targetDefinitions','followUps','crmCallAttempts','dispositionStatuses','callingSessions','callRequests'].includes(action),'INVALID_COLLECTION');
  const constraints=[];
  if(!admin&&action!=='dispositionStatuses')constraints.push(where(action==='leads'?'assignedTo':'employeeUid','==',uid));
  if(action==='callRequests'&&admin)constraints.push(where('employeeUid','==',uid));
  constraints.push(orderBy(documentId()));
  const after=url.searchParams.get('after');if(after)constraints.push(startAfter(after));
  const count=Math.min(100,Math.max(1,Number(url.searchParams.get('limit')||50)));constraints.push(limit(count+1));
  const snap=await getDocsFromServer(query(collection(db,'companies',cid,action),...constraints));
  const page=snap.docs.slice(0,count);return {rows:page.map(d=>({...plain(d.data()),id:d.id})),next:snap.size>count?page.at(-1)!.id:null};
 }
 const body=input as Record<string,any>,key=operationKey||crypto.randomUUID();
 check(/^[\w-]{1,128}$/.test(key),'INVALID_OPERATION_KEY');
 if(action==='initialize'){
  check(admin,'ADMIN_REQUIRED');
  for(const [statusId,label] of statusNames)await runTransaction(db,async tx=>{
   const r=ref('dispositionStatuses',statusId);if((await tx.get(r)).exists())return;
   tx.set(r,{statusId,label,active:true,isTerminal:['not_interested','wrong_number','converted','do_not_call'].includes(statusId),requiresRemarks:false,requiresFollowUp:['call_back','appointment'].includes(statusId),countsAsInterested:statusId==='interested',countsAsConversion:statusId==='converted'});
  });return {initialized:true};
 }
 if(action==='campaigns'){
  check(admin,'ADMIN_REQUIRED');const b=writeBatch(db);b.set(ref('campaigns',key),{name:body.name,description:body.description||'',status:body.status||'draft',employeeUids:[],createdAt:serverTimestamp()});await b.commit();return {id:key};
 }
 if(action==='claim')return runTransaction(db,async tx=>{
  const r=ref('leads',body.leadId),s=await tx.get(r),lead=s.data();
  check(lead?.assignedTo===uid,'LEAD_ACCESS_DENIED: Only the assigned employee can work this lead.');
  check(!lead.terminal&&!lead.doNotCall,'LEAD_CLOSED: This lead cannot be called.');
  check(!lead.claimExpiresAt||lead.claimExpiresAt<Date.now()||lead.claimedBy===uid,'LEAD_BUSY: Another session owns this lead.');
  const expiresAt=Date.now()+14*60*1000;
  tx.update(r,{claimedBy:uid,claimSessionId:key,claimExpiresAt:expiresAt});
  tx.set(ref('callingSessions',key),{employeeUid:uid,leadId:body.leadId,status:'READY',createdAt:serverTimestamp(),expiresAt});
  return {sessionId:key,lead:{...plain(lead),id:s.id},expiresAt};
 });
 if(action==='dispositions')return runTransaction(db,async tx=>{
  const r=ref('leads',body.leadId),lead=(await tx.get(r)).data();
  check(lead?.assignedTo===uid,'LEAD_ACCESS_DENIED: Lead is no longer assigned to you.');
  // The lead records the last atomic operation. A retry never creates a second attempt.
  if(lead.lastAttemptId===key){
   const saved=(await tx.get(ref('crmCallAttempts',key))).data();
   check(saved?.sessionId===body.sessionId&&saved?.dispositionId===body.dispositionId&&saved?.remarks===(body.remarks||'')&&saved?.nextFollowUpAt===(body.nextFollowUpAt||null)&&JSON.stringify(saved?.tags)===JSON.stringify(body.tags||[]),'IDEMPOTENCY_CONFLICT: This action was already saved with different details.');
   return {attemptId:key,status:'DISPOSITION_SAVED',verificationStatus:'UNVERIFIED'};
  }
  check(lead.version===body.version&&lead.claimSessionId===body.sessionId,'LEAD_CONFLICT: Reopen the lead before saving.');
  const status=(await tx.get(ref('dispositionStatuses',body.dispositionId))).data();check(status?.active,'INVALID_STATUS');
  const attempt={attemptId:key,companyId:cid,leadId:body.leadId,employeeUid:uid,campaignId:lead.campaignId||'',sessionId:body.sessionId,dispositionId:body.dispositionId,remarks:body.remarks||'',tags:body.tags||[],nextFollowUpAt:body.nextFollowUpAt||null,createdAt:serverTimestamp(),verificationStatus:'UNVERIFIED',matchedCallId:null,durationSeconds:null,connectionStatus:'UNKNOWN'};
  tx.set(ref('crmCallAttempts',key),attempt);tx.set(ref('leadActivities',key),{...attempt,type:'DISPOSITION'});
  tx.update(r,{status:body.dispositionId,terminal:status.isTerminal,doNotCall:body.dispositionId==='do_not_call',remarks:attempt.remarks,tags:attempt.tags,nextFollowUpAt:attempt.nextFollowUpAt,lastContactAt:serverTimestamp(),updatedAt:serverTimestamp(),version:lead.version+1,claimedBy:'',claimSessionId:'',claimExpiresAt:0,lastAttemptId:key});
  tx.update(ref('callingSessions',body.sessionId),{status:'DISPOSITION_SAVED',attemptId:key});
  if(attempt.nextFollowUpAt)tx.set(ref('followUps',key),{leadId:body.leadId,employeeUid:uid,dueAt:attempt.nextFollowUpAt,status:'pending',remarks:attempt.remarks,createdAt:serverTimestamp()});
  return {attemptId:key,status:'DISPOSITION_SAVED',verificationStatus:'UNVERIFIED'};
 });
 if(action==='call')return runTransaction(db,async tx=>{
  const pair=(await tx.get(ref('webDevicePairings',uid))).data();
  check(pair?.enabled&&pair.readyUntil>Date.now()&&pair.expiresAt>Date.now(),'PHONE_NOT_READY: Enable web calling on your Android phone and keep it open.');
  const lead=(await tx.get(ref('leads',body.leadId))).data();check(lead?.assignedTo===uid&&!lead.terminal&&!lead.doNotCall,'LEAD_ACCESS_DENIED');
  tx.set(ref('callRequests',key),{requestId:key,employeeUid:uid,deviceId:pair.deviceId,pairingId:pair.pairingId,leadId:body.leadId,phoneNumber:lead.primaryPhone,status:'CREATED',createdAt:serverTimestamp(),expiresAt:Date.now()+55000});
  return {requestId:key};
 });
 throw new Error('NOT_IMPLEMENTED: This operation is not available in the Spark workspace yet.');
}
