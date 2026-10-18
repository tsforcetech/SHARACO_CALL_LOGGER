import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {doc,setDoc,getDoc,getDocs,collection,query,where,updateDoc,writeBatch,serverTimestamp} from 'firebase/firestore';

test('Spark CRM rules enforce atomic outcomes and single-use foreground call requests',async t=>{
 const env=await initializeTestEnvironment({projectId:'demo-calltrack-crm-rules',firestore:{rules:await readFile('../firestore.rules','utf8')}});
 const db=uid=>env.authenticatedContext(uid,{email_verified:true,email:`${uid}@example.com`}).firestore();
 const a=db('a'),b=db('b'),owner=db('owner'),path=(d,c,k)=>doc(d,`companies/c/${c}/${k}`);
 const did='11111111-1111-4111-8111-111111111111',pid='22222222-2222-4222-8222-222222222222';
 await env.withSecurityRulesDisabled(async context=>{
  const d=context.firestore();await setDoc(doc(d,'companies/c'),{ownerUid:'owner'});
  for(const [uid,role] of [['a','employee'],['b','employee'],['owner','owner']])await setDoc(path(d,'members',uid),{uid,role,approved:true,activeDeviceId:did});
  await setDoc(path(d,'devices',`a_${did}`),{uid:'a',deviceId:did,status:'active'});
  await setDoc(path(d,'leads','lead'),{leadId:'lead',companyId:'c',assignedTo:'a',primaryPhone:'+971501234567',customerName:'Test',campaignId:'',version:1,status:'new',terminal:false,doNotCall:false,claimExpiresAt:0});
  await setDoc(path(d,'dispositionStatuses','interested'),{statusId:'interested',label:'Interested',active:true,isTerminal:false,requiresRemarks:false,requiresFollowUp:false,countsAsInterested:true,countsAsConversion:false});
 });
 try{
  await t.test('employee own queries succeed; foreign reads and target changes fail',async()=>{
   await assertSucceeds(getDocs(query(collection(a,'companies/c/leads'),where('assignedTo','==','a'))));
   await assertFails(getDoc(path(b,'leads','lead')));
   await assertFails(setDoc(path(a,'targetDefinitions','t'),{employeeUid:'a',value:100,period:'daily',createdAt:serverTimestamp()}));
   await assertSucceeds(setDoc(path(owner,'targetDefinitions','t'),{employeeUid:'a',value:100,period:'daily',createdAt:serverTimestamp()}));
  });
  const pair=()=>({employeeUid:'a',deviceId:did,pairingId:pid,enabled:true,expiresAt:Date.now()+3600000,readyUntil:Date.now()+80000,updatedAt:serverTimestamp()});
  const request=()=>({requestId:'r',employeeUid:'a',deviceId:did,pairingId:pid,leadId:'lead',phoneNumber:'+971501234567',status:'CREATED',createdAt:serverTimestamp(),expiresAt:Date.now()+55000});
  await t.test('pairing binds active device and forbids long readiness',async()=>{
   await assertFails(setDoc(path(a,'webDevicePairings','a'),{...pair(),readyUntil:Date.now()+900000}));
   await assertSucceeds(setDoc(path(a,'webDevicePairings','a'),pair()));
  });
  await t.test('request cannot redirect phone, impersonate another employee, or use wrong pairing',async()=>{
   await assertFails(setDoc(path(a,'callRequests','r'),{...request(),phoneNumber:'+971551234567'}));
   await assertFails(setDoc(path(b,'callRequests','r'),request()));
   await assertFails(setDoc(path(a,'callRequests','r'),{...request(),pairingId:'wrong'}));
   await assertSucceeds(setDoc(path(a,'callRequests','r'),request()));
   await assertFails(updateDoc(path(a,'callRequests','r'),{status:'DIALER_OPENED'}));
   await assertSucceeds(updateDoc(path(a,'callRequests','r'),{status:'ACKNOWLEDGED'}));
   await assertFails(updateDoc(path(a,'callRequests','r'),{status:'ACKNOWLEDGED'}));
   await assertSucceeds(updateDoc(path(a,'webDevicePairings','a'),{readyUntil:0,updatedAt:serverTimestamp()}));
   await assertSucceeds(updateDoc(path(a,'callRequests','r'),{status:'DIALER_OPENED'}));
   await assertFails(updateDoc(path(a,'callRequests','r'),{status:'CREATED'}));
   await assertFails(updateDoc(path(a,'callRequests','r'),{status:'DIALER_OPENED'}));
  });
  await t.test('expired and revoked-device requests cannot be consumed',async()=>{
   await assertSucceeds(setDoc(path(a,'webDevicePairings','a'),pair()));
   await env.withSecurityRulesDisabled(async context=>{await setDoc(path(context.firestore(),'callRequests','expired'),{...request(),requestId:'expired',expiresAt:Date.now()-1000});});
   await assertFails(updateDoc(path(a,'callRequests','expired'),{status:'DIALER_OPENED'}));
   await env.withSecurityRulesDisabled(async context=>{await updateDoc(path(context.firestore(),'devices',`a_${did}`),{status:'inactive'});});
   await assertFails(setDoc(path(a,'callRequests','r2'),{...request(),requestId:'r2'}));
  });
  const expiry=Date.now()+800000;
  await t.test('claim must include corresponding session',async()=>{
   const claim={claimedBy:'a',claimSessionId:'s',claimExpiresAt:expiry};
   await assertFails(updateDoc(path(a,'leads','lead'),claim));
   const batch=writeBatch(a);batch.update(path(a,'leads','lead'),claim);batch.set(path(a,'callingSessions','s'),{employeeUid:'a',leadId:'lead',status:'READY',createdAt:serverTimestamp(),expiresAt:expiry});await assertSucceeds(batch.commit());
  });
  function outcome(verified=false,partial=false){
   const attempt={attemptId:'attempt',companyId:'c',leadId:'lead',employeeUid:'a',campaignId:'',sessionId:'s',dispositionId:'interested',remarks:'Interested in a demo',tags:[],nextFollowUpAt:null,createdAt:serverTimestamp(),verificationStatus:verified?'VERIFIED':'UNVERIFIED',matchedCallId:null,durationSeconds:null,connectionStatus:'UNKNOWN'};
   const batch=writeBatch(a);batch.set(path(a,'crmCallAttempts','attempt'),attempt);
   if(!partial){batch.set(path(a,'leadActivities','attempt'),{...attempt,type:'DISPOSITION'});batch.update(path(a,'callingSessions','s'),{status:'DISPOSITION_SAVED',attemptId:'attempt'});batch.update(path(a,'leads','lead'),{status:'interested',terminal:false,doNotCall:false,remarks:attempt.remarks,tags:[],nextFollowUpAt:null,lastContactAt:serverTimestamp(),updatedAt:serverTimestamp(),version:2,claimedBy:'',claimSessionId:'',claimExpiresAt:0,lastAttemptId:'attempt'});}
   return batch.commit();
  }
  await t.test('forged verification and partial outcomes fail; complete atomic outcome succeeds',async()=>{
   await assertFails(outcome(true));await assertFails(outcome(false,true));await assertSucceeds(outcome());
   assert.equal((await getDoc(path(a,'leads','lead'))).data().version,2);
   await assertFails(updateDoc(path(a,'crmCallAttempts','attempt'),{durationSeconds:90,verificationStatus:'VERIFIED'}));
   await assertFails(outcome());
  });
 }finally{await env.cleanup();}
});
