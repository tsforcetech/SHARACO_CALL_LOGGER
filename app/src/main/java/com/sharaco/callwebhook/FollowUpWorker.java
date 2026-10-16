package com.sharaco.callwebhook;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class FollowUpWorker extends Worker {
 public FollowUpWorker(Context context,WorkerParameters params){super(context,params);}
 public static void schedule(Context c,String company,String uid){
  c.getSharedPreferences("followup_preferences",0).edit().putString("company",company).putString("uid",uid).putBoolean("enabled",true).apply();
  PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(FollowUpWorker.class,15,TimeUnit.MINUTES)
    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();
  WorkManager.getInstance(c).enqueueUniquePeriodicWork("sharaco-followups",ExistingPeriodicWorkPolicy.KEEP,request);
 }
 public static void stop(Context c){
  c.getSharedPreferences("followup_preferences",0).edit().putBoolean("enabled",false).remove("company").remove("uid").apply();
  WorkManager.getInstance(c).cancelUniqueWork("sharaco-followups");
 }
 @Override public Result doWork(){
  Context c=getApplicationContext();String uid=FirebaseAuth.getInstance().getUid();
  if(uid==null)return Result.success();
  android.content.SharedPreferences prefs=c.getSharedPreferences("followup_preferences",0);
  if(!prefs.getBoolean("enabled",false)||!uid.equals(prefs.getString("uid","")))return Result.success();
  String company=prefs.getString("company","");
  if(company.isEmpty())return Result.success();
  if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Result.success();
  try{
   FirebaseFirestore db=FirebaseFirestore.getInstance();
   DocumentSnapshot member=Tasks.await(db.collection("companies").document(company).collection("members").document(uid).get(),25,TimeUnit.SECONDS);
   if(!Boolean.TRUE.equals(member.getBoolean("approved")))return Result.success();
   QuerySnapshot leads=Tasks.await(db.collection("companies").document(company).collection("assigned_numbers").whereEqualTo("assignedTo",uid).limit(300).get(),25,TimeUnit.SECONDS);
   SimpleDateFormat formatter=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);formatter.setLenient(false);
   Date now=new Date();int overdue=0;String first="";
   for(DocumentSnapshot d:leads){
    String state=d.getString("status");String when=d.getString("nextFollowUp");
    if("won".equals(state)||"lost".equals(state)||when==null||when.isEmpty())continue;
    try{if(!formatter.parse(when).after(now)){overdue++;if(first.isEmpty())first=d.getString("name")==null?"Contact":d.getString("name");}}catch(Exception ignored){}
   }
   String key="followup_notice_"+company+"_"+uid;
   if(overdue==0){c.getSharedPreferences("followup_notifications",0).edit().remove(key).apply();return Result.success();}
   String signature=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(now)+":"+overdue;
   if(signature.equals(c.getSharedPreferences("followup_notifications",0).getString(key,"")))return Result.success();
   if(!prefs.getBoolean("enabled",false)||!uid.equals(prefs.getString("uid",""))||!company.equals(prefs.getString("company","")))return Result.success();
   NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
   if(manager==null)return Result.success();
   String channel="sharaco_followups";
   if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(new NotificationChannel(channel,"Follow-up reminders",NotificationManager.IMPORTANCE_DEFAULT));
   Intent intent=new Intent(c,CrmActivity.class);intent.putExtra("company",company);intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
   PendingIntent open=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   NotificationCompat.Builder notice=new NotificationCompat.Builder(c,channel).setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle(overdue+" follow-up"+(overdue==1?"":"s")+" due").setContentText("Company follow-ups need attention · "+first)
    .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_DEFAULT);
   manager.notify(3020,notice.build());
   c.getSharedPreferences("followup_notifications",0).edit().putString(key,signature).apply();
   return Result.success();
  }catch(Exception e){return Result.retry();}
 }
}
