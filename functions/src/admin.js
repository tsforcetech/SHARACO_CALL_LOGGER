import {randomBytes} from 'node:crypto';
import {DateTime} from 'luxon';
import {getAuth} from 'firebase-admin/auth';
import {db,callable,access,requireThat,text,id,companyRef,memberRef,audit,throttle,recent} from './core.js';
import {hash,initialBoundary} from './domain.js';
function profile(d){
 const name=text(d.name);requireThat(name,'Name is required.');
 const timeZone=text(d.timeZone||'Asia/Dubai');requireThat(DateTime.now().setZone(timeZone).isValid,'Choose a valid IANA time zone.');
 const country=text(d.country||'AE',2).toUpperCase();requireThat(/^[A-Z]{2}$/.test(country),'Use a two-letter country code.');
 return {name,timeZone,country,industry:text(d.industry),phone:text(d.phone,40),email:text(d.email),address:text(d.address,500),workingHours:text(d.workingHours),retentionDays:Math.max(7,Math.min(3650,Number(d.retentionDays)||365)),initialSyncPolicy:d.initialSyncPolicy==='JOIN_TIME'?'JOIN_TIME':'DAY_START',notificationsEnabled:d.notificationsEnabled!==false};
}
export const createCompany=callable(async(r,d)=>{
 await throttle(r.auth.uid,'company',3);const p=profile(d),ref=db.collection('companies').doc(),ur=db.doc(`users/${r.auth.uid}`),now=Date.now();
 await db.runTransaction(async tx=>{const u=await tx.get(ur);requireThat(!u.data()?.companyId,'You already belong to a company.','failed-precondition');
 tx.create(ref,{...p,ownerId:r.auth.uid,status:'ACTIVE',createdAt:now});
 tx.set(ref.collection('members').doc(r.auth.uid),{uid:r.auth.uid,role:'OWNER',status:'ACTIVE',name:text(r.auth.token.name||'Owner'),email:text(r.auth.token.email),joinedAt:now});
 tx.set(ur,{companyId:ref.id},{merge:true});audit(tx,ref.id,r.auth.uid,'COMPANY_CREATED',ref.id);});return {companyId:ref.id};
});
export const saveEmployee=callable(async(r,d)=>{
 const {m}=await access(r,d.companyId,true);const cr=companyRef(d.companyId),ref=d.employeeId?cr.collection('employees').doc(id(d.employeeId)):cr.collection('employees').doc();
 const p={name:text(d.name),code:text(d.code),email:text(d.email).toLowerCase(),mobile:text(d.mobile,40),department:text(d.department),designation:text(d.designation)};
 requireThat(p.name&&p.code,'Employee name and staff code are required.');
 await db.runTransaction(async tx=>{const old=(await tx.get(ref)).data();const member=old?.uid?(await tx.get(memberRef(cr.id,old.uid))).data():null;
 requireThat(m.role==='OWNER'||!member||member.role==='EMPLOYEE','Only the owner can edit administrators.','permission-denied');
 tx.set(ref,{...p,...(!old?{status:'INVITED',createdAt:Date.now()}: {})},{merge:true});
 if(old?.uid)tx.update(memberRef(cr.id,old.uid),p);audit(tx,cr.id,r.auth.uid,old?'EMPLOYEE_EDITED':'EMPLOYEE_ADDED',ref.id);});return {employeeId:ref.id};
});
export const createInvite=callable(async(r,d)=>{
 await access(r,d.companyId,true);await throttle(r.auth.uid,'invite');const cr=companyRef(d.companyId),er=cr.collection('employees').doc(id(d.employeeId)),token=randomBytes(24).toString('hex'),tokenHash=hash(token),expiresAt=Date.now()+Math.max(1,Math.min(30,Number(d.days)||7))*86400000;
 await db.runTransaction(async tx=>{const e=(await tx.get(er)).data();requireThat(e?.status==='INVITED','Only unjoined employees can be invited.','failed-precondition');if(e.inviteHash)tx.set(db.doc(`invitations/${e.inviteHash}`),{status:'REVOKED'},{merge:true});
 tx.create(db.doc(`invitations/${tokenHash}`),{companyId:cr.id,employeeId:er.id,createdBy:r.auth.uid,createdAt:Date.now(),expiresAt,status:'ACTIVE',maxUses:1});tx.update(er,{inviteHash:tokenHash,inviteExpiresAt:expiresAt});audit(tx,cr.id,r.auth.uid,'INVITE_CREATED',er.id);});return {token,expiresAt};
});
export const revokeInvite=callable(async(r,d)=>{
 await access(r,d.companyId,true);const er=companyRef(d.companyId).collection('employees').doc(id(d.employeeId));
 await db.runTransaction(async tx=>{const e=(await tx.get(er)).data();requireThat(e?.inviteHash,'No invitation exists.');tx.update(db.doc(`invitations/${e.inviteHash}`),{status:'REVOKED'});tx.update(er,{inviteExpiresAt:0});audit(tx,d.companyId,r.auth.uid,'INVITE_REVOKED',er.id);});return {ok:true};
});
function validInvite(i){requireThat(i?.status==='ACTIVE'&&i.expiresAt>Date.now(),'Invitation is invalid, expired or already used.','failed-precondition');}
export const inspectInvite=callable(async(r,d)=>{
 await throttle(r.auth.uid,'inspect');const i=(await db.doc(`invitations/${hash(text(d.token,100))}`).get()).data();validInvite(i);
 const [cs,es]=await Promise.all([companyRef(i.companyId).get(),companyRef(i.companyId).collection('employees').doc(i.employeeId).get()]);requireThat(cs.data()?.status==='ACTIVE'&&es.data()?.status==='INVITED','This invitation is unavailable.');
 return {companyName:cs.data().name,employeeName:es.data().name,employeeCode:es.data().code,expiresAt:i.expiresAt};
});
export const joinCompany=callable(async(r,d)=>{
 requireThat(d.consent===true,'Please accept the monitoring disclosure.');await throttle(r.auth.uid,'join');const ir=db.doc(`invitations/${hash(text(d.token,100))}`),ur=db.doc(`users/${r.auth.uid}`);
 return db.runTransaction(async tx=>{const [is,us]=await Promise.all([tx.get(ir),tx.get(ur)]),i=is.data();validInvite(i);requireThat(!us.data()?.companyId,'Leave your current company first.','failed-precondition');
 const cr=companyRef(i.companyId),er=cr.collection('employees').doc(i.employeeId);const [cs,es]=await Promise.all([tx.get(cr),tx.get(er)]);const c=cs.data(),e=es.data();requireThat(c?.status==='ACTIVE'&&e?.status==='INVITED','Invitation unavailable.');
 requireThat(!e.email||(r.auth.token.email_verified&&e.email===String(r.auth.token.email).toLowerCase()),'Use the Google account specified on the invitation.','permission-denied');const now=Date.now();
 tx.set(memberRef(cr.id,r.auth.uid),{...e,uid:r.auth.uid,employeeId:er.id,role:'EMPLOYEE',status:'ACTIVE',joinedAt:now,monitoringStartedAt:now,initialSyncFrom:initialBoundary(now,c.timeZone,c.initialSyncPolicy),consentVersion:1,consentAt:now,activeDeviceId:''});
 tx.update(er,{uid:r.auth.uid,status:'ACTIVE'});tx.update(ir,{status:'USED',usedAt:now,usedBy:r.auth.uid});tx.set(ur,{companyId:cr.id},{merge:true});audit(tx,cr.id,r.auth.uid,'EMPLOYEE_JOINED',er.id);
 tx.set(cr.collection('notifications').doc(),{title:'Employee joined',body:`${e.name} joined the company.`,timestamp:now});return {companyId:cr.id};});
});
export const employeeAction=callable(async(r,d)=>{
 const {m}=await access(r,d.companyId,true);const er=companyRef(d.companyId).collection('employees').doc(id(d.employeeId));
 await db.runTransaction(async tx=>{const e=(await tx.get(er)).data();requireThat(e,'Employee not found.');const mr=e.uid?memberRef(d.companyId,e.uid):null,old=mr?(await tx.get(mr)).data():null;
 requireThat(old?.role!=='OWNER'&&(m.role==='OWNER'||!old||old.role==='EMPLOYEE'),'Only the owner can manage administrators.','permission-denied');
 if(d.action==='ROLE'){requireThat(m.role==='OWNER'&&old?.status==='ACTIVE','Only the owner can change active employee roles.','permission-denied');requireThat(['ADMIN','EMPLOYEE'].includes(d.role),'Invalid role.');tx.update(mr,{role:d.role,manageEmployees:d.role==='ADMIN'&&d.manageEmployees===true});}
 else {requireThat(['ACTIVE','INACTIVE','SUSPENDED','REMOVED'].includes(d.action),'Invalid status.');requireThat(e.status!=='REMOVED','Removed employees must receive a new employee record.');requireThat(old||d.action==='REMOVED','Employee has not joined.');
 tx.update(er,{status:d.action});if(mr)tx.update(mr,{status:d.action});if(d.action==='REMOVED'){if(e.uid)tx.set(db.doc(`users/${e.uid}`),{companyId:''},{merge:true});if(e.inviteHash)tx.set(db.doc(`invitations/${e.inviteHash}`),{status:'REVOKED'},{merge:true});}}
 audit(tx,d.companyId,r.auth.uid,d.action==='ROLE'?'ROLE_CHANGED':`EMPLOYEE_${d.action}`,er.id);});return {ok:true};
});
export const updateCompany=callable(async(r,d)=>{
 const {c}=await access(r,d.companyId,false,true),p=profile({...c,...d});requireThat(p.timeZone===c.timeZone,'Time zone is fixed after creation to preserve reporting boundaries.');
 if(d.logo!==undefined){requireThat(typeof d.logo==='string'&&d.logo.length<180000,'Logo is too large.');p.logo=d.logo;}
 await db.runTransaction(async tx=>{tx.update(companyRef(d.companyId),p);audit(tx,d.companyId,r.auth.uid,'COMPANY_SETTING_CHANGED',d.companyId);});return {ok:true};
});
export const leaveCompany=callable(async(r,d)=>{
 const {m}=await access(r,d.companyId);requireThat(m.role!=='OWNER','Owners must delete the company before leaving.');requireThat(d.confirm==='LEAVE','Type LEAVE to confirm.');
 await db.runTransaction(async tx=>{tx.update(memberRef(d.companyId,r.auth.uid),{status:'REMOVED'});tx.update(companyRef(d.companyId).collection('employees').doc(m.employeeId),{status:'REMOVED'});tx.set(db.doc(`users/${r.auth.uid}`),{companyId:''},{merge:true});audit(tx,d.companyId,r.auth.uid,'EMPLOYEE_LEFT',m.employeeId);});return {ok:true};
});
export const requestDeletion=callable(async(r,d)=>{
 recent(r);requireThat(d.confirm==='DELETE','Type DELETE to confirm.');
 if(d.companyId){await access(r,d.companyId,false,true);await db.runTransaction(async tx=>{tx.update(companyRef(d.companyId),{status:'DELETING'});tx.set(db.doc(`deletionJobs/${id(d.companyId)}`),{companyId:d.companyId,requestedAt:Date.now()});audit(tx,d.companyId,r.auth.uid,'COMPANY_DELETION_REQUESTED',d.companyId);});}
 else {const u=(await db.doc(`users/${r.auth.uid}`).get()).data();if(u?.companyId){const m=(await memberRef(u.companyId,r.auth.uid).get()).data();requireThat(m?.role!=='OWNER','Delete your company before deleting your account.');await db.runTransaction(async tx=>{tx.update(memberRef(u.companyId,r.auth.uid),{status:'REMOVED'});if(m?.employeeId)tx.update(companyRef(u.companyId).collection('employees').doc(m.employeeId),{status:'REMOVED'});audit(tx,u.companyId,r.auth.uid,'ACCOUNT_DELETED',r.auth.uid);});}await getAuth().deleteUser(r.auth.uid);await db.doc(`users/${r.auth.uid}`).delete();}
 return {ok:true};
});
export const saveNotificationToken=callable(async(r,d)=>{await db.doc(`users/${r.auth.uid}`).set({fcmToken:text(d.token,4096)},{merge:true});return {ok:true};});
