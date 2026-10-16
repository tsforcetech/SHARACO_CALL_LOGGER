package com.sharaco.callwebhook;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.Manifest;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.content.pm.PackageManager;
import androidx.work.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.android.gms.tasks.Tasks;

public class CallStore extends SQLiteOpenHelper {
 public CallStore(Context c){super(c,"call_history.db",null,1);}
 @Override public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE calls(id TEXT PRIMARY KEY,company TEXT,uid TEXT,sim TEXT,remote TEXT,contact TEXT,calltype TEXT,startms INTEGER,duration INTEGER,uploaded INTEGER DEFAULT 0)");}
 @Override public void onUpgrade(SQLiteDatabase d,int o,int n){}
 public synchronized void scan(Context c,String company,String sim){
  if(c.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)return;
  if(company==null||company.isEmpty()||sim==null||sim.isEmpty())return;
  String uid=FirebaseAuth.getInstance().getUid();if(uid==null)return;
  if(!safeSingleSim(c))return;
  long consentStart=c.getSharedPreferences("settings",0).getLong("syncConsentStartedAt",0L);
  if(consentStart<=0L)return;
  String[] cols={CallLog.Calls._ID,CallLog.Calls.NUMBER,CallLog.Calls.TYPE,CallLog.Calls.DATE,CallLog.Calls.DURATION};
  try(Cursor cursor=c.getContentResolver().query(CallLog.Calls.CONTENT_URI,cols,null,null,CallLog.Calls.DATE+" DESC")){
   if(cursor==null)return;int count=0;
   while(cursor.moveToNext()&&count++<2000){
    long row=cursor.getLong(0),ms=cursor.getLong(3),duration=cursor.getLong(4);
    if(ms<consentStart)break;
    String number=cursor.getString(1);int t=cursor.getInt(2);
    String type=t==CallLog.Calls.INCOMING_TYPE?"INCOMING":t==CallLog.Calls.OUTGOING_TYPE?"OUTGOING":t==CallLog.Calls.MISSED_TYPE?"MISSED":t==CallLog.Calls.REJECTED_TYPE?"REJECTED":t==CallLog.Calls.BLOCKED_TYPE?"BLOCKED":"OTHER";
    String contact=contact(c,number);
    ContentValues v=new ContentValues();String install=c.getSharedPreferences("device_install",0).getString("deviceId","");
    if(install.isEmpty())return;
    v.put("id",company+"_"+uid+"_"+install+"_"+row);v.put("company",company);v.put("uid",uid);v.put("sim",sim);v.put("remote",number);v.put("contact",contact);v.put("calltype",type);v.put("startms",ms);v.put("duration",duration);
    getWritableDatabase().insertWithOnConflict("calls",null,v,SQLiteDatabase.CONFLICT_IGNORE);
   }
  }catch(Exception ignored){}
 }
 public static boolean safeSingleSim(Context c){
  if(c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)){
   if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return false;
   try{
    SubscriptionManager manager=(SubscriptionManager)c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    if(manager==null)return false;
    java.util.List<SubscriptionInfo> active=manager.getActiveSubscriptionInfoList();
    return active!=null && active.size()==1;
   }catch(SecurityException e){return false;}
  }
  return false;
 }
 private String contact(Context c,String n){
  if(n==null||c.checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return "";
  try(Cursor cur=c.getContentResolver().query(android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,android.net.Uri.encode(n)),new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},null,null,null)){
    if(cur!=null&&cur.moveToFirst())return cur.getString(0);
  }catch(Exception ignored){}return "";
 }
 public synchronized ArrayList<String> history(String company){
  ArrayList<String> a=new ArrayList<>();
  try(Cursor x=getReadableDatabase().rawQuery("SELECT remote,contact,calltype,duration,startms,uploaded FROM calls WHERE company=? AND uid=? ORDER BY startms DESC LIMIT 200",new String[]{company,FirebaseAuth.getInstance().getUid()==null?"":FirebaseAuth.getInstance().getUid()})){
   while(x.moveToNext())a.add((x.getString(1).isEmpty()?x.getString(0):x.getString(1)+" ("+x.getString(0)+")")+" | "+x.getString(2)+" | "+x.getLong(3)+"s | "+new java.util.Date(x.getLong(4))+" | "+(x.getInt(5)==1?"SYNCED":"PENDING"));
  }return a;
 }
 public synchronized int countPending(String cid){
  int result=0;try(Cursor x=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM calls WHERE company=? AND uid=? AND uploaded=0",new String[]{cid,FirebaseAuth.getInstance().getUid()==null?"":FirebaseAuth.getInstance().getUid()})){if(x.moveToFirst())result=x.getInt(0);}return result;
 }
 public synchronized int countSynced(String cid){
  int result=0;try(Cursor x=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM calls WHERE company=? AND uid=? AND uploaded=1",new String[]{cid,FirebaseAuth.getInstance().getUid()==null?"":FirebaseAuth.getInstance().getUid()})){if(x.moveToFirst())result=x.getInt(0);}return result;
 }
 public static long lastSync(Context c){return c.getSharedPreferences("settings",0).getLong("lastSync",0);}
 public synchronized int eligiblePending(String companyId,String sim,long consentStartedAt){
  String uid=FirebaseAuth.getInstance().getUid();if(uid==null)return 0;
  try(Cursor x=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM calls WHERE uploaded=0 AND uid=? AND company=? AND sim=? AND startms>=?",new String[]{uid,companyId,sim,String.valueOf(consentStartedAt)})){
   return x.moveToFirst()?x.getInt(0):0;
  }
 }
 public synchronized boolean uploadBlocking(String companyId,String registeredSim,long consentStartedAt){
  String current=FirebaseAuth.getInstance().getUid();if(current==null)return false;
  boolean success=true;
  try(Cursor x=getReadableDatabase().rawQuery("SELECT id,company,uid,sim,remote,contact,calltype,startms,duration FROM calls WHERE uploaded=0 AND uid=? AND company=? AND sim=? AND startms>=? ORDER BY startms ASC LIMIT 100",new String[]{current,companyId,registeredSim,String.valueOf(consentStartedAt)})){
   while(x.moveToNext()){
    String id=x.getString(0),cid=x.getString(1),owner=x.getString(2),sim=x.getString(3),remote=x.getString(4),contact=x.getString(5),type=x.getString(6);
    if(!cid.equals(companyId))continue;

    long start=x.getLong(7),dur=x.getLong(8);
    Map<String,Object> m=new HashMap<>();m.put("uid",owner);m.put("companyId",cid);m.put("sim",sim);
    m.put("remoteNumber",remote==null?"":remote);m.put("fromNumber",type.equals("OUTGOING")?sim:remote);
    m.put("toNumber",type.equals("OUTGOING")?remote:sim);m.put("contactName",contact==null?"":contact);
    m.put("type",type);m.put("startedAt",start);m.put("durationSec",dur);
    m.put("createdAt",com.google.firebase.firestore.FieldValue.serverTimestamp());m.put("deviceCallId",id);
    try{
      Tasks.await(FirebaseFirestore.getInstance().collection("companies").document(cid).collection("call_logs").document(id).set(m),30,TimeUnit.SECONDS);
      ContentValues done=new ContentValues();done.put("uploaded",1);getWritableDatabase().update("calls",done,"id=?",new String[]{id});
    }catch(Exception e){success=false;break;}
   }
  }catch(Exception e){return false;}
  return success;
 }
 public static void schedule(Context c){
  Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
  PeriodicWorkRequest task=new PeriodicWorkRequest.Builder(SyncWorker.class,15,TimeUnit.MINUTES).setConstraints(network).build();
  WorkManager.getInstance(c).enqueueUniquePeriodicWork("call-sync",ExistingPeriodicWorkPolicy.KEEP,task);
  syncNow(c);
 }
 public static void syncNow(Context c){
  Constraints network=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
  OneTimeWorkRequest task=new OneTimeWorkRequest.Builder(SyncWorker.class).setConstraints(network).build();
  WorkManager.getInstance(c).enqueueUniqueWork("call-sync-now",ExistingWorkPolicy.KEEP,task);
 }
 public static void stop(Context c){
  WorkManager.getInstance(c).cancelUniqueWork("call-sync-now");
  WorkManager.getInstance(c).cancelUniqueWork("call-sync");
 }
 public static Worker.Result pause(android.content.SharedPreferences status,String reason){status.edit().putString("status",reason).apply();return Worker.Result.success();}
 public static class SyncWorker extends Worker {
  public SyncWorker(Context c,WorkerParameters p){super(c,p);}
  @Override public Result doWork(){
   Context c=getApplicationContext();
   android.content.SharedPreferences state=c.getSharedPreferences("sync_diagnostics",0);
   state.edit().putLong("lastAttempt",System.currentTimeMillis()).putString("status","Checking prerequisites").apply();
   String cid=c.getSharedPreferences("settings",0).getString("syncCompany","");
   String sim=c.getSharedPreferences("settings",0).getString("syncSim","");
   if(FirebaseAuth.getInstance().getUid()==null || cid.isEmpty() || sim.isEmpty())return pause(state,"Sign in and register company SIM");
   if(!c.getSharedPreferences("settings",0).getBoolean("syncConsent",false) || !cid.equals(c.getSharedPreferences("settings",0).getString("syncConsentCompany","")))return pause(state,"Consent not enabled for this company");
   if(c.getSharedPreferences("settings",0).getLong("syncConsentStartedAt",0L)<=0L)return pause(state,"Consent start time missing");
   try{
    DocumentSnapshot member=Tasks.await(FirebaseFirestore.getInstance().collection("companies").document(cid).collection("members").document(FirebaseAuth.getInstance().getUid()).get(),30,TimeUnit.SECONDS);
    if(!Boolean.TRUE.equals(member.getBoolean("approved")) || !sim.equals(member.getString("sim")))return pause(state,"Company membership or SIM not verified");
    String deviceId=c.getSharedPreferences("device_install",0).getString("deviceId","");
    if(!deviceId.isEmpty()){
      DocumentSnapshot device=Tasks.await(FirebaseFirestore.getInstance().collection("companies").document(cid).collection("devices").document(FirebaseAuth.getInstance().getUid()+"_"+deviceId).get(),30,TimeUnit.SECONDS);
      if(!device.exists() || !"active".equals(device.getString("status")) || !FirebaseAuth.getInstance().getUid().equals(device.getString("uid")))return pause(state,"Registered device is inactive or unavailable");
    }else return pause(state,"Register this device before syncing");
   }catch(Exception e){state.edit().putString("status","Verification failed; retry queued").apply();return Result.retry();}
   if(!CallStore.safeSingleSim(c))return pause(state,"Only verified single-SIM phones can sync");
   CallStore s=new CallStore(c);s.scan(c,cid,sim);
   long consent=c.getSharedPreferences("settings",0).getLong("syncConsentStartedAt",0L);
   int initial=s.eligiblePending(cid,sim,consent);
   state.edit().putInt("lastEligibleCount",initial).apply();
   if(!s.uploadBlocking(cid,sim,consent)){
    state.edit().putString("status","Upload failed; retry scheduled").apply();return Result.retry();
   }
   int remaining=s.eligiblePending(cid,sim,consent);
   if(remaining>0){
    state.edit().putString("status","Uploaded first batch; "+remaining+" calls still queued").apply();
    return Result.retry();
   }
   c.getSharedPreferences("settings",0).edit().putLong("lastSync",System.currentTimeMillis()).apply();
   state.edit().putString("status","Completed; all eligible calls uploaded").putInt("lastEligibleCount",0)
      .putLong("lastSuccess",System.currentTimeMillis()).apply();
   return Result.success();
  }
 }
}
