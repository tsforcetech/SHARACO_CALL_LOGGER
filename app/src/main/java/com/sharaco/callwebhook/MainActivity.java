package com.sharaco.callwebhook;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.widget.*;
import android.view.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.*;

public class MainActivity extends Activity {
 LinearLayout panel; FirebaseAuth auth;FirebaseFirestore db;CallStore store;
 String company="",sim="",role="";
 void label(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(17);v.setPadding(0,12,0,8);panel.addView(v);}
 EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);panel.addView(v);return v;}
 void button(String s,Runnable job){Button b=new Button(this);b.setText(s);panel.addView(b);b.setOnClickListener(v->job.run());}
 void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
 void page(String title){panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(24,24,24,24);ScrollView sv=new ScrollView(this);sv.addView(panel);setContentView(sv);label(title);}
 String uid(){return auth.getUid();}
 @Override public void onCreate(Bundle b){super.onCreate(b);auth=FirebaseAuth.getInstance();db=FirebaseFirestore.getInstance();store=new CallStore(this);company=getPreferences(0).getString("company","");sim=getPreferences(0).getString("sim","");home();}
 void home(){page("SHARACO CALL LOGGER");
  if(uid()==null){login();return;}
  label(auth.getCurrentUser().getEmail());button("Sign out",()->{auth.signOut();company="";home();});
  button("Create company",()->createCompany());
  button("Join company (QR)",()->scanJoin());
  String pending=getPreferences(0).getString("pendingCompany","");if(!pending.isEmpty())button("Check joined company approval",()->choose(pending,""));
  if(!company.isEmpty())companyHome();else showMyCompanies();
 }
 void login(){EditText email=input("Email"),pass=input("Password");pass.setInputType(129);
  button("Login",()->auth.signInWithEmailAndPassword(email.getText().toString().trim(),pass.getText().toString()).addOnSuccessListener(a->home()).addOnFailureListener(e->toast(e.getMessage())));
  button("Register",()->auth.createUserWithEmailAndPassword(email.getText().toString().trim(),pass.getText().toString()).addOnSuccessListener(a->home()).addOnFailureListener(e->toast(e.getMessage())));
 }
 void createCompany(){page("Create Company");EditText name=input("Company name");button("Save company",()->{
  String n=name.getText().toString().trim();if(n.isEmpty()){toast("Company name required");return;}
  String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
  Map<String,Object> c=new HashMap<>();c.put("name",n);c.put("ownerUid",uid());c.put("joinToken",token);c.put("createdAt",FieldValue.serverTimestamp());
  DocumentReference d=db.collection("companies").document();
  Map<String,Object> member=new HashMap<>();member.put("uid",uid());member.put("role","owner");member.put("approved",true);member.put("sim","");member.put("assignedUsers",new ArrayList<String>());
  WriteBatch b=db.batch();b.set(d,c);b.set(d.collection("members").document(uid()),member);
  b.commit().addOnSuccessListener(v->{choose(d.getId(),"");}).addOnFailureListener(e->toast(e.getMessage()));
 });button("Back",()->home());}
 void showMyCompanies(){label("Companies I own");db.collection("companies").whereEqualTo("ownerUid",uid()).get().addOnSuccessListener(s->{for(DocumentSnapshot d:s)button("Open "+d.getString("name"),()->choose(d.getId(),""));});
  label("My approved memberships"); // Memberships in other companies can be opened using the QR scan again.
 }
 void choose(String cid,String ownSim){company=cid;sim=ownSim;getPreferences(0).edit().putString("company",company).putString("sim",sim).apply();home();}
 void companyHome(){
  label("Company ID: "+company);button("Change company",()->{company="";getPreferences(0).edit().remove("company").apply();home();});
  db.collection("companies").document(company).collection("members").document(uid()).get().addOnSuccessListener(doc->{
   if(!Boolean.TRUE.equals(doc.getBoolean("approved"))){label("Awaiting owner approval (or membership not yet created)");return;}
   role=doc.getString("role");sim=doc.getString("sim")==null?"":doc.getString("sim");
   getPreferences(0).edit().putString("sim",sim).apply();
   label("Role: "+role+" | Company SIM: "+sim);
   button("Local call history",()->localHistory());
   button("Enable call-log access",()->{if(checkSelfPermission(Manifest.permission.READ_CALL_LOG)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG,Manifest.permission.READ_CONTACTS},12);else toast("Already granted");});
   button("Sync call history now",()->{store.scan(this,company,sim);store.upload();CallStore.schedule(this);toast("Sync started");});
   button("My company report",()->report());
   if(role.equals("owner")||role.equals("admin")){button("Company QR code",()->showQr());button("Pending join requests",()->requests());button("Team call logs",()->teamLogs());}
  }).addOnFailureListener(e->toast(e.getMessage()));
 }
 void showQr(){db.collection("companies").document(company).get().addOnSuccessListener(d->{
   String token=d.getString("joinToken");if(token==null){toast("QR not available");return;}
   page("Company joining QR");String value="SHARACO:"+company+":"+token;try{
    BitMatrix bits=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,640,640);Bitmap image=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);
    for(int y=0;y<640;y++)for(int x=0;x<640;x++)image.setPixel(x,y,bits.get(x,y)?0xff000000:0xffffffff);
    ImageView iv=new ImageView(this);iv.setImageBitmap(image);panel.addView(iv);
   }catch(Exception e){toast(e.getMessage());}button("Back",()->home());
  }).addOnFailureListener(e->toast(e.getMessage()));}
 void scanJoin(){new IntentIntegrator(this).setPrompt("Scan company QR").setBeepEnabled(false).initiateScan();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);
  if(scan!=null&&scan.getContents()!=null){String[] parts=scan.getContents().split(":");if(parts.length==3&&parts[0].equals("SHARACO"))join(parts[1],parts[2]);else toast("Invalid company code");}}
 void join(String cid,String token){page("Request to Join Company");EditText name=input("Your name");EditText phone=input("Your company SIM number (+countrycode...)");
  button("Submit request",()->{
   String n=name.getText().toString().trim(),p=phone.getText().toString().trim();if(n.isEmpty()||!p.matches("\\+[0-9]{8,15}")){toast("Enter name and international SIM number");return;}
   Map<String,Object> m=new HashMap<>();m.put("uid",uid());m.put("name",n);m.put("sim",p);m.put("token",token);m.put("status","pending");m.put("requestedAt",FieldValue.serverTimestamp());
   db.collection("companies").document(cid).collection("join_requests").document(uid()).set(m).addOnSuccessListener(v->{getPreferences(0).edit().putString("pendingCompany",cid).apply();toast("Request submitted; owner approval required");}).addOnFailureListener(e->toast(e.getMessage()));
  });button("Back",()->home());
 }
 void requests(){page("Pending join requests");db.collection("companies").document(company).collection("join_requests").whereEqualTo("status","pending").get().addOnSuccessListener(s->{
  for(DocumentSnapshot d:s){label(d.getString("name")+" | "+d.getString("sim"));button("Approve "+d.getString("name"),()->{
   Map<String,Object> m=new HashMap<>();m.put("uid",d.getId());m.put("role","employee");m.put("approved",true);m.put("sim",d.getString("sim"));m.put("assignedUsers",new ArrayList<String>());
   WriteBatch b=db.batch();b.set(db.collection("companies").document(company).collection("members").document(d.getId()),m);
   b.update(d.getReference(),"status","approved");b.commit().addOnSuccessListener(v->requests()).addOnFailureListener(e->toast(e.getMessage()));
  });}}).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());}
 void localHistory(){page("Local SIM Call History");for(String row:store.history(company))label(row);button("Back",()->home());}
 void teamLogs(){page("Company Call Logs");db.collection("companies").document(company).collection("call_logs").orderBy("startedAt",Query.Direction.DESCENDING).limit(100).get().addOnSuccessListener(s->{for(DocumentSnapshot d:s)label(d.getString("remoteNumber")+" | "+d.getString("type")+" | "+d.getLong("durationSec")+" sec");}).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());}
 void report(){page("Call Summary");db.collection("companies").document(company).collection("call_logs").limit(1000).get().addOnSuccessListener(s->{
   long seconds=0;Map<String,Integer> byType=new TreeMap<>();for(DocumentSnapshot d:s){String t=d.getString("type");byType.put(t,byType.getOrDefault(t,0)+1);Long v=d.getLong("durationSec");seconds+=v==null?0:v;}
   label("Sample of up to 1,000 authorized calls");label("Total calls: "+s.size());label("Total duration: "+seconds+" seconds");for(String t:byType.keySet())label(t+": "+byType.get(t));
  }).addOnFailureListener(e->toast(e.getMessage()));button("Back",()->home());}
}
