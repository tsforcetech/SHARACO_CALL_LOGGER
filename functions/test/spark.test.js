import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, writeBatch, setDoc, updateDoc, serverTimestamp, Timestamp, query, where, onSnapshot } from 'firebase/firestore';

test('Spark onboarding: atomic creation, invitations, approval, isolation and adversarial writes', async t => {
 const env = await initializeTestEnvironment({projectId:'demo-calltrack',firestore:{rules:await readFile('../firestore.rules','utf8')}});
 await env.clearFirestore();
 const account = (uid, email = `${uid}@example.com`, verified = true) => env.authenticatedContext(uid,{email,email_verified:verified}).firestore();
 const owner = account('owner'), staff = account('staff'), intruder = account('intruder'), admin = account('admin');
 const ref = (db,p) => doc(db,p);
 const idx = (role,status) => ({name:'Sharaco',role,status,updatedAt:serverTimestamp()});
 const member = (uid,role) => ({uid,role,approved:true,name:uid,email:`${uid}@example.com`,sim:''});
 const company = () => ({name:'Sharaco',ownerUid:'owner',joinToken:'A'.repeat(32),createdAt:serverTimestamp()});
 const code = 'B'.repeat(32);
 const request = (uid, token=code) => ({uid,name:uid,sim:'',token,status:'pending',requestedAt:serverTimestamp(),email:`${uid}@example.com`,employeeId:'employee'});
 function create(db, cid='c', complete=true) {
  const b=writeBatch(db); b.set(ref(db,`companies/${cid}`),company());
  if(complete){b.set(ref(db,`companies/${cid}/members/owner`),member('owner','owner'));b.set(ref(db,`users/owner/companies/${cid}`),idx('owner','approved'));b.set(ref(db,`companies/${cid}/settings/profile`),{timeZone:'Asia/Dubai'});}
  return b.commit();
 }
 async function invite(token=code, email='staff@example.com', expires=Date.now()+86400000) {
  const b=writeBatch(owner);
  b.set(ref(owner,`invitations/${token}`),{companyId:'c',employeeId:'employee',companyName:'Sharaco',employeeName:'staff',employeeCode:'S1',email,expiresAt:Timestamp.fromMillis(expires),revoked:false,claimedBy:''});
  b.update(ref(owner,'companies/c/employees/employee'),{inviteCode:token});return b.commit();
 }
 function join(db,uid='staff',token=code) {
  const b=writeBatch(db);b.update(ref(db,`invitations/${token}`),{claimedBy:uid});
  b.set(ref(db,`companies/c/join_requests/${uid}`),request(uid,token));
  b.set(ref(db,`users/${uid}/companies/c`),idx('employee','pending'));return b.commit();
 }
 function approve(db,role='employee') {
  const b=writeBatch(db);b.set(ref(db,'companies/c/members/staff'),member('staff',role));
  b.set(ref(db,'users/staff/companies/c'),idx(role,'approved'));
  b.update(ref(db,'companies/c/join_requests/staff'),{status:'approved'});
  b.update(ref(db,'companies/c/employees/employee'),{uid:'staff',status:'ACTIVE'});return b.commit();
 }
 try {
  await t.test('partial company batch rejected, complete batch accepted',async()=>{
   await assertFails(create(owner,'partial',false));
   await assertSucceeds(create(owner));
   assert.equal((await getDoc(ref(owner,'users/owner/companies/c'))).data().role,'owner');
   await assertSucceeds(getDocs(collection(owner,'users/owner/companies')));
   await assertSucceeds(getDoc(ref(owner,'companies/c/settings/profile')));
  });
  await t.test('cannot impersonate owner or create unverified company',async()=>{
   await assertFails(create(intruder,'fake'));
   await assertFails(create(account('owner','owner@example.com',false),'unverified'));
  });
  await t.test('create account-bound high-entropy invitation',async()=>{
   await setDoc(ref(owner,'companies/c/employees/employee'),{name:'staff',code:'S1',email:'staff@example.com',mobile:'',department:'Sales',designation:'Agent',status:'INVITED',uid:''});
   await assertFails(invite('123456'));
   await assertFails(invite('C'.repeat(32),'staff@example.com',Date.now()-1000));
   await assertSucceeds(invite());
   await assertSucceeds(getDoc(ref(staff,`invitations/${code}`)));
   await assertFails(getDoc(ref(intruder,`invitations/${code}`)));
   await assertFails(getDocs(collection(staff,'invitations')));
   await assertFails(getDoc(ref(account('staff','staff@example.com',false),`invitations/${code}`)));
  });
  await t.test('claim alone and wrong email fail; request batch succeeds',async()=>{
   await assertFails(updateDoc(ref(staff,`invitations/${code}`),{claimedBy:'staff'}));
   await assertFails(join(intruder,'intruder'));
   await assertSucceeds(join(staff));
   assert.equal((await getDoc(ref(staff,'users/staff/companies/c'))).data().status,'pending');
   await assertSucceeds(getDoc(ref(staff,'companies/c/join_requests/staff')));
   await assertFails(getDoc(ref(staff,'companies/c')));
   await assertFails(getDocs(collection(staff,'companies/c/call_logs')));
   assert.equal((await getDoc(ref(staff,'companies/c/members/staff'))).exists(),false);
  });
  await t.test('replay, self-approval and partial admin approval fail',async()=>{
   await assertFails(join(staff));
   await assertFails(approve(staff));
   await assertFails(updateDoc(ref(owner,'companies/c/join_requests/staff'),{status:'approved'}));
   await assertFails(approve(owner,'admin'));
   assert.equal((await getDoc(ref(staff,'users/staff/companies/c'))).data().status,'pending');
  });
  await t.test('administrator approval commits all records and unlocks dashboard',async()=>{
   await env.withSecurityRulesDisabled(async c=>setDoc(ref(c.firestore(),'companies/c/members/admin'),member('admin','admin')));
   await assertSucceeds(getDocs(collection(admin,'companies/c/join_requests')));
   await assertSucceeds(approve(admin));
   await assertSucceeds(getDoc(ref(staff,'companies/c')));
   await assertSucceeds(getDoc(ref(staff,'companies/c/settings/profile')));
   assert.equal((await getDoc(ref(staff,'companies/c/members/staff'))).data().approved,true);
   assert.equal((await getDoc(ref(staff,'users/staff/companies/c'))).data().status,'approved');
   await assertSucceeds(getDocs(query(collection(staff,'companies/c/call_logs'),where('uid','==','staff'))));
   await assertFails(getDocs(collection(staff,'companies/c/members')));
   await assertFails(getDoc(ref(intruder,'companies/c')));
   await assertFails(getDoc(ref(intruder,'users/staff/companies/c')));
   await assertFails(updateDoc(ref(staff,'companies/c/members/staff'),{role:'owner'}));
  });
  await t.test('Spark devices and sync: atomic registration, replacement, retries and isolation',async()=>{
   const did='11111111-1111-1111-1111-111111111111', newer='22222222-2222-2222-2222-222222222222';
   const initial=Date.now()-3600000;
   const mr='companies/c/members/staff';
   const dp=id=>`companies/c/devices/staff_${id}`;
   const device=id=>({uid:'staff',deviceId:id,employeeName:'staff',manufacturer:'Google',model:'Pixel',androidVersion:'17',appVersion:'4.1.1',status:'active',registeredAt:serverTimestamp(),consentAt:serverTimestamp(),lastSeen:serverTimestamp(),callLogPermission:true,notificationPermission:true,pendingRecordCount:0,lastSyncError:'',lastSyncAttemptStatus:'REGISTERED'});
   const register=(id,replace=false)=>{const b=writeBatch(staff);b.set(ref(staff,dp(id)),device(id));b.update(ref(staff,mr),{activeDeviceId:id,initialSyncFrom:initial});if(replace)b.update(ref(staff,dp(did)),{status:'inactive'});return b.commit();};
   await assertFails(setDoc(ref(staff,dp(did)),device(did)));
   await assertFails(updateDoc(ref(staff,mr),{activeDeviceId:did,initialSyncFrom:initial}));
   await assertFails(setDoc(ref(intruder,dp(did)),device(did)));
   await assertSucceeds(register(did));
   await assertSucceeds(getDoc(ref(staff,dp(did))));
   await assertSucceeds(getDocs(query(collection(staff,'companies/c/devices'),where('uid','==','staff'))));
   await assertFails(updateDoc(ref(staff,mr),{initialSyncFrom:0}));
   await assertFails(updateDoc(ref(staff,mr),{role:'admin',activeDeviceId:did}));
   await assertSucceeds(updateDoc(ref(staff,dp(did)),{lastSeen:serverTimestamp(),lastSuccessfulSyncAt:serverTimestamp(),lastSyncAttemptStatus:'SUCCESS'}));
   const call=(id,dev=did)=>({uid:'staff',companyId:'c',sim:'',remoteNumber:'+971500000000',contactName:'',type:'OUTGOING',startedAt:Date.now()-1000,durationSec:12,createdAt:serverTimestamp(),deviceCallId:id,deviceId:dev});
   // Transactional client needs to read missing IDs before creating them.
   await assertSucceeds(getDoc(ref(staff,'companies/c/call_logs/missing')));
   const batch=writeBatch(staff);for(let i=0;i<100;i++)batch.set(ref(staff,`companies/c/call_logs/sync${i}`),call(`sync${i}`));
   await assertSucceeds(batch.commit());
   await assertSucceeds(updateDoc(ref(staff,'companies/c/call_logs/sync0'),{durationSec:12,type:'OUTGOING'}));
   await assertSucceeds(updateDoc(ref(staff,'companies/c/call_logs/sync0'),{durationSec:25}));
   await assertFails(updateDoc(ref(staff,'companies/c/call_logs/sync0'),{uid:'owner'}));
   await assertFails(updateDoc(ref(staff,'companies/c/call_logs/sync0'),{startedAt:Date.now()}));
   await assertFails(setDoc(ref(staff,'companies/c/call_logs/backdated'),{...call('backdated'),startedAt:initial-1}));
   await assertFails(setDoc(ref(staff,'companies/c/call_logs/future'),{...call('future'),startedAt:Date.now()+3600000}));
   await assertFails(setDoc(ref(staff,'companies/c/call_logs/other'),{...call('other'),uid:'owner'}));
   await assertFails(getDoc(ref(intruder,'companies/c/call_logs/sync0')));
   await assertSucceeds(getDoc(ref(owner,'companies/c/call_logs/sync0')));
   await assertFails(register(newer)); // Replacing requires atomic revocation of the old device.
   await assertSucceeds(register(newer,true));
   await assertFails(updateDoc(ref(staff,dp(did)),{lastSeen:serverTimestamp(),status:'active'}));
   await assertFails(setDoc(ref(staff,'companies/c/call_logs/old'),call('old')));
   await assertFails(updateDoc(ref(staff,'companies/c/call_logs/sync0'),{durationSec:30}));
   await assertSucceeds(setDoc(ref(staff,'companies/c/call_logs/new'),call('new',newer)));
   await assertSucceeds(updateDoc(ref(owner,mr),{approved:false}));
   await assertFails(updateDoc(ref(staff,dp(newer)),{lastSeen:serverTimestamp()}));
   await assertFails(setDoc(ref(staff,'companies/c/call_logs/revoked'),call('revoked',newer)));
   await assertSucceeds(updateDoc(ref(owner,mr),{approved:true}));
  });
  await t.test('live employee query receives newly synced calls and finalized durations', async()=>{
   const liveQuery=query(collection(staff,'companies/c/call_logs'),where('uid','==','staff'),where('startedAt','>=',0));
   const received=[];
   const stop=onSnapshot(liveQuery,s=>received.push(s.docs.map(d=>({id:d.id,...d.data()}))));
   async function waitFor(predicate){const end=Date.now()+5000;while(!received.some(predicate)){if(Date.now()>end)throw new Error('Live update was not delivered');await new Promise(r=>setTimeout(r,20));}}
   try {
    await waitFor(rows=>rows.some(r=>r.id==='new'));
    await setDoc(ref(staff,'companies/c/call_logs/live'),{uid:'staff',companyId:'c',sim:'',remoteNumber:'+971500000001',contactName:'',type:'OUTGOING',startedAt:Date.now()-1000,durationSec:12,createdAt:serverTimestamp(),deviceCallId:'live',deviceId:'22222222-2222-2222-2222-222222222222'});
    await waitFor(rows=>rows.some(r=>r.id==='live' && r.durationSec===12));
    await updateDoc(ref(staff,'companies/c/call_logs/live'),{durationSec:40});
    await waitFor(rows=>rows.some(r=>r.id==='live' && r.durationSec===40));
    assert.ok(received.every(rows=>rows.every(r=>r.uid==='staff')));
   } finally { stop(); }
  });
  await t.test('revoked and expired invitations cannot be claimed, legacy token is not a bypass',async()=>{
   await env.withSecurityRulesDisabled(async c=>{
    const db=c.firestore(); const i=(await getDoc(ref(db,`invitations/${code}`))).data();
    await setDoc(ref(db,'invitations/'+ 'D'.repeat(32)),{...i,email:'intruder@example.com',claimedBy:'',revoked:true});
    await setDoc(ref(db,'invitations/'+ 'E'.repeat(32)),{...i,email:'intruder@example.com',claimedBy:'',expiresAt:Timestamp.fromMillis(0)});
   });
   await assertFails(join(intruder,'intruder','D'.repeat(32)));
   await assertFails(join(intruder,'intruder','E'.repeat(32)));
   await assertFails(join(intruder,'intruder','A'.repeat(32)));
   await assertFails(setDoc(ref(intruder,'companies/c/members/intruder'),member('intruder','employee')));
  });
 } finally { await env.cleanup(); }
});
