package com.sharaco.callwebhook;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;

public class DeviceActivity extends Activity {
 FirebaseFirestore db;String cid,uid,role,deviceId;LinearLayout body;
 CollectionReference devices(){return db.collection("companies").document(cid).collection("devices");}
 boolean admin(){return "owner".equals(role)||"admin".equals(role);}
 void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 void label(String s){UiKit.cardText(this,body,s,null);}
 void button(String text,Runnable fn){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);GradientDrawable bg=new GradientDrawable();bg.setColor(UiKit.BRAND);bg.setCornerRadius(26);b.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,8,0,8);body.addView(b,lp);b.setOnClickListener(v->fn.run());}
 void page(String title){ScrollView s=new ScrollView(this);body=new LinearLayout(this);UiKit.screen(this,body);s.addView(body);setContentView(s);TextView head=new TextView(this);head.setText(title);UiKit.heading(head);head.setTextColor(Color.rgb(21,39,47));body.addView(head);}
 @Override public void onCreate(Bundle b){super.onCreate(b);cid=getIntent().getStringExtra("company");uid=FirebaseAuth.getInstance().getUid();if(uid==null||cid==null){finish();return;}db=FirebaseFirestore.getInstance();
  SharedPreferences prefs=getSharedPreferences("device_install",0);deviceId=prefs.getString("deviceId","");if(deviceId.isEmpty()){deviceId=UUID.randomUUID().toString();prefs.edit().putString("deviceId",deviceId).apply();}
  db.collection("companies").document(cid).collection("members").document(uid).get().addOnSuccessListener(d->{if(!Boolean.TRUE.equals(d.getBoolean("approved"))){finish();return;}role=d.getString("role");screen();}).addOnFailureListener(e->{message(e.getMessage());finish();});
 }
 void screen(){page("Company Devices");label("Device: "+Build.MANUFACTURER+" "+Build.MODEL);label("Android "+Build.VERSION.RELEASE);
  button("Register this device",()->register());
  if(admin())button("View company devices",()->list());
  button("Back to company",()->finish());
 }
 void register(){
  DocumentReference ref=devices().document(uid+"_"+deviceId);
  ref.get().addOnSuccessListener(old->{
   if(old.exists()){
    if("active".equals(old.getString("status"))){
     message("This device is already registered and active.");
     android.content.SharedPreferences settings=getSharedPreferences("settings",0);
     if(settings.getBoolean("syncConsent",false) && cid.equals(settings.getString("syncConsentCompany","")))CallStore.schedule(this);
    }else message("This device has been disabled by an administrator. Contact your company owner.");
    return;
   }
   Map<String,Object> data=new HashMap<>();
   data.put("uid",uid);data.put("model",Build.MANUFACTURER+" "+Build.MODEL);
   data.put("androidVersion",Build.VERSION.RELEASE);
   data.put("lastSeen",FieldValue.serverTimestamp());data.put("status","active");
   ref.set(data).addOnSuccessListener(v->{
    message("Device registered. Automatic sync requires call permissions and company consent.");
    android.content.SharedPreferences sp=getSharedPreferences("settings",0);
    if(sp.getBoolean("syncConsent",false) && cid.equals(sp.getString("syncConsentCompany","")))CallStore.schedule(this);
   }).addOnFailureListener(e->message("Registration failed: "+e.getMessage()));
  }).addOnFailureListener(e->message("Cannot verify device status: "+e.getMessage()));
 }
 void list(){if(!admin()){message("Admin access required");return;}page("Registered Devices");
  devices().limit(200).get().addOnSuccessListener(s->{if(s.isEmpty())label("No devices registered");for(DocumentSnapshot d:s){label(d.getString("model")+" · "+d.getString("status")+"\nEmployee: "+d.getString("uid"));
   if("active".equals(d.getString("status")))button("Deactivate "+d.getId().substring(0,Math.min(12,d.getId().length())),()->d.getReference().update("status","inactive").addOnSuccessListener(v->list()).addOnFailureListener(e->message(e.getMessage())));
  }}).addOnFailureListener(e->message(e.getMessage()));button("Back",()->screen());
 }
}