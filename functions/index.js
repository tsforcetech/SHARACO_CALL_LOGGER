"use strict";
const {onCall, HttpsError}=require("firebase-functions/v2/https");
const admin=require("firebase-admin");
admin.initializeApp();
const firestore=admin.firestore();

exports.deleteCompany=onCall({region:"us-central1",timeoutSeconds:540,memory:"1GiB"},async(request)=>{
 const uid=request.auth?.uid;
 const cid=request.data?.companyId;
 const confirmation=request.data?.confirm;
 if(!uid)throw new HttpsError("unauthenticated","Sign in to delete a company.");
 if(typeof cid!=="string"||!/^[-A-Za-z0-9_]{8,128}$/.test(cid)||confirmation!==cid)
  throw new HttpsError("invalid-argument","Enter the exact company ID to confirm deletion.");
 const ref=firestore.collection("companies").doc(cid);
 const doc=await ref.get();
 if(!doc.exists)throw new HttpsError("not-found","Company does not exist.");
 if(doc.get("ownerUid")!==uid)throw new HttpsError("permission-denied","Only the owner can permanently delete this company.");
 const members=await ref.collection("members").get();
 // Recheck ownership before each destructive operation; deletion may be retried.
 for(let start=0;start<members.docs.length;start+=400){
  const batch=firestore.batch();
  for(const member of members.docs.slice(start,start+400)){
   batch.delete(firestore.collection("users").doc(member.id).collection("companies").doc(cid));
  }
  await batch.commit();
 }
 await firestore.recursiveDelete(ref);
 return {deleted:true,companyId:cid};
});
