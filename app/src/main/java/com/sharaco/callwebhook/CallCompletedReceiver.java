package com.sharaco.callwebhook;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;

/** Trigger an expedited best-effort sync when Android announces that a call has ended.
 * WorkManager periodic work remains the fallback; Android may limit broadcast delivery.
 */
public final class CallCompletedReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent) {
  if(intent==null||!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction()))return;
  if(!TelephonyManager.EXTRA_STATE_IDLE.equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE)))return;
  if(context.checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED ||
     context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return;
  android.content.SharedPreferences prefs=context.getSharedPreferences("settings",0);
  if(!prefs.getBoolean("syncConsent",false)||
     !prefs.getString("syncCompany","").equals(prefs.getString("syncConsentCompany",""))||
     prefs.getString("syncSim","").isEmpty())return;
  CallStore.syncNow(context);
 }
}
